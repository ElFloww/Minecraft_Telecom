import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { test } from 'node:test';
import vm from 'node:vm';
import * as coverage from '../coverage.js';
import { MapImageStore } from '../map-image.js';
import { metadata, pngResponse } from './map-image-fixture.js';

const source = (await readFile(new URL('../main.js', import.meta.url), 'utf8')).replace(/^import .*;$/gm, '');
async function flush() { for (let i = 0; i < 32; i++) await Promise.resolve(); }

async function dashboard({ fetcher, bitmap, offscreen, manualTimers = false } = {}) {
    let now = Date.now();
    const requests = [], draws = [], timers = [], logs = [];
    const elements = new Map(), documentListeners = {};
    const canvasContext = new Proxy({}, { get(target, key) {
        return key in target ? target[key] : (...args) => draws.push({ method: key, args, color: target.fillStyle });
    } });
    const context = vm.createContext({
        ...coverage, MapImageStore, OffscreenCanvas: offscreen,
        document: {
            hidden: false, body: { style: {} },
            addEventListener(name, fn) { documentListeners[name] = fn; },
            getElementById(id) {
                if (!elements.has(id)) elements.set(id, {
                    style: {}, value: '', textContent: '', clientWidth: 800, clientHeight: 600,
                    offsetWidth: 300, offsetHeight: 180,
                    listeners: {}, checked: id === 'show-infra',
                    addEventListener(name, fn) { this.listeners[name] = fn; }, getContext() { return canvasContext; },
                    getBoundingClientRect() { return { left: 300, top: 0 }; },
                    replaceChildren(...children) { this.children = children; this.value = children[0]?.value || ''; },
                });
                return elements.get(id);
            },
        },
        window: { innerWidth: 1100, innerHeight: 600, addEventListener() {} },
        console: { warn: (...args) => logs.push(args), error: (...args) => logs.push(args) }, Headers, AbortSignal, URLSearchParams,
        Date: class extends Date { static now() { return now; } },
        Option: class { constructor(text, value) { this.text = text; this.value = value; } },
        setTimeout(callback, delay) {
            if (delay > 200) return;
            if (manualTimers) timers.push({ callback, at: now + delay });
            else queueMicrotask(() => { now += delay; callback(); });
        },
        setInterval() {}, requestAnimationFrame() {}, cancelAnimationFrame() {},
        createImageBitmap: async blob => ({ width: 512, height: 512, ...(await (bitmap?.(blob) ?? { close() {} })) }),
        fetch: async (path, options) => {
            requests.push({ path, options, at: now });
            if (fetcher) return fetcher(path, options);
            return { ok: true, status: 200, json: async () => path.includes('network') ? { nodes: [], edges: [] } : [] };
        },
    });
    vm.runInContext(source, context);
    await vm.runInContext('requestQueue', context);
    for (const [id, value] of Object.entries({ 'coverage-antenna': 'all', 'coverage-technology': 'all',
        'coverage-band': 'all', 'coverage-height': 'surface', 'coverage-step': 'auto' })) elements.get(id).value = value;
    requests.length = 0;
    now += 200;
    return { context, requests, elements, draws, logs, documentListeners, advance: ms => {
        now += ms;
        for (const timer of timers.splice(0)) {
            if (timer.at <= now) timer.callback();
            else timers.push(timer);
        }
    }, run: code => {
        const result = vm.runInContext(code, context);
        return code === 'requestQueue' ? result.then(flush) : result;
    } };
}

test('token is memory-only, sent as Bearer, and cleared on session reset', async () => {
    const { run, requests, elements } = await dashboard();
    run("setSessionToken('session-secret')");
    await run('requestQueue');
    assert.ok(requests.length > 0);
    for (const { path, options } of requests) {
        assert.equal(options.headers.get('Authorization'), 'Bearer session-secret');
        assert.ok(!path.includes('session-secret'));
        assert.equal(options.credentials, 'omit');
    }
    assert.equal(elements.get('session-token').value, '');
    run("setSessionToken('')");
    await run('requestQueue');
    const response = await run("apiFetch('/api/network')");
    assert.equal(response.status, 200);
    assert.equal(requests.at(-1).options.headers.has('Authorization'), false);
});

test('network metadata drives one authenticated global PNG; 500 pan/zoom and unchanged polls add no image fetch', async () => {
    let revision = 'r1', ready = false;
    const mapId = 'opaque /?&=+#';
    const { run, requests, draws, elements } = await dashboard({ fetcher: async path => path === '/api/network'
        ? { status: 200, json: async () => ({ nodes: [], edges: [], mapId, mapImage: metadata({ revision, ready }) }) }
        : pngResponse({ revision, blocksPerPixel: 8 }) });
    run("setSessionToken('image-secret')");
    await run('requestQueue');
    assert.equal(requests.filter(r => r.path.startsWith('/api/map-image')).length, 0);
    ready = true;
    await run('fetchNetworkData()');
    await run('requestQueue');
    assert.ok(run('mapImageStore.snapshot'));
    for (let i = 0; i < 500; i++) {
        run('pan.x -= 123; pan.y += 27; zoomAt(zoom > 2 ? 0.5 : 1.5, 400, 300); draw(); updateTerrain()');
        draws.length = 0;
    }
    for (let i = 0; i < 5; i++) await run('fetchNetworkData()');
    const images = () => requests.filter(r => r.path.startsWith('/api/map-image'));
    assert.equal(images().length, 1);
    assert.equal(images()[0].path, `/api/map-image?map=${encodeURIComponent(mapId)}`);
    assert.equal(images()[0].options.headers.get('Authorization'), 'Bearer image-secret');
    assert.equal(images()[0].options.cache, 'no-store');
    assert.match(elements.get('terrain-status').textContent, /pas raster 8 blocs\/pixel/);
    revision = 'r2';
    await run('fetchNetworkData()');
    await run('requestQueue');
    for (let i = 0; i < 20; i++) run('updateTerrain(); draw()');
    await run('requestQueue');
    assert.equal(images().length, 2);
    assert.ok(requests.every(r => !/^\/api\/(terrain|tile)/.test(r.path)));
});

test('world and token resets close snapshots, invalidate radio, and keep opaque map identity', async () => {
    let mapId = 'world-a', ready = false, closed = 0;
    const { run, requests } = await dashboard({
        fetcher: async path => path === '/api/network'
            ? { status: 200, json: async () => ({ nodes: [], edges: [], mapId, mapImage: metadata({ ready }) }) }
            : pngResponse(),
        bitmap: async () => ({ close() { closed++; } }),
    });
    ready = true;
    await run('fetchNetworkData()');
    await run('requestQueue');
    seedCoverage(run);
    const generation = run('terrainGeneration');
    mapId = 'world / b';
    ready = false;
    await run('fetchNetworkData()');
    assert.equal(run('terrainGeneration'), generation + 1);
    assert.equal(run('mapImageStore.snapshot'), null);
    assert.equal(run('coverageStore.cache.size'), 0);
    assert.equal(closed, 1);
    ready = true;
    await run('fetchNetworkData()');
    await run('requestQueue');
    assert.equal(requests.at(-1).path, '/api/map-image?map=world%20%2F%20b');
    ready = false;
    run("setSessionToken('new-token')");
    assert.equal(run('mapImageStore.snapshot'), null);
    assert.equal(closed, 2);
    await run('requestQueue');
});

test('queued stale image request is cancelled before fetch after world switch', async () => {
    let mapId = 'world-a';
    const { run, requests } = await dashboard({ fetcher: async () => ({ status: 200,
        json: async () => ({ nodes: [], edges: [], mapId }) }) });
    mapId = 'world-b';
    const network = run('fetchNetworkData()');
    run(`mapImageStore.select('world-a', ${JSON.stringify(metadata())})`);
    const image = run('mapImageStore.tick()');
    await Promise.all([network, image]);
    assert.equal(requests.length, 1);
    assert.equal(requests[0].path, '/api/network');
    assert.equal(run('mapImageStore.snapshot'), null);
});

test('request queue rejects excess work instead of accumulating promises', async () => {
    const { run } = await dashboard();
    const accepted = Array.from({ length: 8 }, () => run("apiFetch('/api/network')"));
    await assert.rejects(run("apiFetch('/api/network')"), /queue full/);
    await Promise.all(accepted);
});

test('untrusted IP text is escaped before HTML interpolation', async () => {
    const { run } = await dashboard();
    assert.equal(run(`escapeHtml('<img src=x onerror="steal()">')`), '&lt;img src=x onerror=&quot;steal()&quot;&gt;');
});

test('coverage is opt-in and shares Bearer serialization without URL credentials', async () => {
    const { run, requests, elements } = await dashboard();
    run('updateComputedCoverage()');
    assert.equal(requests.length, 0);
    run("setSessionToken('coverage-secret')");
    await run('requestQueue');
    elements.get('cov-computed').checked = true;
    run('updateComputedCoverage()');
    await run('requestQueue');
    const request = requests.find(r => r.path === '/api/coverage/options');
    assert.ok(request);
    assert.equal(request.options.headers.get('Authorization'), 'Bearer coverage-secret');
    assert.ok(!request.path.includes('coverage-secret'));
});

test('manual refresh and session reset immediately invalidate coverage cache', async () => {
    const { run, elements } = await dashboard();
    run("coverageStore.cache.set('0/0,0', { data: {} })");
    elements.get('coverage-refresh').listeners.click();
    assert.equal(run('coverageStore.cache.size'), 0);
    run("coverageStore.cache.set('0/0,0', { data: {} }); setSessionToken('next')");
    assert.equal(run('coverageStore.cache.size'), 0);
    assert.equal(run('coverageStore.options'), null);
    await run('requestQueue');
});

test('height is bounded by options and antenna identifiers stay strings', async () => {
    const { run, elements } = await dashboard();
    run("coverageStore.options = { minY: -64, maxY: 319 }; networkData.nodes = [{ type: 'ANTENNA', id: '-9223372036854775808', x: 0, y: 64, z: 0 }]; updateCoverageAntennas()");
    assert.equal(elements.get('coverage-antenna').children[1].value, '-9223372036854775808');
    elements.get('coverage-height').value = 'y';
    for (const y of ['', '-65', '320', '1.5']) {
        elements.get('coverage-y').value = y;
        assert.equal(run('currentCoverageFilters()'), null);
    }
    elements.get('coverage-y').value = '-64';
    assert.equal(run('currentCoverageFilters().y'), '-64');
});

function seedCoverage(run, status = 'ready') {
    run(`coverageStore.select(currentCoverageFilters());
        coverageStore.cache.set(coverageStore.key({ tx: 0, tz: 0, level: 0 }), { expires: Date.now() + 3000,
            data: { status: '${status}', level: 0, progress: 0.5, step: currentCoverageFilters().step, revision: 'r1', cells: [
                { x: 64, y: 70, z: 64, state: 'signal', powerDbm: -75,
                  technology: '4G', band: 'BAND_700', antenna: '-9223372036854775808', service: 'unavailable' }
            ] } });`);
}

test('computed samples render after physical image, before equipment, and respect layer toggle', async () => {
    const { run, elements, draws } = await dashboard();
    elements.get('cov-computed').checked = true;
    seedCoverage(run);
    run(`tilesPaused = true;
        mapImageStore.snapshot = { originX: 0, originZ: 0, width: 512, height: 512, blocksPerPixel: 1, image: {} };
        networkData.nodes = [{ id: '1', type: 'ANTENNA', x: 80, z: 80 }]; draw()`);
    const terrain = draws.findIndex(d => d.method === 'drawImage');
    const signal = draws.findIndex(d => d.method === 'fillRect' && d.color === '#22c55e');
    const equipment = draws.findIndex(d => d.method === 'arc');
    assert.ok(terrain >= 0 && signal > terrain && equipment > signal);
    assert.equal(run('sampleRadius(32)'), 16);
    elements.get('cov-computed').checked = false;
    draws.length = 0;
    run('drawComputedCoverage()');
    assert.equal(draws.length, 0);
});

test('pending partial samples and expired samples render no radio signal', async () => {
    const { run, elements, draws } = await dashboard();
    elements.get('cov-computed').checked = true;
    seedCoverage(run, 'pending');
    run('drawComputedCoverage()');
    assert.equal(draws.some(d => d.method === 'fillRect'), false);
    assert.ok(draws.some(d => d.method === 'strokeRect'));
    seedCoverage(run);
    draws.length = 0;
    run("coverageStore.entry({ tx: 0, tz: 0 }).expires = 0; drawComputedCoverage()");
    assert.equal(draws.some(d => d.method === 'fillRect'), false);
});

test('sample hover reports radio source and service separately without distant interpolation', async () => {
    const { run, elements } = await dashboard();
    elements.get('cov-computed').checked = true;
    seedCoverage(run);
    run('mapPointer = { x: 364, y: 64 }; showCoverageTooltip()');
    const tooltip = elements.get('tooltip');
    assert.equal(tooltip.style.display, 'block');
    assert.match(tooltip.textContent, /Y 70/);
    assert.match(tooltip.textContent, /-75 dBm/);
    assert.match(tooltip.textContent, /BAND_700/);
    assert.match(tooltip.textContent, /antenne -9223372036854775808/);
    assert.match(tooltip.textContent, /Service : indisponible/);
    run('mapPointer = { x: 390, y: 90 }; showCoverageTooltip()');
    assert.equal(tooltip.style.display, 'none');
    run("coverageStore.entry({ tx: 0, tz: 0 }).expires = 0; showCoverageTooltip()");
    assert.match(tooltip.textContent, /Aucune donnée radio valide/);
    assert.doesNotMatch(tooltip.textContent, /-75/);
});

test('hidden pages stop drawing and starting polling work', async () => {
    const { run, elements, draws, requests, documentListeners } = await dashboard();
    elements.get('cov-computed').checked = true;
    elements.get('cov-nperf').checked = true;
    run('document.hidden = true');
    documentListeners.visibilitychange();
    run('draw(); updateTerrain(); updateComputedCoverage(); fetchNetworkData(); fetchNperfData()');
    await run('requestQueue');
    assert.equal(draws.length, 0);
    assert.equal(requests.length, 0);
});

for (const status of [429, 503, 504]) {
    test(`queued backlog observes ${status} Retry-After before the next fetch`, async () => {
        let throttle = false;
        const { run, advance, requests } = await dashboard({ manualTimers: true, fetcher: async () => {
            if (throttle) {
                throttle = false;
                return { status, headers: new Headers({ 'Retry-After': '2' }) };
            }
            return { status: 200, json: async () => ({ nodes: [], edges: [] }) };
        } });
        throttle = true;
        const first = run("apiFetch('/api/one')");
        const queued = run("apiFetch('/api/two')");
        await first;
        await flush();
        assert.equal(requests.length, 1);
        advance(1999);
        await flush();
        assert.equal(requests.length, 1);
        advance(1);
        await queued;
        assert.equal(requests.length, 2);
        assert.equal(requests[1].at - requests[0].at, 2000);
    });
}

test('network, player and nperf accept only HTTP 200 data, and pending reads recover', async () => {
    let status = 202, parsed = 0;
    const { run, advance, logs, elements } = await dashboard({ fetcher: async path => ({ status,
        headers: new Headers({ 'Retry-After': '1' }), json: async () => {
            parsed++;
            assert.equal(status, 200);
            return path === '/api/network' ? { nodes: [], edges: [] }
                : path === '/api/player' ? { x: 10, z: 20 } : [];
        } }) });
    elements.get('cov-nperf').checked = true;
    for (const code of [202, 204]) {
        status = code;
        advance(30000);
        await run('fetchNetworkData()');
        await run('fetchPlayerData()');
        await run('fetchNperfData()');
    }
    assert.equal(parsed, 0);
    assert.equal(logs.length, 0);
    status = 200;
    advance(30000);
    await run('fetchNetworkData()');
    await run('fetchPlayerData()');
    await run('fetchNperfData()');
    assert.equal(parsed, 3);
    assert.equal(run('initialCenterDone'), true);
});

test('queued coverage cancels logically on world/filter changes before fetch', async () => {
    const { run, requests, advance, context } = await dashboard({ manualTimers: true });
    await run("apiFetch('/api/budget')");
    context.current = true;
    const stale = run("apiFetch('/api/coverage?tx=0', { isCurrent: () => current })");
    const rejected = assert.rejects(stale, error => error.deferred);
    await flush();
    context.current = false;
    advance(200);
    await rejected;
    assert.equal(requests.length, 1);
});

test('long Retry-After suspends backlog and image retry until session reset', async () => {
    let limited = false;
    const { run, requests, advance, elements } = await dashboard({ fetcher: async () => limited
        ? { status: 503, headers: new Headers({ 'Retry-After': '31' }) }
        : { status: 200, json: async () => ({ nodes: [], edges: [] }) } });
    limited = true;
    run(`mapImageStore.select('world', ${JSON.stringify(metadata())}); updateTerrain()`);
    const backlog = run("apiFetch('/api/queued')");
    const rejected = assert.rejects(backlog, error => error.deferred && error.retryAt === Infinity);
    await run('requestQueue');
    await rejected;
    assert.equal(requests.length, 1);
    assert.equal(run('mapImageStore.snapshot'), null);
    advance(60000);
    run('updateTerrain()');
    await run('requestQueue');
    assert.equal(requests.length, 1);
    assert.match(elements.get('auth-status').textContent, /30 s.*suspendues/);
    limited = false;
    run("setSessionToken('')");
    await run('requestQueue');
    assert.equal(requests.length, 2);
});

test('pending routes do not block unrelated queued reads, but duplicate polls wait at least a second', async () => {
    const { run, requests } = await dashboard({ fetcher: async path => path === '/api/pending'
        ? { status: 202, headers: new Headers({ 'Retry-After': '1' }) }
        : { status: 200, json: async () => ({ nodes: [], edges: [] }) } });
    const first = run("apiFetch('/api/pending')");
    const duplicate = run("apiFetch('/api/pending')");
    const rejected = assert.rejects(duplicate, error => error.deferred);
    const unrelated = run("apiFetch('/api/unrelated')");
    await Promise.all([first, rejected, unrelated]);
    assert.deepEqual(requests.map(r => r.path), ['/api/pending', '/api/unrelated']);
    assert.equal(requests[1].at - requests[0].at, 200);
});

test('node index and bitmap are reused across unchanged polls and RAFs', async () => {
    const { run } = await dashboard();
    const nodes = run('nodeMap');
    const snapshot = run('mapImageStore.snapshot');
    await run('fetchNetworkData()');
    assert.equal(run('nodeMap'), nodes);
    for (let i = 0; i < 10; i++) run('draw()');
    assert.equal(run('nodeMap'), nodes);
    assert.equal(run('mapImageStore.snapshot'), snapshot);
});

test('radio 4K LOD displays the effective manual step and renders/hover-tests scaled negative tiles', async () => {
    const { run, elements, draws, requests } = await dashboard();
    elements.get('cov-computed').checked = true;
    elements.get('coverage-step').value = '16';
    run(`canvas.width = 3840; canvas.height = 2160; zoom = 0.25; pan = { x: 100, y: 100 };
        tilesPaused = true; updateComputedCoverage(); drawComputedCoverage()`);
    const level = run('currentCoverageFilters().level');
    const step = run('currentCoverageFilters().step');
    const size = (128 << level) * 0.25;
    assert.ok(level > 0);
    assert.ok(step >= 128);
    assert.match(elements.get('coverage-status').textContent, new RegExp(`Pas effectif : ${step} blocs, adapté au zoom`));
    assert.ok(run('coverageView().length') <= 49);
    const placeholder = draws.find(d => d.method === 'strokeRect');
    assert.equal(placeholder.args[2], size - 2);
    run(`const radioTile = coverageView().find(t => t.tx === -1 && t.tz === -1);
        coverageStore.cache.set(coverageStore.key(radioTile), { expires: Date.now() + 10000,
            data: { level: radioTile.level, status: 'ready', step: currentCoverageFilters().step,
                revision: 'radio-lod', cells: [{ x: -128, y: 70, z: -128, state: 'signal',
                    powerDbm: -75, technology: '4G', band: 'BAND_700', antenna: '1', service: 'available' }] } });
        mapPointer = { x: 368, y: 68 }; showCoverageTooltip()`);
    assert.match(elements.get('tooltip').textContent, /radio-lod/);
    assert.match(elements.get('tooltip').textContent, new RegExp(`Zone estimée de ${step}`));
    const visible = run('coverageView()');
    for (let i = 0; i < 10; i++) run('drawComputedCoverage()');
    assert.equal(run('coverageView()'), visible);
    assert.equal(requests.length, 0, 'radio render and hover never schedule HTTP');
});

test('technology controls reuse ready HTTP tiles, render independent signals and reset incompatible bands', async () => {
    const radioOptions = { tileSize: 128, minLevel: -3, maxLevel: 6, maxSamplesPerSide: 16,
        steps: [1, 8, 16, 32, 64, 128, 256, 512, 1024, 2048], sharedTechnologies: true,
        technologies: ['2G', '3G', '4G', '5G'], minY: -64, maxY: 319, maxRange: 4096, modelRevision: '0',
        bands: [{ id: 'LTE', technology: '4G', label: '700 MHz' },
            { id: 'NR', technology: '5G', label: '3500 MHz' }, { id: 'GSM', technology: '2G', label: '900 MHz' }] };
    const none = { state: 'none', powerDbm: null, technology: null, band: null, antenna: null, service: 'unavailable' };
    const radio = (technology, band, powerDbm) => ({ state: 'signal', powerDbm, technology, band,
        antenna: '-9223372036854775808', service: 'available' });
    let imageReads = 0;
    const { run, elements, requests, draws } = await dashboard({ fetcher: async path => {
        if (path === '/api/network') return { status: 200, json: async () => ({ nodes: [], edges: [],
            mapId: 'world', mapImage: metadata() }) };
        if (path.startsWith('/api/map-image')) { imageReads++; return pngResponse(); }
        if (path.endsWith('/options')) return { status: 200, json: async () => radioOptions };
        const q = new URL(path, 'http://localhost').searchParams;
        assert.equal(q.get('technology'), 'all');
        const band = q.get('band');
        const technologies = { '2G': band === 'all' ? radio('2G', 'GSM', -70) : none, '3G': none,
            '4G': radio('4G', 'LTE', -95), '5G': band === 'all' ? radio('5G', 'NR', -110) : none };
        return { status: 200, json: async () => ({ status: 'ready', revision: 'radio', level: -3,
            tileX: 0, tileZ: 0, originX: 0, originZ: 0, tileSize: 16, step: 16, height: 'surface',
            generatedAt: 0, validForMs: 30000, maxRange: 4096, progress: 1,
            cells: [{ x: 8, y: 70, z: 8, ...technologies[band === 'all' ? '5G' : '4G'], technologies }] }) };
    } });
    await run('requestQueue');
    elements.get('cov-computed').checked = true;
    run('canvas.width = 16; canvas.height = 16; zoom = 1; pan = { x: 0, y: 0 }; updateComputedCoverage()');
    await run('requestQueue');
    run('updateComputedCoverage()');
    await run('requestQueue');
    run('updateComputedCoverage()');
    await run('requestQueue');
    const physical = run('mapImageStore.snapshot'), cacheEntry = run('coverageStore.entry(coverageView()[0])');
    assert.ok(physical);
    assert.equal(imageReads, 1);
    assert.ok(run('coverageStore.ready(coverageView()[0])'));
    const count = requests.length, generation = run('coverageStore.generation');
    for (const [technology, color, power] of [['4G', '#eab308', -95], ['5G', '#f97316', -110],
        ['2G', '#22c55e', -70], ['4G', '#eab308', -95], ['all', '#f97316', -110]]) {
        elements.get('coverage-technology').value = technology;
        elements.get('coverage-technology').listeners.change();
        await run('requestQueue');
        draws.length = 0;
        run('drawComputedCoverage(); mapPointer = { x: 308, y: 8 }; showCoverageTooltip()');
        assert.ok(draws.some(d => d.method === 'fillRect' && d.color === color));
        assert.match(elements.get('tooltip').textContent, new RegExp(`${power} dBm`));
        assert.match(elements.get('tooltip').textContent, new RegExp(`Sélection : ${technology === 'all' ? 'Toutes \\(dominante\\)' : technology}`));
        assert.match(elements.get('coverage-status').textContent, /Pas effectif : 16 blocs/);
        const projected = run('coverageStore.ready(coverageView()[0])');
        for (let i = 0; i < 20; i++) run('drawComputedCoverage()');
        assert.equal(run('coverageStore.ready(coverageView()[0])'), projected);
        assert.equal(run('coverageStore.entry(coverageView()[0])'), cacheEntry);
        assert.equal(run('mapImageStore.snapshot'), physical);
        assert.equal(run('coverageStore.generation'), generation);
        assert.equal(requests.length, count);
    }
    elements.get('coverage-band').value = 'LTE';
    elements.get('coverage-band').listeners.change();
    await run('requestQueue');
    assert.equal(requests.length, count + 1, 'a precise band has its own response cache');
    assert.equal(run('coverageStore.cache.size'), 2);
    elements.get('coverage-technology').value = '5G';
    elements.get('coverage-technology').listeners.change();
    await run('requestQueue');
    assert.equal(elements.get('coverage-band').value, 'all');
    assert.deepEqual(elements.get('coverage-band').children.map(b => b.value), ['all', 'NR']);
    assert.equal(run('coverageStore.entry(coverageView()[0])'), cacheEntry);
    assert.equal(run('coverageStore.ready(coverageView()[0]).cells[0].powerDbm'), -110);
    assert.equal(requests.length, count + 1, 'returning to all bands reuses the ready shared entry');
    assert.equal(imageReads, 1);
});

test('4K zoom controls reach real 1-block samples and never label an adapted 8-block grid as precise', async () => {
    const { run, elements, requests, draws } = await dashboard();
    elements.get('cov-computed').checked = true;
    elements.get('coverage-step').value = '1';
    run(`tilesPaused = true; canvas.width = 3840; canvas.height = 2160;
        zoom = 1; pan = { x: 1920, y: 1080 }; zoomAt(100, 1920, 1080)`);
    assert.equal(run('zoom'), 32);
    assert.equal(run('currentCoverageFilters().level'), -3);
    assert.equal(run('currentCoverageFilters().step'), 1);
    assert.equal(run('coverageView().length'), 48);
    assert.match(elements.get('coverage-status').textContent, /Pas effectif : 1 bloc, précision demandée atteinte/);
    run(`const preciseTile = coverageView().find(t => t.tx === -1 && t.tz === -1);
        coverageStore.cache.set(coverageStore.key(preciseTile), { expires: Date.now() + 30000,
            data: { status: 'ready', level: -3, step: 1, revision: 'precise', cells: [
                { x: -1, y: 64, z: -1, state: 'signal', powerDbm: -70, technology: '4G',
                    band: 'LTE', antenna: '1', service: 'available' }] } });
        drawComputedCoverage(); mapPointer = { x: 2188, y: 1048 }; showCoverageTooltip()`);
    const sample = draws.find(d => d.method === 'fillRect');
    assert.equal(sample.args[2], 32);
    assert.equal(sample.args[3], 32);
    assert.equal(sample.args[0], run('-zoom + pan.x'), 'one-block cells start at their block boundary, not half a block earlier');
    assert.equal(sample.args[1], run('-zoom + pan.y'));
    assert.match(elements.get('tooltip').textContent, /Zone estimée de 1 × 1 blocs/);
    assert.match(elements.get('tooltip').textContent, /X -1, Y 64, Z -1/);
    run('pan = { x: 100, y: 100 }; updateComputedCoverage()');
    assert.equal(run('currentCoverageFilters().step'), 8);
    assert.match(elements.get('coverage-status').textContent, /Pas effectif : 8 blocs, adapté au zoom/);
    assert.doesNotMatch(elements.get('coverage-status').textContent, /précision demandée atteinte/);
    assert.equal(run('coverageStore.ready(coverageView()[0])'), null);
    run('zoomAt(0.0001, 1920, 1080)');
    assert.equal(run('zoom'), 0.25);
    assert.ok(run('coverageView().length') <= 49);
    assert.ok(run('currentCoverageFilters().step') > 16);
    assert.equal(requests.length, 0);
});

test('coverage selector labels expose dominant mode and the three requested spatial steps', async () => {
    const html = await readFile(new URL('../index.html', import.meta.url), 'utf8');
    assert.match(html, /<option value="all">Toutes \(dominante\)<\/option><option>2G<\/option><option>3G<\/option><option>4G<\/option><option>5G<\/option>/);
    assert.match(html, /<option value="auto">Auto \(recommandé\)<\/option>/);
    assert.match(html, /<option value="1">1 bloc \(précis à zoom fort\)<\/option>/);
    assert.match(html, /<option value="8">8 blocs \(demi-chunk\)<\/option>/);
    assert.match(html, /<option value="16">16 blocs \(chunk\)<\/option>/);
    assert.match(html, /au plus 256 cellules par tuile/);
});

function offscreenStub() {
    const surfaces = [];
    class OffscreenCanvas {
        constructor(width, height) {
            this.width = width;
            this.height = height;
            this.calls = {};
            this.context = new Proxy({ isContextLost: () => false }, { get: (target, key) => key in target ? target[key]
                : () => { this.calls[key] = (this.calls[key] || 0) + 1; } });
            surfaces.push(this);
        }
        getContext() { return this.context; }
    }
    return { OffscreenCanvas, surfaces };
}

test('five static frames paint 49x256 radio cells once and only blit thereafter, below animated infrastructure', async () => {
    const { OffscreenCanvas, surfaces } = offscreenStub();
    const { run, elements, draws, requests } = await dashboard({ offscreen: OffscreenCanvas });
    elements.get('cov-computed').checked = true;
    elements.get('coverage-step').value = '1';
    run(`tilesPaused = true; canvas.width = 1792; canvas.height = 1792; zoom = 16; pan = { x: 0, y: 0 };
        coverageStore.select(currentCoverageFilters());
        for (const tile of coverageView()) {
            coverageStore.cache.set(coverageStore.key(tile), { expires: Date.now() + 30000,
                data: { status: 'ready', level: -3, step: 1, revision: 'dense', cells: Array.from({ length: 256 }, (_, i) => ({
                    x: tile.tx * 16 + i % 16, y: 64, z: tile.tz * 16 + Math.floor(i / 16),
                    state: 'signal', powerDbm: -70, technology: '4G', band: 'LTE', antenna: '1', service: 'available'
                })) } });
        }
        networkData.nodes = [{ id: '1', type: 'ANTENNA', x: 20, z: 20, usageDown: 1, usageUp: 0, capacity: 100 }];
        for (let frame = 0; frame < 5; frame++) draw();`);
    assert.equal(run('coverageView().length'), 49);
    assert.equal(surfaces.length, 1);
    assert.equal(surfaces[0].calls.clearRect, 1);
    assert.equal(surfaces[0].calls.fillRect, 49 * 256);
    assert.equal(draws.filter(d => d.method === 'fillRect').length, 0);
    assert.equal(draws.filter(d => d.method === 'drawImage').length, 5);
    assert.equal(draws.filter(d => d.method === 'arc').length, 10);
    assert.ok(new Set(draws.filter(d => d.method === 'arc').map(d => d.args[2])).size > 2, 'traffic pulses keep animating');
    for (let i = 0; i < draws.length; i++) {
        if (draws[i].method === 'drawImage') {
            assert.equal(draws[i].args[0], surfaces[0]);
            assert.ok(draws.slice(i + 1).findIndex(d => d.method === 'arc') >= 0);
        }
    }
    assert.equal(requests.length, 0, 'rendering never starts HTTP work');
});

test('radio surface invalidates for geometry, filters, ready content, expiry, model and toggle; resize releases storage', async () => {
    const { OffscreenCanvas, surfaces } = offscreenStub();
    const { run, elements, requests } = await dashboard({ offscreen: OffscreenCanvas });
    elements.get('cov-computed').checked = true;
    run("tilesPaused = true; coverageStore.options = { minY: -64, maxY: 319, modelRevision: '0' }");
    seedCoverage(run);
    run('drawComputedCoverage()');
    const original = surfaces[0];
    assert.equal(original.calls.fillRect, 1);
    const mutations = [
        'pan.x += 1', 'pan.y += 1', 'zoom *= 1.01',
        "coverageHeight.value = 'y'; coverageY.value = '64'",
        "coverageBand.value = 'LTE'", "coverageAntenna.value = '1'", "coverageTechnology.value = '4G'",
        "coverageHeight.value = 'surface'; coverageBand.value = coverageAntenna.value = coverageTechnology.value = 'all'",
        "coverageStore.entry({ tx: 0, tz: 0 }).data = { ...coverageStore.entry({ tx: 0, tz: 0 }).data, revision: 'changed' }",
        "coverageStore.entry({ tx: 0, tz: 0 }).data = { ...coverageStore.entry({ tx: 0, tz: 0 }).data, cells: [] }",
        'coverageStore.entry({ tx: 0, tz: 0 }).expires = 0',
        'coverageStore.entry({ tx: 0, tz: 0 }).expires = Date.now() + 30000',
        "coverageStore.options = { ...coverageStore.options, modelRevision: '1' }",
        'coverageStore.invalidate()',
    ];
    for (const mutation of mutations) {
        const paints = original.calls.clearRect;
        run(`${mutation}; drawComputedCoverage(); drawComputedCoverage()`);
        assert.equal(original.calls.clearRect, paints + 1, mutation);
    }
    elements.get('cov-computed').checked = false;
    elements.get('cov-computed').listeners.change();
    assert.equal(original.width * original.height, 0, 'toggle releases the surface even before the next frame');
    run('drawComputedCoverage()');
    assert.equal(run('coverageLayer'), null);
    elements.get('cov-computed').checked = true;
    elements.get('cov-computed').listeners.change();
    run('drawComputedCoverage()');
    assert.equal(surfaces.length, 2);
    run('canvas.width = 3840; canvas.height = 2160; drawComputedCoverage(); drawComputedCoverage()');
    assert.equal(surfaces[1].width * surfaces[1].height, 0);
    assert.equal(surfaces.length, 3);
    assert.equal(surfaces[2].calls.clearRect, 1);
    assert.equal(surfaces[2].width * surfaces[2].height * 4, 33177600);
    assert.ok(surfaces.reduce((bytes, surface) => bytes + surface.width * surface.height * 4, 0) <= 32 * 1024 * 1024);
    run('canvas.width = 7680; canvas.height = 4320; drawComputedCoverage()');
    assert.equal(surfaces.length, 3, 'oversized viewports fall back without allocating an oversized surface');
    assert.equal(run('coverageLayer'), null);
    assert.equal(surfaces[2].width * surfaces[2].height, 0);
    run('canvas.width = 640; canvas.height = 480; drawComputedCoverage()');
    assert.equal(surfaces.length, 4);
    assert.equal(surfaces[3].width, 640);
    assert.equal(surfaces[3].height, 480);
    assert.equal(requests.length, 0);
});

test('missing, failing, lost or unblittable offscreen contexts fall back to direct radio rendering safely', async () => {
    for (const failure of ['missing', 'constructor', 'context', 'lost', 'blit']) {
        const { OffscreenCanvas, surfaces } = offscreenStub();
        let attempts = 0;
        class FailingCanvas extends OffscreenCanvas {
            constructor(width, height) {
                attempts++;
                if (failure === 'constructor') throw new Error('allocation failed');
                super(width, height);
                if (failure === 'lost') this.context.isContextLost = () => true;
            }
            getContext() { return failure === 'context' ? null : super.getContext(); }
        }
        const { run, elements, draws, requests } = await dashboard({ offscreen: failure === 'missing' ? undefined : FailingCanvas });
        elements.get('cov-computed').checked = true;
        seedCoverage(run);
        if (failure === 'blit') run("ctx.drawImage = () => { throw new Error('blit failed'); }");
        run('for (let frame = 0; frame < 5; frame++) drawComputedCoverage()');
        assert.equal(draws.filter(d => d.method === 'fillRect').length, 5, failure);
        assert.equal(attempts, failure === 'missing' ? 0 : 1, 'failed allocation is not repeated on every RAF');
        assert.ok(surfaces.every(surface => surface.width * surface.height === 0));
        assert.equal(run('coverageLayer'), null);
        assert.equal(requests.length, 0);
    }
});

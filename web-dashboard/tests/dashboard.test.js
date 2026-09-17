import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { test } from 'node:test';
import vm from 'node:vm';
import * as coverage from '../coverage.js';
import { MapImageStore } from '../map-image.js';
import * as zone from '../zone.js';
import { SpeedtestStore, speedtestPlot } from '../speedtest.js';
import { metadata, pngResponse } from './map-image-fixture.js';

const source = (await readFile(new URL('../main.js', import.meta.url), 'utf8')).replace(/^import .*;$/gm, '');
async function flush() { for (let i = 0; i < 32; i++) await Promise.resolve(); }

test('confirmed zone without credentials sends POST with the displayed world identity and can be cancelled', async () => {
    const { run, requests } = await dashboard({ fetcher: async (url, options) => {
        return { status: 200, ok: true, headers: { get: () => null }, json: async () => url.endsWith('/cancel')
            ? { status: 'cancelled' } : url === '/api/network' ? { nodes: [], edges: [], mapId: 'world' }
                : { id: '00000000-0000-0000-0000-000000000001', status: 'queued' } };
    } });
    run('terrainMapId="world";setSelectedZone({minX:0,minZ:0,maxX:15,maxZ:15});');
    await run('startZoneJob("terrain")');
    assert.equal(requests.length, 0);
    run('zoneConfirm.checked=true');
    await run('startZoneJob("terrain")');
    assert.equal(requests.length, 1);
    assert.equal(requests[0].path, '/api/zone-jobs');
    assert.equal(requests[0].options.method, 'POST');
    assert.equal(requests[0].options.headers.has('Authorization'), false);
    const body = JSON.parse(requests[0].options.body);
    assert.equal(body.mapId, 'world');
    assert.equal(body.allowGeneration, true);
    await run('startZoneJob("terrain")');
    assert.equal(requests.length, 1, 'an active job blocks duplicate starts');
    await run('cancelZoneJob()');
    assert.equal(requests.length, 2);
    assert.ok(requests[1].path.endsWith('/api/zone-jobs/cancel'));
    assert.equal(run('zoneJob.state'), 'cancelled');
});

test('selected coverage keeps its exact grid even when zoomed out', async () => {
    const { run } = await dashboard();
    run('applyZoneJob({id:"job",kind:"coverage",state:"running",total:1,completed:0,progress:0,message:"",bounds:{minX:-16,minZ:0,maxX:-1,maxZ:15},step:1,height:"surface",antenna:"all",technology:"4G",band:"all",coverageTiles:[{x:-1,z:0,level:-3,step:1}]});zoom=0.25;');
    assert.equal(run('currentCoverageFilters().step'), 1);
    assert.equal(run('currentCoverageFilters().level'), -3);
    assert.equal(run('forcedCoverageView.tiles.length'), 1);
});

function largeZoneJob(step = 1, elongated = false) {
    const side = step * 16;
    return { id: 'large-zone', kind: 'coverage', state: 'running', total: 1024, completed: 0, progress: 0, message: '',
        bounds: { minX: 0, minZ: 0, maxX: (elongated ? 1024 : 32) * side - 1, maxZ: (elongated ? 1 : 32) * side - 1 },
        step, height: 'surface', antenna: 'all', technology: 'all', band: 'all',
        coverageTiles: Array.from({ length: 1024 }, (_, i) => ({ x: elongated ? i : i % 32,
            z: elongated ? 0 : Math.floor(i / 32), step, level: step === 1 ? -3 : step === 8 ? 0 : 1 })) };
}

async function zoneRadioDashboard() {
    const result = await dashboard({ fetcher: async path => {
        if (path === '/api/network') return { status: 200, json: async () => ({ nodes: [], edges: [], mapId: 'world' }) };
        const query = new URL(path, 'http://localhost').searchParams;
        const step = Number(query.get('step')), tx = Number(query.get('tx')), tz = Number(query.get('tz'));
        return { status: 200, json: async () => ({ status: 'ready', revision: 'radio', level: Number(query.get('level')),
            tileX: tx, tileZ: tz, originX: tx * step * 16, originZ: tz * step * 16, tileSize: step * 16,
            step, height: 'surface', generatedAt: 0, validForMs: 30000, maxRange: 4096, progress: 1, cells: [] }) };
    } });
    result.run(`coverageStore.options = { minY: -64, maxY: 319, bands: [], modelRevision: 'radio' };
        coverageStore.nextOptionsAt = Infinity; appliedCoverageOptions = coverageStore.options;`);
    return result;
}

test('large zone status and static frames reuse parsed descriptors and the viewport while updating progress', async () => {
    const { context, run, requests, elements } = await dashboard();
    let parses = 0, selections = 0;
    context.exactCoverageTiles = job => { parses++; return zone.exactCoverageTiles(job); };
    context.exactCoverageViewport = (...args) => { selections++; return zone.exactCoverageViewport(...args); };
    context.job = largeZoneJob();
    run('canvas.width=512;canvas.height=512;pan={x:0,y:0};zoom=1;applyZoneJob(job)');
    const tiles = run('forcedCoverageView.tiles'), viewport = run('coverageView()');
    for (let i = 1; i <= 100; i++) {
        context.job = { ...largeZoneJob(), completed: i, progress: i / 1024, message: `progress-${i}` };
        run('applyZoneJob(job);drawComputedCoverage()');
    }
    assert.equal(parses, 1);
    assert.equal(selections, 1);
    assert.equal(run('forcedCoverageView.tiles'), tiles);
    assert.equal(run('coverageView()'), viewport);
    assert.equal(run('zoneJob.completed'), 100);
    assert.equal(run('forcedCoverageView.progress'), 100 / 1024);
    assert.match(elements.get('zone-status').textContent, /progress-100/);
    assert.equal(requests.length, 0, 'neither status application nor RAF starts tile requests');
    run('pan.x-=16;drawComputedCoverage()');
    assert.equal(selections, 2);
    assert.equal(parses, 1);
    context.job = largeZoneJob(8);
    run('applyZoneJob(job)');
    assert.equal(parses, 2, 'a changed exact step invalidates descriptor parsing');
    context.job = { ...largeZoneJob(8), id: 'next-job', coverageTiles: [] };
    run('applyZoneJob(job)');
    context.job = { ...largeZoneJob(8), id: 'next-job' };
    run('applyZoneJob(job)');
    assert.equal(run('forcedCoverageView.tiles.length'), 1024, 'a POST acknowledgement does not freeze an empty list');
    assert.equal(parses, 4);
    context.job = { ...largeZoneJob(8), id: 'next-job', bounds: { ...largeZoneJob(8).bounds, minX: 1 } };
    run('applyZoneJob(job)');
    assert.equal(parses, 5, 'changed bounds invalidate descriptor parsing');
});

test('a zoomed-out 1024-tile job loads only a stable centre window, never cycles against the LRU', async () => {
    for (const step of [1, 8, 16]) {
        const { context, run, requests, elements, draws } = await zoneRadioDashboard();
        context.job = largeZoneJob(step);
        run(`canvas.width=canvas.height=${step * 128};pan={x:0,y:0};zoom=0.25;applyZoneJob(job)`);
        const selected = new Set(run('coverageView()').map(tile => `${tile.tx},${tile.tz}`));
        assert.equal(selected.size, 64);
        assert.equal(run('coverageViewport.visibleKeys.size'), 1024);
        assert.equal(run('forcedCoverageView.tiles.length'), 1024);
        run('drawComputedCoverage()');
        assert.equal(draws.filter(draw => draw.method === 'strokeRect').length, 0, 'no 1024-tile placeholder grid');
        run(`coverageStore.select(currentCoverageFilters());
            for (let tx=-128;tx<0;tx++) {
                const tile={tx,tz:-1,level:job.coverageTiles[0].level,key:job.coverageTiles[0].level+'/'+tx+',-1'};
                coverageStore.cache.set(coverageStore.key(tile), {tile,expires:Date.now()+30000,nextAt:Infinity,
                    data:{status:'ready',level:tile.level,step:job.step,cells:[]}});
            }`);
        for (let i = 0; i < 200; i++) {
            run('updateComputedCoverage()');
            await run('requestQueue');
        }
        assert.equal(requests.length, 64);
        assert.equal(run('coverageStore.cache.size'), 128);
        for (const { path } of requests) {
            const q = new URL(path, 'http://localhost').searchParams;
            assert.ok(selected.has(`${q.get('tx')},${q.get('tz')}`));
            assert.equal(q.get('step'), String(step));
            assert.equal(q.get('level'), String(step === 1 ? -3 : step === 8 ? 0 : 1));
        }
        assert.match(run('zoneMessage'), /Zoomer pour détails/);
        assert.match(elements.get('zone-status').textContent, /Vue partielle/);
        assert.match(elements.get('coverage-status').textContent, /64\/64.*fenêtre locale.*1024 visibles, 1024 dans le job/);
        assert.equal(run('zoneJob.completed'), 0, 'local reads never advance the authoritative backend job');
        run('zoom=32;pan={x:0,y:0};updateComputedCoverage()');
        await run('requestQueue');
        assert.doesNotMatch(run('zoneMessage'), /Zoomer pour détails/);
        assert.doesNotMatch(elements.get('zone-status').textContent, /Vue partielle/);
    }
});

test('zone UI exposes new limits, rounded chunk estimates and long-running server work', async () => {
    const html = await readFile(new URL('../index.html', import.meta.url), 'utf8');
    assert.match(html, /1024 tuiles physiques maximum, soit 262144 points/);
    assert.match(html, /4096 chunks maximum, bornes arrondies aux chunks entiers/);
    assert.match(html, /prendre longtemps/);
    const { run, elements } = await dashboard();
    run('zoneStep.value="1";zoneConfirm.checked=true;terrainMapId="world";setSelectedZone({minX:0,minZ:0,maxX:511,maxZ:511})');
    assert.equal(elements.get('zone-coverage').disabled, false);
    assert.match(elements.get('zone-bounds').textContent, /1024 tuiles physiques, 262144 points/);
    run('setSelectedZone({minX:0,minZ:0,maxX:1023,maxZ:1023})');
    assert.equal(elements.get('zone-terrain').disabled, false);
    assert.equal(elements.get('zone-coverage').disabled, true);
    assert.match(elements.get('zone-bounds').textContent, /4096 chunks/);
    run('setSelectedZone({minX:-1,minZ:-1,maxX:0,maxZ:0})');
    assert.match(elements.get('zone-bounds').textContent, /4 chunks \(bornes arrondies/);
});

test('panning an elongated zone fetches no distant tiles, reaches its end and preserves the radio cache', async () => {
    for (const step of [1, 8, 16]) {
        const { context, run, requests } = await zoneRadioDashboard();
        context.job = largeZoneJob(step, true);
        run(`canvas.width=${step * 32};canvas.height=${step * 16};pan={x:0,y:0};zoom=1;applyZoneJob(job)`);
        for (let i = 0; i < 5; i++) { run('updateComputedCoverage()'); await run('requestQueue'); }
        assert.equal(requests.length, 3);
        assert.ok(requests.every(({ path }) => Number(new URL(path, 'http://localhost').searchParams.get('tx')) < 3));
        const cache = run('coverageStore.cache'), first = run('coverageStore.cache.values().next().value');
        const generation = run('coverageStore.generation');
        requests.length = 0;
        run(`pan.x=-${1022 * step * 16};drawComputedCoverage()`);
        for (let i = 0; i < 5; i++) { run('updateComputedCoverage()'); await run('requestQueue'); }
        assert.equal(requests.length, 3);
        assert.ok(requests.every(({ path }) => Number(new URL(path, 'http://localhost').searchParams.get('tx')) >= 1021));
        assert.ok(requests.some(({ path }) => new URL(path, 'http://localhost').searchParams.get('tx') === '1023'));
        assert.equal(run('coverageStore.cache'), cache);
        assert.equal(run('coverageStore.generation'), generation);
        assert.ok([...cache.values()].includes(first));
        assert.equal(cache.size, 6);
        assert.equal(run('forcedCoverageView.tiles.length'), 1024);
        requests.length = 0;
        run('pan.x=0;updateComputedCoverage()');
        await run('requestQueue');
        assert.equal(requests.length, 0, 'returning to cached results does not refetch');
    }
});

test('wide exact view draws cached corners outside its working window, but never expired or missing details', async () => {
    const { context, run, draws } = await zoneRadioDashboard();
    context.job = largeZoneJob();
    run(`canvas.width=canvas.height=512;pan={x:0,y:0};zoom=1;applyZoneJob(job);
        coverageStore.select(currentCoverageFilters());
        const corner=forcedCoverageView.tiles[0];
        coverageStore.cache.set(coverageStore.key(corner), {tile:corner, expires:Date.now()+30000,
            data:{status:'ready',level:-3,step:1,cells:[{x:0,z:0,state:'unknown'}]}});
        drawComputedCoverage()`);
    assert.equal(run('coverageView().some(tile => tile.tx===0 && tile.tz===0)'), false);
    assert.ok(draws.some(draw => draw.method === 'fillRect'));
    assert.equal(run('coverageDisplayView().length'), 1);
    draws.length = 0;
    run('coverageStore.cache.values().next().value.expires=0;drawComputedCoverage()');
    assert.equal(draws.filter(draw => ['fillRect', 'strokeRect'].includes(draw.method)).length, 0);
});

test('a pan before queued exact HTTP dispatch cancels the now-distant request', async () => {
    const { context, run, requests, advance } = await dashboard({ manualTimers: true });
    context.job = largeZoneJob(1, true);
    run(`canvas.width=32;canvas.height=16;pan={x:0,y:0};zoom=1;applyZoneJob(job);
        coverageStore.options={minY:-64,maxY:319,bands:[],modelRevision:'radio'};coverageStore.nextOptionsAt=Infinity;`);
    await run("apiFetch('/api/budget')");
    run('updateComputedCoverage()');
    await flush();
    run('pan.x=-16352;drawComputedCoverage()');
    advance(200);
    await run('requestQueue');
    assert.equal(requests.length, 1, 'only the earlier budget request was sent');
});

test('credential-free zones still require a world, valid bounds, generation confirmation and an explicit coverage step', async () => {
    const { run, elements, requests } = await dashboard({ fetcher: async path => ({ status: 200,
        json: async () => path === '/api/network' ? { nodes: [], edges: [] } : { id: 'coverage-job', status: 'queued' } }) });
    run('setSelectedZone({minX:-16,minZ:0,maxX:-1,maxZ:15});zoneConfirm.checked=true;zoneStep.value="1"');
    await run('startZoneJob("terrain")');
    await run('startZoneJob("coverage")');
    assert.equal(requests.length, 0, 'no world identity means no mutation');
    run('terrainMapId="opaque / world";setSelectedZone(null)');
    await run('startZoneJob("terrain")');
    await run('startZoneJob("coverage")');
    assert.equal(requests.length, 0);
    run('setSelectedZone({minX:-16,minZ:0,maxX:-1,maxZ:15});zoneConfirm.checked=false');
    await run('startZoneJob("terrain")');
    for (const step of ['', 'auto', '0', '32']) {
        elements.get('zone-step').value = step;
        await run('startZoneJob("coverage")');
    }
    assert.equal(requests.length, 0);
    run('zoneStep.value="1"');
    await run('startZoneJob("coverage")');
    assert.equal(requests.length, 1);
    assert.equal(requests[0].options.method, 'POST');
    assert.deepEqual(JSON.parse(requests[0].options.body), { kind: 'coverage', minX: -16, minZ: 0, maxX: -1, maxZ: 15,
        mapId: 'opaque / world', allowGeneration: false, step: 1, height: 'surface', antenna: 'all', technology: 'all', band: 'all' });
    assert.equal(elements.get('zone-cancel').disabled, false);
});

test('zone cancellation before dispatch sends nothing and releases the queued start', async () => {
    const { run, requests, advance } = await dashboard({ manualTimers: true });
    await run("apiFetch('/api/budget')");
    run('terrainMapId="world";zoneConfirm.checked=true;setSelectedZone({minX:0,minZ:0,maxX:15,maxZ:15})');
    const start = run('startZoneJob("terrain")');
    await flush();
    assert.equal(run('zonePending.dispatched'), false);
    await run('cancelZoneJob()');
    advance(200);
    await start;
    assert.equal(run('zonePending'), null);
    assert.equal(run('zoneReconcile'), false);
    assert.equal(requests.length, 1);
});

test('zone cancellation during start response or JSON decoding waits for the job identity and sends one cancel', async () => {
    for (const phase of ['response', 'json']) {
        let release;
        const { run, requests, elements } = await dashboard({ fetcher: async path => {
            if (path === '/api/network') return { status: 200, json: async () => ({ nodes: [], edges: [], mapId: 'world' }) };
            if (path.endsWith('/cancel')) return { status: 200, json: async () => ({ status: 'cancelled' }) };
            const result = { id: 'started-job', status: 'queued' };
            return phase === 'response' ? new Promise(resolve => { release = () => resolve({ status: 202, json: async () => result }); })
                : { status: 202, json: () => new Promise(resolve => { release = () => resolve(result); }) };
        } });
        run('zoneConfirm.checked=true;setSelectedZone({minX:0,minZ:0,maxX:15,maxZ:15})');
        const start = run('startZoneJob("terrain")');
        await flush();
        await run('cancelZoneJob()');
        await run('cancelZoneJob()');
        await run('startZoneJob("terrain")');
        assert.equal(elements.get('zone-cancel').disabled, true);
        assert.equal(requests.length, 1);
        release();
        await start;
        await run('requestQueue');
        assert.equal(requests.length, 2);
        assert.equal(requests[1].path, '/api/zone-jobs/cancel');
        assert.deepEqual(JSON.parse(requests[1].options.body), { id: 'started-job' });
        assert.ok(requests[1].at - requests[0].at >= 200);
        assert.equal(run('zoneJob.state'), 'cancelled');
    }
});

test('zone starts are stale after world changes, both queued before transport and decoding a response', async () => {
    for (const phase of ['queued', 'json']) {
        let mapId = 'old-world', release;
        const { run, requests } = await dashboard({ fetcher: async path => path === '/api/network'
            ? { status: 200, json: async () => ({ nodes: [], edges: [], mapId }) }
            : { status: 200, json: () => new Promise(resolve => { release = resolve; }) } });
        run('zoneConfirm.checked=true;setSelectedZone({minX:0,minZ:0,maxX:15,maxZ:15})');
        let network;
        if (phase === 'queued') { mapId = 'new-world'; network = run('fetchNetworkData()'); }
        const start = run('startZoneJob("terrain")');
        if (phase === 'json') {
            await run('requestQueue');
            mapId = 'new-world';
            await run('fetchNetworkData()');
            release({ id: 'stale-job', status: 'queued' });
        }
        await Promise.all([network, start]);
        assert.equal(requests.filter(r => r.options.method === 'POST').length, phase === 'queued' ? 0 : 1);
        assert.equal(run('zoneJob'), null);
        assert.equal(run('zonePending'), null);
        assert.equal(run('selectedZone'), null);
        assert.equal(run('zoneConfirm.checked'), false);
        assert.equal(run('terrainMapId'), 'new-world');
    }
});

test('zone conflicts and busy responses reconcile through reads, never automatically replaying a mutation', async () => {
    for (const status of [409, 429, 503, 504]) {
        const { run, requests, elements } = await dashboard({ fetcher: async (path, options) => options.method === 'POST'
            ? { status, headers: new Headers({ 'Retry-After': '2' }) }
            : { status: 200, json: async () => path === '/api/network' ? { nodes: [], edges: [], mapId: 'world' } : { job: null } } });
        run('zoneConfirm.checked=true;setSelectedZone({minX:0,minZ:0,maxX:15,maxZ:15})');
        await run('startZoneJob("terrain")');
        assert.equal(run('zoneReconcile'), true);
        assert.equal(elements.get('zone-terrain').disabled, true);
        await run('startZoneJob("terrain")');
        await run('pollZoneJobs()');
        assert.equal(run('zoneReconcile'), false);
        assert.equal(requests.filter(r => r.options.method === 'POST').length, 1);
        assert.equal(requests[1].path, '/api/zone-jobs');
        assert.ok(requests[1].at - requests[0].at >= (status === 409 ? 200 : 2000));
    }
});

test('a restarted server with no job eventually clears an old acknowledged job', async () => {
    const { run } = await dashboard({ fetcher: async path => ({ status: 200, ok: true, json: async () =>
        path === '/api/zone-jobs' ? { job: null } : { nodes: [], edges: [] } }) });
    run('zoneJob={id:"old",kind:"terrain",state:"running",completed:0,total:1,progress:0,message:""};zoneAwaitingId="old";zoneAcknowledgedAt=Date.now()-6000;zoneNextAt=0;');
    await run('pollZoneJobs()');
    assert.equal(run('zoneJob'), null);
    assert.equal(run('zoneAwaitingId'), null);
});

async function dashboard({ fetcher, bitmap, offscreen, manualTimers = false, reduceMotion = false } = {}) {
    let now = Date.now();
    const requests = [], draws = [], timers = [], logs = [];
    const elements = new Map(), documentListeners = {}, windowListeners = {};
    const canvasContext = new Proxy({}, { get(target, key) {
        return key in target ? target[key] : (...args) => draws.push({ method: key, args, color: target.fillStyle, stroke: target.strokeStyle });
    } });
    const context = vm.createContext({
        ...coverage, ...zone, MapImageStore, SpeedtestStore, speedtestPlot, OffscreenCanvas: offscreen,
        document: {
            hidden: false, body: { style: {} },
            addEventListener(name, fn) { documentListeners[name] = fn; },
            getElementById(id) {
                if (!elements.has(id)) {
                    elements.set(id, {
                    style: {}, value: '', textContent: '', clientWidth: 800, clientHeight: 600,
                    offsetWidth: 300, offsetHeight: 180,
                    listeners: {}, checked: id === 'show-infra',
                    addEventListener(name, fn) { this.listeners[name] = fn; }, getContext() { return canvasContext; }, setPointerCapture() {},
                    getBoundingClientRect() { return { left: 300, top: 0 }; },
                    replaceChildren(...children) { this.children = children; this.value = children[0]?.value || ''; },
                    });
                    if (id === 'details-content') Object.defineProperty(elements.get(id), 'innerHTML', {
                        get() { return this.html; },
                        set(value) {
                            this.html = value;
                            elements.delete('btn-speedtest');
                            elements.delete('speedtest-duration');
                            elements.delete('speedtest-server');
                            elements.delete('speedtest-refresh');
                            for (const id of ['value', 'arc', 'progress', 'progress-text', 'phase-progress', 'wait', 'activity-dot']) {
                                elements.delete(`speedtest-${id}`);
                            }
                        },
                    });
                }
                return elements.get(id);
            },
        },
        window: { innerWidth: 1100, innerHeight: 600, matchMedia: () => ({ matches: reduceMotion }),
            addEventListener(name, fn) { windowListeners[name] = fn; } },
        console: { warn: (...args) => logs.push(args), error: (...args) => logs.push(args) }, Headers, AbortSignal, AbortController, URLSearchParams,
        Date: class extends Date { static now() { return now; } },
        Option: class { constructor(text, value) { this.text = text; this.value = value; } },
        setTimeout(callback, delay) {
            if (delay > 200) return;
            if (manualTimers) timers.push({ callback, at: now + delay });
            else queueMicrotask(() => { now += delay; callback(); });
        },
        clearTimeout() {}, setInterval() {}, requestAnimationFrame() {}, cancelAnimationFrame() {},
        createImageBitmap: async blob => ({ width: 512, height: 512, ...(await (bitmap?.(blob) ?? { close() {} })) }),
        fetch: async (path, options) => {
            assert.notEqual(path, '/api/session');
            assert.equal(options.headers.has('Authorization'), false);
            assert.equal(options.credentials, 'omit');
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
    return { context, requests, elements, draws, logs, documentListeners, windowListeners, advance: ms => {
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

test('dashboard has no credentials, probe or authentication controls and warns about open actions', async () => {
    const html = await readFile(new URL('../index.html', import.meta.url), 'utf8');
    const css = await readFile(new URL('../style.css', import.meta.url), 'utf8');
    const { run, requests, elements } = await dashboard();
    elements.get('resume-reads').listeners.click();
    await run('requestQueue');
    assert.ok(requests.length > 0);
    for (const { path, options } of requests) {
        assert.equal(options.headers.has('Authorization'), false);
        assert.notEqual(path, '/api/session');
        assert.equal(options.credentials, 'omit');
        assert.equal(options.cache, 'no-store');
        assert.equal(options.redirect, 'error');
    }
    assert.doesNotMatch(source, /sessionToken|sessionVerified|canMutate|readAllowed|sessionProbe|setSessionToken|verifySessionToken|authStatus|Authorization|\/api\/session/);
    assert.doesNotMatch(source, /localStorage|sessionStorage|document\.cookie|location\.(?:reload|assign|replace)/);
    assert.doesNotMatch(html, /session-auth|session-token|clear-token|auth-status|TELECOM_HTTP_TOKEN|type="password"|<form/);
    assert.doesNotMatch(css, /session-auth|auth-status/);
    assert.equal(elements.has('session-token'), false);
    assert.match(html, /Sans authentification : toute personne accédant à cette page peut lancer les actions/);
    const ids = [...html.matchAll(/\bid="([^"]+)"/g)].map(match => match[1]);
    assert.equal(new Set(ids).size, ids.length);
    assert.equal(run('resetDashboardSession.length'), 0);
});

test('voluntary read reset preserves the entire map context and cached image without refetching it', async () => {
    const nodes = [...routers(), { id: '-123', type: 'ANTENNA', x: 0, y: 64, z: 0 }];
    let closed = 0;
    const { run, requests, elements } = await dashboard({ fetcher: async path => path === '/api/network'
            ? { status: 200, json: async () => ({ nodes, edges: [], mapId: 'world', mapImage: metadata() }) }
            : pngResponse(), bitmap: async () => ({ close() { closed++; } }) });
    run('updateTerrain()');
    await run('requestQueue');
    assert.ok(run('mapImageStore.snapshot'));
    const closedBefore = closed;
    selectRouter(run, 0);
    elements.get('speedtest-duration').value = '6000';
    elements.get('speedtest-duration').listeners.change();
    run(`pan = {x:123,y:-456}; zoom=3; zoneStep.value='8'; zoneConfirm.checked=zoneSelect.checked=true;
        setSelectedZone({minX:-16,minZ:0,maxX:15,maxZ:15});
        coverageStore.options={minY:-64,maxY:319,bands:[{id:'LTE',technology:'4G',label:'LTE'}]};
        appliedCoverageOptions=coverageStore.options;
        coverageAntenna.value='-123'; coverageTechnology.value='4G'; coverageBand.value='LTE';
        coverageHeight.value='y'; coverageY.value='70'; coveragePrecision.value='8';
        coverageToggle.checked=true; document.getElementById('cov-nperf').checked=false;
        nperfData=[{x:1,z:2}]; mapPointer={x:100,y:200}; drawComputedCoverage();`);
    seedCoverage(run);
    const names = ['mapImageStore.snapshot', 'pan', 'selectedNode', 'selectedZone', 'mapPointer',
        'coverageStore.options', 'appliedCoverageOptions', 'coverageStore.cache',
        'coverageStore.cache.values().next().value', 'speedtestSettings', 'nperfData', 'terrainGeneration'];
    const before = names.map(name => run(name));
    const filters = run('JSON.stringify(currentCoverageFilters())');
    const oldGeneration = run('sessionGeneration');
    requests.length = 0;
    elements.get('resume-reads').listeners.click();
    assert.equal(run('sessionGeneration'), oldGeneration + 1);
    await run('requestQueue');
    names.forEach((name, i) => assert.equal(run(name), before[i], name));
    assert.equal(run('zoom'), 3);
    assert.equal(run('JSON.stringify(currentCoverageFilters())'), filters);
    assert.equal(run('zoneConfirm.checked && zoneSelect.checked'), true);
    assert.equal(elements.get('speedtest-duration').value, '6000');
    for (const id of ['zone-terrain', 'zone-coverage', 'btn-speedtest']) assert.equal(elements.get(id).disabled, false, id);
    assert.equal(closed, closedBefore, 'resuming reads never closes the displayed bitmap');
    assert.equal(requests.filter(r => r.path.startsWith('/api/map-image')).length, 0);
});

test('read reset preserves an exact coverage zone, its job, confirmation and cancellation', async () => {
    const { run, elements } = await dashboard({ fetcher: async () =>
        ({ status: 200, json: async () => ({ nodes: [], edges: [], mapId: 'world' }) }) });
    run(`applyZoneJob({id:'job',kind:'coverage',state:'running',total:1,completed:0,progress:0,message:'',
        bounds:{minX:-16,minZ:0,maxX:-1,maxZ:15},step:1,height:'70',antenna:'-123',technology:'4G',band:'LTE',
        coverageTiles:[{x:-1,z:0,level:-3,step:1}]}); zoneConfirm.checked=true;`);
    const names = ['forcedCoverageView', 'selectedZone', 'zoneJob'];
    const before = names.map(name => run(name));
    assert.equal(elements.get('zone-cancel').disabled, false);
    run('resetDashboardSession()');
    await run('requestQueue');
    names.forEach((name, i) => assert.equal(run(name), before[i], name));
    assert.equal(elements.get('zone-cancel').disabled, false);
    assert.equal(run('zoneConfirm.checked'), true);
    assert.equal(run('currentCoverageFilters().step'), 1);
});

test('read reset does not recenter a manually chosen viewport when the first nodes arrive', async () => {
    let loaded = false;
    const { run } = await dashboard({ fetcher: async () =>
        ({ status: 200, json: async () => ({ nodes: loaded ? routers() : [], edges: [], mapId: 'world' }) }) });
    run('pan={x:123,y:-456};zoom=4');
    assert.equal(run('initialCenterDone'), false);
    loaded = true;
    run('resetDashboardSession()');
    await run('requestQueue');
    assert.equal(run('JSON.stringify(pan)'), '{"x":123,"y":-456}');
    assert.equal(run('zoom'), 4);
});

test('read reset before world metadata does not disable initial map centering', async () => {
    const { run } = await dashboard({ fetcher: async () => ({ status: 202, headers: { get: () => '1' } }) });
    run('initialCenterDone=false;terrainMapId=null;');
    run('resetDashboardSession()');
    assert.equal(run('initialCenterDone'), false);
});

test('read resets reject old network, player, nperf, zone and radio JSON without clearing their displayed state', async () => {
    for (const route of ['/api/network', '/api/player', '/api/nperf_map', '/api/zone-jobs', '/api/coverage/options']) {
        let defer = false, release;
        const { run } = await dashboard({ fetcher: async path => {
            if (defer && path === route) return { status: 200, json: () => new Promise(resolve => { release = resolve; }) };
            return { status: 200, json: async () => path === '/api/network' ? { nodes: routers(), edges: [], mapId: 'world' } : [] };
        } });
        run(`document.getElementById('cov-nperf').checked=true; nperfData=[{x:1,z:2}];
            zoneJob={id:'displayed',kind:'terrain',state:'running',progress:0,completed:0,total:1,message:''};
            setSelectedZone({minX:0,minZ:0,maxX:15,maxZ:15});
            pan={x:123,y:-456}; coverageStore.options={minY:-64,maxY:319,modelRevision:'keep'}; coverageStore.nextOptionsAt=0;`);
        seedCoverage(run);
        const names = ['networkData', 'pan', 'nperfData', 'zoneJob', 'selectedZone', 'coverageStore.options',
            'coverageStore.cache.values().next().value'];
        const before = names.map(name => run(name));
        defer = true;
        const calls = { '/api/network': 'fetchNetworkData()', '/api/player': 'initialCenterDone=false;fetchPlayerData()',
            '/api/nperf_map': 'fetchNperfData()', '/api/zone-jobs': 'pollZoneJobs()',
            '/api/coverage/options': 'coverageStore.tick([], currentCoverageFilters())' };
        const pending = run(calls[route]);
        await run('requestQueue');
        assert.equal(typeof release, 'function', route);
        run('initialCenterDone=true');
        run('resetDashboardSession()');
        defer = false;
        release(route === '/api/network' ? { nodes: [], edges: [], mapId: 'obsolete-world' }
            : route === '/api/player' ? { x: 9999, z: 9999 }
                : route === '/api/nperf_map' ? [{ x: 9999, z: 9999 }]
                    : route === '/api/zone-jobs' ? { job: null } : { minY: 9999, maxY: 9999 });
        await pending;
        await run('requestQueue');
        // Other routes may refresh normally; the old route alone must not replace its state.
        const index = { '/api/network': 0, '/api/player': 1, '/api/nperf_map': 2,
            '/api/zone-jobs': 3, '/api/coverage/options': 5 }[route];
        assert.equal(run(names[index]), before[index], route);
        assert.equal(run('selectedZone'), before[4]);
        assert.equal(run('coverageStore.cache.values().next().value'), before[6]);
        assert.equal(run('terrainMapId'), 'world');
    }
});

test('read reset during an old image decode keeps the displayed bitmap and closes only the obsolete result', async () => {
    let revision = 'r1', release, closed = 0;
    const { run } = await dashboard({ fetcher: async path => path === '/api/network'
        ? { status: 200, json: async () => ({ nodes: [], edges: [], mapId: 'world', mapImage: metadata({ revision }) }) }
            : pngResponse({ revision }), bitmap: async () => revision === 'r2'
                ? new Promise(resolve => { release = () => resolve({ close() { closed++; } }); })
                : { close() { closed++; } } });
    await run('requestQueue');
    const snapshot = run('mapImageStore.snapshot');
    assert.ok(snapshot);
    revision = 'r2';
    await run('fetchNetworkData()');
    await run('requestQueue');
    assert.equal(typeof release, 'function');
    run('resetDashboardSession()');
    await run('requestQueue');
    assert.equal(run('mapImageStore.snapshot'), snapshot);
    release();
    await flush();
    assert.equal(run('mapImageStore.snapshot'), snapshot);
    assert.equal(run('mapImageStore.pending'), false);
    assert.equal(closed, 1);
});

test('world change purges image, radio, selections, zone confirmation and duration preferences', async () => {
    let closed = 0;
    const { run } = await dashboard({ fetcher: async () => ({ status: 200,
        json: async () => ({ nodes: [], edges: [], mapId: 'new-world' }) }) });
    run('terrainMapId="old-world"');
    run(`selectedNode={id:'private'}; pan={x:1,y:2}; zoom=3;
        setSelectedZone({minX:0,minZ:0,maxX:15,maxZ:15}); zoneConfirm.checked=true;
        speedtestSettings.set('private',{duration:'6000'}); coverageStore.cache.set('private',{});`);
    run('mapImageStore.snapshot={image:{close(){}}}');
    const image = run('mapImageStore.snapshot.image');
    image.close = () => { closed++; };
    await run('fetchNetworkData()');
    assert.equal(closed, 1);
    assert.equal(run('mapImageStore.snapshot'), null);
    assert.equal(run('selectedNode'), null);
    assert.equal(run('selectedZone'), null);
    assert.equal(run('zoneConfirm.checked'), false);
    assert.equal(run('speedtestSettings.size + coverageStore.cache.size'), 0);
    await run('requestQueue');
});

test('API pause, empty responses and rate limits use connection-status', async () => {
    let status = 200;
    const { run, elements, advance } = await dashboard({ fetcher: async () =>
        ({ status, headers: new Headers({ 'Retry-After': '1' }), json: async () => ({ nodes: [], edges: [] }) }) });
    for (const code of [202, 204, 429, 503, 504, 200]) {
        status = code;
        advance(2000);
        await run(`apiFetch('/api/status-${code}')`);
        assert.notEqual(elements.get('connection-status').textContent, '');
        if (code === 202) assert.match(elements.get('connection-status').textContent, /pause/);
    }
});

test('API 401/403 report origin/proxy errors, pause denied reads without retry loops and do not gate actions', async () => {
    for (const status of [401, 403]) for (const method of ['GET', 'POST']) {
        let denied = false;
        const { run, elements, requests } = await dashboard({ fetcher: async () => denied
            ? { status } : { status: 200, json: async () => ({ nodes: routers(), edges: [], mapId: 'world' }) } });
        selectRouter(run, 0);
        run('zoneStep.value="8";zoneConfirm.checked=true;setSelectedZone({minX:0,minZ:0,maxX:15,maxZ:15})');
        denied = true;
        const first = run(`apiFetch('/api/refusal', {method:'${method}'})`);
        const queued = run("apiFetch('/api/action', {method:'POST'})");
        await first;
        await queued;
        assert.equal(run('tilesPaused'), method === 'GET');
        for (const id of ['zone-terrain', 'zone-coverage', 'btn-speedtest']) assert.equal(elements.get(id).disabled, false, id);
        assert.equal(requests.some(r => r.path === '/api/action'), true);
        assert.match(elements.get('connection-status').textContent, /origine|proxy/);
        const count = requests.length;
        denied = false;
        for (let i = 0; i < 5; i++) await run('fetchNetworkData()');
        assert.equal(requests.length, count + (method === 'GET' ? 0 : 5));
        if (method === 'GET') {
            await assert.rejects(run("apiFetch('/api/blocked-read')"), error => error.deferred && error.retryAt === Infinity);
            elements.get('resume-reads').listeners.click();
            await run('requestQueue');
            assert.equal(run('tilesPaused'), false);
            assert.equal(requests.length, count + 1);
        }
    }
});

test('network metadata drives one credential-free global PNG; 500 pan/zoom and unchanged polls add no image fetch', async () => {
    let revision = 'r1', ready = false;
    const mapId = 'opaque /?&=+#';
    const { run, requests, draws, elements } = await dashboard({ fetcher: async path => path === '/api/network'
        ? { status: 200, json: async () => ({ nodes: [], edges: [], mapId, mapImage: metadata({ revision, ready }) }) }
        : pngResponse({ revision, blocksPerPixel: 8 }) });
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
    assert.equal(images()[0].options.headers.has('Authorization'), false);
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

test('world changes close snapshots and invalidate radio, while read reset keeps the opaque map identity', async () => {
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
    const snapshot = run('mapImageStore.snapshot');
    run('resetDashboardSession()');
    assert.equal(run('mapImageStore.snapshot'), snapshot);
    assert.equal(run('terrainMapId'), mapId);
    assert.equal(closed, 1);
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

test('read reset preserves the HTTP busy barrier and finite backoff, including a stale busy response', async () => {
    let release;
    const { run, requests } = await dashboard({ fetcher: async path => path === '/api/slow'
        ? new Promise(resolve => { release = resolve; })
        : { status: 200, json: async () => ({ nodes: [], edges: [] }) } });
    const slow = assert.rejects(run("apiFetch('/api/slow')"), error => error.deferred);
    const obsolete = assert.rejects(run("apiFetch('/api/obsolete')"), error => error.deferred);
    await flush();
    const nextAt = run('apiNextAt');
    run('apiRetryAt=Date.now()+1000;resetDashboardSession()');
    assert.equal(run('apiNextAt'), nextAt);
    assert.equal(run('apiRetryAt'), requests[0].at + 1000);
    await flush();
    assert.equal(requests.length, 1, 'reset cannot start a second transport while HTTP is busy');
    release({ status: 503, headers: new Headers({ 'Retry-After': '2' }) });
    await Promise.all([slow, obsolete]);
    await run('requestQueue');
    assert.deepEqual(requests.map(r => r.path), ['/api/slow', '/api/network']);
    assert.equal(requests[1].at - requests[0].at, 2000);
    assert.equal(run('queuedRequests'), 0);
});

test('read reset during a dispatched zone start keeps reconciliation locked and ignores stale acknowledgement', async () => {
    let release;
    const { run, requests, elements } = await dashboard({ fetcher: async (path, options) => options.method === 'POST'
        ? { status: 202, json: () => new Promise(resolve => { release = resolve; }) }
        : { status: 200, json: async () => path === '/api/network' ? { nodes: [], edges: [], mapId: 'world' } : { job: null } } });
    run('zoneConfirm.checked=true;setSelectedZone({minX:0,minZ:0,maxX:15,maxZ:15})');
    const start = run('startZoneJob("terrain")');
    await run('requestQueue');
    run('resetDashboardSession()');
    assert.equal(run('zoneReconcile'), true);
    assert.equal(elements.get('zone-terrain').disabled, true);
    await run('startZoneJob("terrain")');
    release({ id: 'obsolete', status: 'queued' });
    await start;
    assert.equal(run('zoneJob'), null);
    assert.equal(run('zoneReconcile'), true);
    await run('pollZoneJobs()');
    assert.equal(run('zoneReconcile'), false);
    assert.equal(run('zoneConfirm.checked'), true);
    assert.equal(requests.filter(r => r.options.method === 'POST').length, 1);
});

test('untrusted IP text is escaped before HTML interpolation', async () => {
    const { run } = await dashboard();
    assert.equal(run(`escapeHtml('<img src=x onerror="steal()">')`), '&lt;img src=x onerror=&quot;steal()&quot;&gt;');
});

test('shared cable load is identical in details, hover and drawing, including deployed DTOs without capacityMode', async () => {
    for (const mode of [undefined, 'SHARED']) {
        const nodes = [{ id: '1', type: 'PM', x: 40, y: 64, z: 40 }, { id: '2', type: 'PM', x: 240, y: 64, z: 40 }];
        const edge = { source: '1', target: '2', type: 'FIBER', length: 200, capacity: 100,
            usageDown: 50, usageUp: 50, ...(mode ? { capacityMode: mode, nominalCapacity: 100 } : {}) };
        const { run, elements, windowListeners, draws, requests } = await dashboard({ fetcher: async () => ({
            status: 200, json: async () => ({ nodes, edges: [edge] }),
        }) });
        run('pan={x:0,y:0};zoom=1;showEdgeDetails(networkData.edges[0]);draw()');
        assert.match(elements.get('details-content').innerHTML, /Charge partagée \(DOWN \+ UP\)/);
        assert.match(elements.get('details-content').innerHTML, /100\.0%/);
        assert.match(elements.get('details-content').innerHTML, /Saturé/);
        assert.ok(draws.some(draw => draw.method === 'stroke' && draw.stroke === 'hsla(0, 100%, 50%, 0.8)'));
        assert.ok(!draws.some(draw => draw.method === 'setLineDash' && draw.args[0]?.length === 2));
        windowListeners.pointermove({ clientX: 440, clientY: 40, target: run('canvas') });
        assert.match(elements.get('tooltip').innerHTML, /Charge partagée \(DOWN \+ UP\) : 100\.0%/);
        assert.equal(requests.length, 0, 'load views introduce no network reads');
    }
});

test('asymmetric node load uses each directional budget in details, hover and animation', async () => {
    for (const mode of [undefined, 'DIRECTIONAL']) {
        const node = { id: '1', type: 'ROUTER', x: 40, y: 64, z: 40, capacity: 1000,
            capacityDown: 1000, capacityUp: 100, usageDown: 0, usageUp: 100, capacityMode: mode };
        const { run, elements, windowListeners, draws } = await dashboard({ fetcher: async () => ({
            status: 200, json: async () => ({ nodes: [node], edges: [] }),
        }) });
        run('pan={x:0,y:0};zoom=1;showNodeDetails(networkData.nodes[0]);animationTime=0;draw()');
        const html = elements.get('details-content').innerHTML;
        assert.match(html, /Charge directionnelle<\/span> <span>100\.0%/);
        assert.match(html, /Saturé/);
        assert.match(html, /width: 0%/);
        assert.match(html, /width: 100%/);
        assert.equal(run('nodeLoadPercent(networkData.nodes[0])'), 100);
        const pulse = draws.filter(draw => draw.method === 'arc').at(-1);
        const expected = run('5 + ((animationTime * 6) % 2) * 15');
        assert.equal(pulse.args[2], expected);
        windowListeners.pointermove({ clientX: 340, clientY: 40, target: run('canvas') });
        assert.match(elements.get('tooltip').innerHTML, /Charge directionnelle : 100\.0%/);
    }
});

test('zero and invalid loads stay finite, and effective capacity never exceeds nominal', async () => {
    const { run, elements } = await dashboard();
    for (const value of ['0', 'NaN', 'undefined', 'Infinity', '-1']) {
        run(`showEdgeDetails({type:'COPPER',length:0,capacity:${value},usageDown:${value},usageUp:${value}})`);
        assert.match(elements.get('details-content').innerHTML, /0\.0%/);
        assert.doesNotMatch(elements.get('details-content').innerHTML, /NaN|Infinity|undefined/);
        run(`showNodeDetails({type:'PM',capacityDown:${value},capacityUp:${value},usageDown:${value},usageUp:${value},x:0,y:0,z:0})`);
        assert.match(elements.get('details-content').innerHTML, /0\.0%/);
        assert.doesNotMatch(elements.get('details-content').innerHTML, /NaN|Infinity|undefined/);
    }
    assert.equal(run('edgeLoadPercent({capacity:0,usageUp:100})'), 100);
    assert.equal(run('nodeLoadPercent({capacityDown:1000,capacityUp:0,usageUp:100})'), 100);
    assert.equal(run('edgeCapacity({capacity:1000,nominalCapacity:100})'), 100);
    run(`showEdgeDetails({type:'COPPER',length:250,capacity:500,nominalCapacity:1000,capacityMode:'SHARED',usageDown:250,usageUp:250})`);
    const html = elements.get('details-content').innerHTML;
    assert.match(html, /Capacité effective partagée<\/span> <span class="capacity-text">500 Mbps/);
    assert.match(html, /Capacité nominale<\/span> <span class="capacity-text">1\.0 Gbps/);
    assert.match(html, /100\.0%/);
});

test('antenna collection and existing radio frequency capacity stay distinct and escaped', async () => {
    const { run, elements } = await dashboard();
    run(`showNodeDetails({type:'ANTENNA',x:0,y:64,z:0,capacityDown:1000000,capacityUp:1000000,
        usageDown:0,usageUp:0,frequencies:[{label:'<img src=x>',technology:'<4G>',max:150,usage:75}]})`);
    const html = elements.get('details-content').innerHTML;
    assert.match(html, /Collecte filaire/);
    assert.match(html, /Radio : utilisation par Fréquence/);
    assert.match(html, /1000\.0 Gbps/);
    assert.match(html, /75 Mbps \/ 150 Mbps/);
    assert.match(html, /width: 50%/);
    assert.match(html, /&lt;img src=x&gt; \(&lt;4G&gt;\)/);
    assert.doesNotMatch(html, /<img/);
});

test('server catalogue describes a downstream path ceiling, not guaranteed duplex', async () => {
    const { run, elements } = await dashboard();
    run(`routerSettings('1').servers=[{id:'2',name:'<img src=x>',available:true,estimatedPingMs:10,bandwidthMbps:100}];
        showNodeDetails({id:'1',type:'ROUTER',x:0,y:64,z:0})`);
    const html = elements.get('details-content').innerHTML;
    assert.match(html, /plafond descendant du trajet 100 Mbps/);
    assert.match(html, /&lt;img src=x&gt;/);
    assert.doesNotMatch(html, /<img|duplex|garanti/);
});

const routers = () => ['-9223372036854775808', '9223372036854775807'].map((id, x) => ({
    id, x, y: 64, z: 0, type: 'ROUTER', ip: '192.168.1.1', speedtest: null,
    capacityDown: 100, capacityUp: 50, usageDown: 0, usageUp: 0,
}));
const speedtest = (deviceId, sessionId, active = true) => ({
    deviceId: `router:${deviceId}`, sessionId, active, state: active ? 'DOWNLOAD' : 'FINISHED', pingMs: 12,
    serverId: '0', serverName: 'Destination', errorCode: '',
    actualBandwidth: 80, ticksElapsed: 150, totalTicksPerPhase: 300, downloadBandwidth: 75, uploadBandwidth: 25,
});
const started = (deviceId, sessionId) => ({ ok: true, status: 200,
    json: async () => ({ status: 'started', deviceId: `router:${deviceId}`, sessionId, serverId: '0', serverName: 'Destination' }) });
function selectRouter(run, index) {
    run(`selectedNode = networkData.nodes[${index}]; showNodeDetails(selectedNode)`);
}

test('same-IP routers start independently, duplicates stay blocked through refresh, and A never updates B', async () => {
    const nodes = routers();
    const releases = [];
    const { run, elements, requests } = await dashboard({ fetcher: async path => path === '/api/network'
        ? { status: 200, json: async () => ({ mapId: 'world-a', nodes, edges: [] }) }
        : new Promise(resolve => releases.push(resolve)) });
    selectRouter(run, 0);
    elements.get('speedtest-duration').value = '1200';
    elements.get('speedtest-duration').listeners.change();
    const a = elements.get('btn-speedtest').listeners.click();
    await flush();
    assert.equal(elements.get('btn-speedtest').innerText, 'Démarrage...');
    selectRouter(run, 0);
    assert.equal(elements.get('btn-speedtest').disabled, true);
    assert.equal(elements.get('speedtest-duration').value, '1200');
    await elements.get('btn-speedtest').listeners.click();
    selectRouter(run, 1);
    assert.equal(elements.get('btn-speedtest').disabled, false);
    assert.equal(elements.get('speedtest-duration').value, '300');
    const b = elements.get('btn-speedtest').listeners.click();
    const buttonB = elements.get('btn-speedtest');
    assert.equal(run('speedtestPending.size'), 2);
    releases[0](started(nodes[0].id, 'session-a'));
    await a;
    await flush();
    assert.equal(elements.get('btn-speedtest'), buttonB);
    assert.equal(buttonB.innerText, 'Démarrage...');
    releases[1](started(nodes[1].id, 'session-b'));
    await b;
    await run('requestQueue');
    await run('fetchNetworkData()');
    assert.equal(run('speedtestPending.size'), 2, 'null snapshot cannot unlock an acknowledged start');
    assert.equal(elements.get('btn-speedtest').disabled, true);
    nodes[0].speedtest = speedtest(nodes[0].id, 'session-a');
    nodes[1].speedtest = speedtest(nodes[1].id, 'session-b');
    await run('fetchNetworkData()');
    assert.equal(run('speedtestPending.size'), 0);
    assert.equal(elements.get('btn-speedtest').innerText, 'Speedtest en cours !');
    await elements.get('btn-speedtest').listeners.click();
    selectRouter(run, 0);
    assert.equal(elements.get('speedtest-duration').value, '1200');
    const posts = requests.filter(r => r.path === '/api/speedtest');
    assert.deepEqual(posts.map(r => JSON.parse(r.options.body)), [
        { pos: nodes[0].id, duration: 1200, serverId: '' }, { pos: nodes[1].id, duration: 300, serverId: '' },
    ]);
    assert.ok(posts[1].at - posts[0].at >= 200);
});

test('node snapshot is authoritative for active status, progress and per-device results', async () => {
    const nodes = routers();
    nodes[0].speedtest = speedtest(nodes[0].id, 'another-client');
    const { run, elements, requests } = await dashboard({ fetcher: async () => ({ status: 200,
        json: async () => ({ nodes, edges: [], mapId: 'world-a' }) }) });
    selectRouter(run, 0);
    assert.equal(elements.get('btn-speedtest').disabled, true);
    assert.equal(elements.get('speedtest-phase-progress').textContent, '51 %', '150 confirmed ticks plus 200 ms of presentation time');
    assert.equal(elements.get('speedtest-progress-text').textContent, '32 %', 'includes the 60-tick ping in 660 total ticks');
    assert.match(elements.get('details-content').innerHTML, /12 ms/);
    assert.match(elements.get('details-content').innerHTML, /75/);
    assert.match(elements.get('details-content').innerHTML, /25/);
    await elements.get('btn-speedtest').listeners.click();
    assert.equal(requests.filter(r => r.path === '/api/speedtest').length, 0);
    nodes[0].speedtest = speedtest(nodes[0].id, 'another-client', false);
    await run('fetchNetworkData()');
    assert.equal(elements.get('btn-speedtest').disabled, false);
    assert.match(elements.get('details-content').innerHTML, /FINISHED/);
    nodes[1].speedtest = nodes[0].speedtest;
    await run('fetchNetworkData()');
    selectRouter(run, 1);
    assert.doesNotMatch(elements.get('details-content').innerHTML, /12 ms|FINISHED/);
});

test('speedtest history is captured only by accepted network snapshots, not frames, panel rebuilds or duplicate polls', async () => {
    const nodes = routers();
    nodes[0].speedtest = speedtest(nodes[0].id, 'live');
    nodes[1].speedtest = { ...speedtest(nodes[1].id, 'other'), actualBandwidth: 19 };
    const { run, elements, advance, requests } = await dashboard({ fetcher: async () => ({ status: 200,
        json: async () => ({ nodes, edges: [], mapId: 'world' }) }) });
    assert.equal(run('speedtestStore.devices.size'), 2, 'unselected devices also retain samples');
    selectRouter(run, 0);
    elements.get('speedtest-duration').value = '1200';
    elements.get('speedtest-duration').listeners.change();
    elements.get('speedtest-server').value = '9223372036854775807';
    elements.get('speedtest-server').listeners.change();
    const entry = run(`speedtestStore.get('${nodes[0].id}')`);
    advance(1000);
    run('draw()');
    const oldValue = elements.get('speedtest-value');
    selectRouter(run, 0);
    assert.notEqual(elements.get('speedtest-value'), oldValue, 'DOM bindings were rebuilt');
    assert.equal(elements.get('speedtest-value').textContent, oldValue.textContent, 'smoothing survived the rebuild');
    for (let i = 0; i < 120; i++) run('draw()');
    assert.equal(requests.length, 0, 'animation and selection never start reads');
    assert.equal(entry.down.length, 1);
    await run('fetchNetworkData()');
    assert.equal(entry.down.length, 1);
    assert.equal(run('networkNextAt - Date.now()'), 2000, 'existing network cadence is unchanged');
    assert.equal(elements.get('speedtest-duration').value, '1200');
    assert.equal(elements.get('speedtest-server').value, '9223372036854775807');
    advance(6001);
    run('draw()');
    assert.match(elements.get('speedtest-wait').textContent, /plus de 6 s/);
    const frozen = elements.get('speedtest-value').textContent;
    const progress = elements.get('speedtest-progress').value;
    advance(10000);
    run('draw()');
    assert.equal(elements.get('speedtest-value').textContent, frozen);
    assert.equal(elements.get('speedtest-progress').value, progress);
    assert.equal(elements.get('speedtest-activity-dot').style.opacity, '0');
    nodes[0].speedtest = { ...nodes[0].speedtest, ticksElapsed: 190, actualBandwidth: 21, downloadBandwidth: 62 };
    await run('fetchNetworkData()');
    assert.equal(elements.get('speedtest-value').textContent, '21.0');
    assert.match(elements.get('details-content').innerHTML, /62 Mbps/);
    assert.equal(entry.down.length, 2);
    assert.equal(entry.down[1].value, 21);
    assert.match(elements.get('details-content').innerHTML, /dernières 2 secondes \/ phase/);
    assert.match(elements.get('details-content').innerHTML, /Dernier snapshot \/ phase/);
    selectRouter(run, 1);
    assert.notEqual(elements.get('speedtest-value').textContent, '21.0');
    assert.equal(run(`speedtestStore.get('${nodes[1].id}').down.length`), 1);
});

test('speedtest rejects late network JSON across read resets, hidden tabs and old world generations', async () => {
    const nodes = routers();
    nodes[0].speedtest = speedtest(nodes[0].id, 'live');
    let release, delay = false, mapId = 'world-a';
    const { run, elements, advance } = await dashboard({ fetcher: async () => ({ status: 200, json: () => delay
        ? new Promise(resolve => { release = resolve; }) : Promise.resolve({ nodes, edges: [], mapId }) }) });
    selectRouter(run, 0);
    const entry = run(`speedtestStore.get('${nodes[0].id}')`);
    delay = true;
    const late = run('fetchNetworkData()');
    await run('requestQueue');
    run('resetDashboardSession()');
    release({ nodes: [{ ...nodes[0], speedtest: { ...nodes[0].speedtest, ticksElapsed: 200 } }], edges: [], mapId: 'old' });
    await late;
    assert.equal(entry.down.length, 1);
    assert.equal(run('terrainMapId'), 'world-a');
    const hidden = run('fetchNetworkData()');
    await run('requestQueue');
    run('document.hidden = true');
    release({ nodes: [{ ...nodes[0], speedtest: { ...nodes[0].speedtest, ticksElapsed: 200 } }], edges: [], mapId });
    await hidden;
    assert.equal(entry.down.length, 1);
    run('document.hidden = false');
    delay = false;
    await run('fetchNetworkData()');
    assert.equal(run(`speedtestStore.get('${nodes[0].id}')`), entry, 'reconnect preserves same-world history');
    mapId = 'world-b';
    nodes[0].speedtest = { ...nodes[0].speedtest, actualBandwidth: 5 };
    await run('fetchNetworkData()');
    assert.notEqual(run(`speedtestStore.get('${nodes[0].id}')`), entry, 'same UUID/ticks in a different world must reset');
    assert.equal(run(`speedtestStore.get('${nodes[0].id}').down[0].value`), 5);
    assert.equal(elements.get('details-panel').style.display, 'none');
    selectRouter(run, 0);
    advance(1000);
    run('draw()');
    assert.ok(Number(elements.get('speedtest-value').textContent) <= 5);
});

test('terminal speedtest keeps exact server averages and destination across refreshes, failure never shows success', async () => {
    for (const state of ['FINISHED', 'FAILED']) {
        const nodes = routers();
        nodes[0].speedtest = { ...speedtest(nodes[0].id, 'terminal', false), state,
            downloadBandwidth: 123456, uploadBandwidth: 7654, serverName: '<Server>' };
        const { run, elements, advance } = await dashboard({ fetcher: async () => ({ status: 200,
            json: async () => ({ nodes, edges: [] }) }) });
        selectRouter(run, 0);
        assert.equal(elements.get('speedtest-value').textContent, '123456');
        assert.match(elements.get('details-content').innerHTML, /123456 Mbps/);
        assert.match(elements.get('details-content').innerHTML, /7654 Mbps/);
        assert.match(elements.get('details-content').innerHTML, /&lt;Server&gt;/);
        assert.equal(elements.get('speedtest-progress').value === 100, state === 'FINISHED');
        assert.match(elements.get('speedtest-wait').textContent, state === 'FINISHED' ? /finaux/ : /interrompu/);
        advance(10000);
        run('draw()');
        nodes[0].speedtest = { ...nodes[0].speedtest, downloadBandwidth: 1, serverName: 'Wrong' };
        await run('fetchNetworkData()');
        assert.equal(elements.get('speedtest-value').textContent, '123456');
        assert.doesNotMatch(elements.get('details-content').innerHTML, /Wrong/);
        assert.equal(run(`speedtestStore.get('${nodes[0].id}').down.length`), 0);
    }
});

test('failed UI distinguishes unknown advancement from the last confirmed snapshot of the same session', async () => {
    const nodes = routers();
    nodes[0].speedtest = { ...speedtest(nodes[0].id, 'isolated', false), state: 'FAILED' };
    const { run, elements, advance } = await dashboard({ fetcher: async () => ({ status: 200,
        json: async () => ({ nodes, edges: [] }) }) });
    selectRouter(run, 0);
    assert.match(elements.get('details-content').innerHTML, /Dernier avancement confirmé/);
    assert.equal(elements.get('speedtest-progress-text').textContent, 'Inconnu');
    assert.equal(elements.get('speedtest-phase-progress').textContent, 'Inconnu');
    assert.equal(elements.get('speedtest-progress').hidden, true);
    assert.equal(elements.get('speedtest-progress').value, 0, 'hidden bar remains finite without asserting zero progress');
    assert.doesNotMatch(elements.get('details-content').innerHTML, /NaN|value="null"/);
    nodes[0].speedtest = { ...speedtest(nodes[0].id, 'next'), ticksElapsed: 100 };
    await run('fetchNetworkData()');
    const confirmed = elements.get('speedtest-progress').value;
    const phase = elements.get('speedtest-phase-progress').textContent;
    advance(3000);
    run('draw()');
    assert.ok(elements.get('speedtest-progress').value > confirmed);
    nodes[0].speedtest = { ...nodes[0].speedtest, state: 'FAILED', active: false, ticksElapsed: 190 };
    await run('fetchNetworkData()');
    assert.equal(elements.get('speedtest-progress').hidden, false);
    assert.equal(elements.get('speedtest-progress').value, confirmed);
    assert.equal(elements.get('speedtest-phase-progress').textContent, phase);
    assert.match(elements.get('details-content').innerHTML, /Dernier avancement confirmé/);
    nodes[0].speedtest = { ...nodes[0].speedtest, sessionId: 'new-failed' };
    await run('fetchNetworkData()');
    assert.equal(elements.get('speedtest-progress-text').textContent, 'Inconnu');
    assert.equal(elements.get('speedtest-progress').hidden, true);
    nodes[0].speedtest = { ...nodes[0].speedtest, sessionId: 'new-finished', state: 'FINISHED' };
    await run('fetchNetworkData()');
    assert.equal(elements.get('speedtest-progress').hidden, false);
    assert.equal(elements.get('speedtest-progress').value, 100);
    assert.equal(elements.get('speedtest-progress-text').textContent, '100 %');
});

test('new ACK destination replaces old terminal results during long read backoff until its own snapshot', async () => {
    const nodes = routers();
    const old = { ...speedtest(nodes[0].id, 'A', false), serverName: 'Old server A', downloadBandwidth: 123456 };
    nodes[0].speedtest = old;
    let acknowledge, busy = false;
    const { run, elements, advance, requests } = await dashboard({ manualTimers: true, fetcher: async path => {
        if (path === '/api/speedtest') return { status: 200, ok: true,
            json: () => new Promise(resolve => { acknowledge = resolve; }) };
        return busy ? { status: 503, headers: new Headers({ 'Retry-After': '20' }) }
            : { status: 200, json: async () => ({ nodes, edges: [] }) };
    } });
    selectRouter(run, 0);
    assert.match(elements.get('details-content').innerHTML, /123456 Mbps/);
    elements.get('speedtest-server').value = '200';
    elements.get('speedtest-server').listeners.change();
    const starting = elements.get('btn-speedtest').listeners.click();
    await run('requestQueue');
    assert.equal(elements.get('speedtest-server').value, '200');
    assert.equal(run('speedtestElements'), null);
    assert.doesNotMatch(elements.get('details-content').innerHTML, /Old server A|123456 Mbps|speedtest-gauge/);
    acknowledge({ status: 'started', deviceId: `router:${nodes[0].id}`, sessionId: 'B', serverId: '200', serverName: 'New server B' });
    await starting;
    const assertWaitingForB = () => {
        assert.match(elements.get('details-content').innerHTML, /Destination utilisée.*New server B.*\[200\]/s);
        assert.doesNotMatch(elements.get('details-content').innerHTML, /Old server A|123456 Mbps|speedtest-gauge|speedtest-chart/);
        assert.match(elements.get('details-content').innerHTML, /en attente du premier snapshot/);
        assert.equal(elements.get('speedtest-server').value, '200');
        assert.equal(elements.get('btn-speedtest').disabled, true);
        assert.equal(run('speedtestElements'), null);
        assert.equal(run('speedtestPending.size'), 1);
    };
    assertWaitingForB();
    advance(200);
    await run('fetchNetworkData()');
    assertWaitingForB();
    busy = true;
    advance(200);
    await run('fetchNetworkData()');
    const requestCount = requests.length;
    const deferred = run('fetchNetworkData()');
    await flush();
    advance(10000);
    await flush();
    run('draw();showNodeDetails(selectedNode)');
    assertWaitingForB();
    assert.equal(requests.length, requestCount, 'read backoff was not bypassed');
    busy = false;
    nodes[0].speedtest = { ...speedtest(nodes[0].id, 'B'), serverId: '200', serverName: 'New server B',
        ticksElapsed: 0, actualBandwidth: 0, downloadBandwidth: 0, uploadBandwidth: 0 };
    advance(10000);
    await deferred;
    assert.equal(run('speedtestPending.size'), 0);
    assert.equal(elements.get('speedtest-value').textContent, '0.0');
    assert.match(elements.get('details-content').innerHTML, /New server B/);
    assert.equal(run(`speedtestStore.get('${nodes[0].id}').down.length`), 1, 'real tick-zero allocation is retained');
    nodes[0].speedtest = old;
    advance(200);
    await run('fetchNetworkData()');
    assert.equal(run(`speedtestStore.get('${nodes[0].id}').snapshot.sessionId`), 'B');
    assert.match(elements.get('details-content').innerHTML, /New server B/);
    assert.doesNotMatch(elements.get('details-content').innerHTML, /Old server A|123456 Mbps/);
    assert.equal(elements.get('btn-speedtest').disabled, true, 'retired terminal cannot unlock the active session');
});

test('reduced motion removes speedtest motion while retaining real samples, mobile sizing and the sole draw loop', async () => {
    const nodes = routers();
    nodes[0].speedtest = speedtest(nodes[0].id, 'live');
    const { run, elements, advance, requests } = await dashboard({ reduceMotion: true, fetcher: async () => ({ status: 200,
        json: async () => ({ nodes, edges: [] }) }) });
    selectRouter(run, 0);
    assert.equal(elements.get('speedtest-value').textContent, '80.0');
    const progress = elements.get('speedtest-progress').value;
    advance(1500);
    run('draw()');
    assert.equal(elements.get('speedtest-progress').value, progress);
    assert.equal(elements.get('speedtest-activity-dot').style.opacity, '0');
    assert.equal(requests.length, 0);
    assert.equal((source.match(/renderSpeedtest\(Date.now\(\)\)/g) || []).length, 1);
    const css = await readFile(new URL('../style.css', import.meta.url), 'utf8');
    assert.match(css, /prefers-reduced-motion: reduce[\s\S]*\.speedtest-activity \{ visibility: hidden/);
    assert.match(css, /\.details-panel \{ width: min\(350px, 100vw\); max-width: 100%; height: 100%/);
    assert.match(css, /\.speedtest-chart \{ display: block; width: 100%; height: auto/);
});

test('failure unlocks only its device and malformed or misrouted acknowledgements are rejected', async () => {
    for (const response of [{ ok: false, status: 500, json: async () => ({}) }, started('wrong-device', 'session-a'),
        { ok: true, status: 200, json: async () => ({ status: 'started', deviceId: routers()[0].id, sessionId: 'raw-id' }) },
        { ok: true, status: 200, json: async () => ({ status: 'started', deviceId: routers()[0].id }) }]) {
        const nodes = routers();
        const releases = [];
        const { run, elements } = await dashboard({ fetcher: async path => path === '/api/network'
            ? { status: 200, json: async () => ({ nodes, edges: [] }) }
            : new Promise(resolve => releases.push(resolve)) });
        selectRouter(run, 0);
        const a = elements.get('btn-speedtest').listeners.click();
        selectRouter(run, 1);
        const b = elements.get('btn-speedtest').listeners.click();
        await flush();
        releases[0](response);
        await a;
        await flush();
        assert.equal(run('speedtestPending.size'), 1);
        assert.equal(elements.get('btn-speedtest').innerText, 'Démarrage...');
        selectRouter(run, 0);
        assert.equal(elements.get('btn-speedtest').disabled, false);
        assert.match(elements.get('details-content').innerHTML, /Erreur HTTP 500|Réponse Speedtest invalide/);
        releases[1](started(nodes[1].id, 'session-b'));
        await b;
    }
});

test('network allocation budget refusal is explicit and never automatically restarts the test', async () => {
    const nodes = routers();
    const { run, elements, requests } = await dashboard({ fetcher: async path => path === '/api/network'
        ? { status: 200, json: async () => ({ nodes, edges: [] }) }
        : { ok: false, status: 503, json: async () => ({ errorCode: 'network_limit' }) } });
    selectRouter(run, 0);
    await elements.get('btn-speedtest').listeners.click();
    assert.match(elements.get('details-content').innerHTML, /Limite de calcul réseau atteinte/);
    assert.equal(run('speedtestPending.size'), 0);
    assert.equal(elements.get('btn-speedtest').disabled, false);
    assert.equal(requests.filter(r => r.path === '/api/speedtest').length, 1);
});

test('world changes and read resets invalidate late JSON callbacks; only world changes clear device preferences', async () => {
    for (const reset of ['world', 'reads']) {
        const nodes = routers();
        let mapId = 'world-a', release;
        const { run, elements } = await dashboard({ fetcher: async path => path === '/api/network'
            ? { status: 200, json: async () => ({ mapId, nodes, edges: [] }) }
            : { ok: true, status: 200, json: () => new Promise(resolve => { release = resolve; }) } });
        selectRouter(run, 0);
        elements.get('speedtest-duration').value = '6000';
        elements.get('speedtest-duration').listeners.change();
        const old = elements.get('btn-speedtest').listeners.click();
        await run('requestQueue');
        if (reset === 'world') {
            mapId = 'world-b';
            await run('fetchNetworkData()');
        } else {
            run('resetDashboardSession()');
            await run('requestQueue');
        }
        assert.equal(run('speedtestPending.size'), 0);
        assert.equal(run('speedtestSettings.size'), reset === 'world' ? 0 : 1);
        assert.equal(elements.get('details-panel').style.display, reset === 'world' ? 'none' : 'flex');
        selectRouter(run, 0);
        const button = elements.get('btn-speedtest');
        release({ status: 'started', deviceId: `router:${nodes[0].id}`, sessionId: 'stale' });
        await old;
        assert.equal(elements.get('btn-speedtest'), button);
        assert.equal(run('speedtestPending.size'), 0);
        assert.equal(button.disabled, false);
        assert.equal(elements.get('speedtest-duration').value, reset === 'world' ? '300' : '6000');
    }
});

test('pending starts are bounded to eight including acknowledged sessions awaiting snapshots', async () => {
    const nodes = Array.from({ length: 9 }, (_, i) => ({ ...routers()[0], id: String(i) }));
    const { run, elements, requests } = await dashboard({ fetcher: async (path, options) => path === '/api/network'
        ? { status: 200, json: async () => ({ nodes, edges: [] }) }
        : started(JSON.parse(options.body).pos, `session-${JSON.parse(options.body).pos}`) });
    for (let i = 0; i < 9; i++) {
        selectRouter(run, i);
        await elements.get('btn-speedtest').listeners.click();
        await run('requestQueue');
    }
    assert.equal(run('speedtestPending.size'), 8);
    assert.equal(requests.filter(r => r.path === '/api/speedtest').length, 8);
    assert.match(elements.get('details-content').innerHTML, /File de démarrage pleine/);
});

test('network refresh during POST body decoding keeps the device lock and its duration', async () => {
    const nodes = routers();
    let release;
    const { run, elements } = await dashboard({ fetcher: async path => path === '/api/network'
        ? { status: 200, json: async () => ({ nodes, edges: [] }) }
        : { ok: true, status: 200, json: () => new Promise(resolve => { release = resolve; }) } });
    selectRouter(run, 0);
    elements.get('speedtest-duration').value = '6000';
    elements.get('speedtest-duration').listeners.change();
    const start = elements.get('btn-speedtest').listeners.click();
    await run('requestQueue');
    const detachedButton = elements.get('btn-speedtest');
    await run('fetchNetworkData()');
    assert.notEqual(elements.get('btn-speedtest'), detachedButton);
    assert.equal(elements.get('btn-speedtest').disabled, true);
    assert.equal(elements.get('btn-speedtest').innerText, 'Démarrage...');
    assert.equal(elements.get('speedtest-duration').value, '6000');
    nodes[0].speedtest = speedtest(nodes[0].id, 'session-a', false);
    await run('fetchNetworkData()');
    assert.equal(elements.get('btn-speedtest').disabled, true);
    release({ status: 'started', deviceId: `router:${nodes[0].id}`, sessionId: 'session-a' });
    await start;
    assert.equal(run('speedtestPending.size'), 0);
    assert.equal(elements.get('btn-speedtest').disabled, false);
    assert.match(elements.get('details-content').innerHTML, /FINISHED/);
});

test('queued starts are cancelled before transport on world changes or read resets', async () => {
    for (const reset of ['world', 'reads']) {
        const nodes = routers();
        let mapId = 'world-a';
        const { run, elements, requests } = await dashboard({ fetcher: async () => ({ status: 200,
            json: async () => ({ nodes, edges: [], mapId }) }) });
        selectRouter(run, 0);
        mapId = 'world-b';
        const network = run('fetchNetworkData()');
        const start = elements.get('btn-speedtest').listeners.click();
        if (reset === 'reads') run('resetDashboardSession()');
        await Promise.all([network, start]);
        await run('requestQueue');
        assert.equal(requests.filter(r => r.path === '/api/speedtest').length, 0);
        assert.equal(run('speedtestPending.size'), 0);
    }
});

test('server choices preserve long strings, unavailable selections and router isolation without auto fallback', async () => {
    const nodes = routers();
    let servers = [{ id: '0', name: '<img src=x onerror=bad()>', estimatedPingMs: 2, available: true, bandwidthMbps: 1000, reason: '' },
        { id: '-9223372036854775808', name: 'Distant', estimatedPingMs: 20, available: true, bandwidthMbps: 1000, reason: '' },
        { id: '-1', name: 'Isolated', estimatedPingMs: -1, available: false, bandwidthMbps: 0, reason: 'server_unavailable' }];
    const { run, elements, requests, advance } = await dashboard({ fetcher: async path => {
        if (path === '/api/network') return { status: 200, json: async () => ({ nodes, edges: [] }) };
        if (path.startsWith('/api/speedtest/servers?')) return { status: 200, json: async () => ({
            pos: new URL(path, 'http://localhost').searchParams.get('pos'), servers, truncated: true }) };
        return { status: 409, ok: false, json: async () => ({ errorCode: 'server_unavailable', error: '<bad>' }) };
    } });
    selectRouter(run, 0);
    await run('fetchSpeedtestServers()');
    assert.match(elements.get('details-content').innerHTML, /&lt;img/);
    assert.doesNotMatch(elements.get('details-content').innerHTML, /<img/);
    assert.match(elements.get('details-content').innerHTML, /128 serveurs|2 ms estimés/);
    for (let i = 0; i < 100; i++) { selectRouter(run, 0); await run('fetchSpeedtestServers()'); }
    assert.equal(requests.length, 1, 'renders and polling within cooldown do not fetch again');
    for (const id of ['0', '-9223372036854775808']) {
        elements.get('speedtest-server').value = id;
        elements.get('speedtest-server').listeners.change();
        await elements.get('btn-speedtest').listeners.click();
        assert.equal(JSON.parse(requests.at(-1).options.body).serverId, id);
        assert.match(elements.get('details-content').innerHTML, /Aucun repli automatique/);
        assert.equal(elements.get('btn-speedtest').disabled, false);
    }
    servers = servers.map(server => ({ ...server, available: false, estimatedPingMs: -1, reason: 'server_unavailable' }));
    advance(15000);
    await run('fetchSpeedtestServers()');
    assert.equal(elements.get('speedtest-server').value, '-9223372036854775808');
    assert.match(elements.get('details-content').innerHTML, /value="-9223372036854775808" disabled/);
    servers = [];
    advance(15000);
    await run('fetchSpeedtestServers()');
    assert.equal(elements.get('speedtest-server').value, '-9223372036854775808');
    assert.match(elements.get('details-content').innerHTML, /absent du catalogue, aucun repli auto/);
    selectRouter(run, 1);
    assert.equal(elements.get('speedtest-server').value, '');
    selectRouter(run, 0);
    assert.equal(elements.get('speedtest-server').value, '-9223372036854775808');
    await elements.get('btn-speedtest').listeners.click();
    assert.equal(JSON.parse(requests.at(-1).options.body).serverId, '-9223372036854775808');
    nodes[0].speedtest = { ...speedtest(nodes[0].id, 'failed', false), state: 'FAILED', serverId: '-1',
        serverName: '<script>bad</script>', errorCode: 'route_lost' };
    await run('fetchNetworkData()');
    assert.match(elements.get('details-content').innerHTML, /Destination utilisée.*&lt;script&gt;bad&lt;\/script&gt;.*\[-1\]/s);
    assert.match(elements.get('details-content').innerHTML, /Aucun repli automatique/);
    assert.match(elements.get('details-content').innerHTML, /Connexion au serveur perdue/);
});

test('catalogue reads respect pending and shared cooldowns, and expose retryable failures', async () => {
    const nodes = routers();
    let status = 202;
    const { run, requests, elements, advance } = await dashboard({ fetcher: async path => {
        if (path === '/api/network') return { status: 200, json: async () => ({ nodes, edges: [] }) };
        if (status === 0) throw new Error('Network failed');
        return { status, headers: { get: () => '2' }, json: async () => ({ pos: nodes[0].id, servers: [], truncated: false }) };
    } });
    selectRouter(run, 0);
    await run('fetchSpeedtestServers()');
    assert.match(elements.get('details-content').innerHTML, /attente du tick/);
    await elements.get('speedtest-refresh').listeners.click();
    assert.equal(requests.length, 1, 'manual refresh cannot bypass route Retry-After');
    advance(2000);
    status = 0;
    await run('fetchSpeedtestServers()');
    assert.match(elements.get('details-content').innerHTML, /Network failed/);
    await run('fetchSpeedtestServers()');
    assert.equal(requests.length, 2);
    status = 200;
    await elements.get('speedtest-refresh').listeners.click();
    assert.equal(requests.length, 3);
    assert.match(elements.get('details-content').innerHTML, /Aucun serveur dans le catalogue/);
});

test('late catalogue JSON cannot publish after world, read reset, router change or removal', async () => {
    for (const reset of ['world', 'reads', 'router', 'removed', 'closed', 'type']) {
        const nodes = routers();
        let mapId = 'world-a', release;
        const { run, elements } = await dashboard({ fetcher: async path => {
            if (path === '/api/network') return { status: 200, json: async () => ({ mapId, nodes, edges: [] }) };
            if (!path.startsWith('/api/speedtest/servers?')) return { status: 200, json: async () => [] };
            return { status: 200, json: () => new Promise(resolve => { release = resolve; }) };
        } });
        selectRouter(run, 0);
        const old = run('fetchSpeedtestServers()');
        await run('requestQueue');
        if (reset === 'world') { mapId = 'world-b'; await run('fetchNetworkData()'); }
        if (reset === 'reads') { run('resetDashboardSession()'); await run('requestQueue'); }
        if (reset === 'router') { selectRouter(run, 1); selectRouter(run, 0); }
        if (reset === 'removed') { nodes.splice(0, 1); await run('fetchNetworkData()'); }
        if (reset === 'closed') { elements.get('details-close').listeners.click(); selectRouter(run, 0); }
        if (reset === 'type') { nodes[0].type = 'SERVER'; selectRouter(run, 0); nodes[0].type = 'ROUTER'; selectRouter(run, 0); }
        release({ pos: routers()[0].id, servers: [{ id: '0', name: 'STALE', available: true, estimatedPingMs: 1, bandwidthMbps: 100 }] });
        await old;
        assert.equal(run('[...speedtestSettings.values()].some(s => s.servers.some(server => server.name === "STALE"))'), false, reset);
        assert.doesNotMatch(elements.get('details-content').innerHTML, /STALE/, reset);
    }
});

test('queued catalogue requests cancel before transport on selection and generation changes', async () => {
    for (const reset of ['router', 'world', 'reads']) {
        const nodes = routers();
        const { run, requests, advance } = await dashboard({ manualTimers: true, fetcher: async () => ({ status: 200,
            json: async () => ({ nodes, edges: [] }) }) });
        selectRouter(run, 0);
        await run('apiFetch("/api/budget")');
        const pending = run('fetchSpeedtestServers()');
        await flush();
        if (reset === 'router') selectRouter(run, 1);
        else run(reset === 'world' ? 'terrainGeneration++' : 'sessionGeneration++');
        advance(200);
        await pending;
        assert.equal(requests.filter(r => r.path.startsWith('/api/speedtest/servers')).length, 0);
    }
});

test('catalogue origin refusal is explicit and cannot be retried around the shared read pause', async () => {
    const nodes = routers();
    const { run, requests, elements, advance } = await dashboard({ fetcher: async path => path === '/api/network'
        ? { status: 200, json: async () => ({ nodes, edges: [] }) }
        : { status: 403, json: async () => { throw new SyntaxError('Not JSON'); } } });
    selectRouter(run, 0);
    await run('fetchSpeedtestServers()');
    assert.match(elements.get('details-content').innerHTML, /HTTP 403.*Réessayer/);
    assert.match(elements.get('connection-status').textContent, /Lectures suspendues/);
    advance(5000);
    await elements.get('speedtest-refresh').listeners.click();
    await run('fetchSpeedtestServers()');
    assert.equal(requests.length, 1);
    assert.match(elements.get('details-content').innerHTML, /reprendre les lectures/);
});

test('catalogue computation limits explain the server refusal', async () => {
    const nodes = routers();
    const { run, elements } = await dashboard({ fetcher: async path => path === '/api/network'
        ? { status: 200, json: async () => ({ nodes, edges: [] }) }
        : { status: 503, json: async () => ({ errorCode: 'catalogue_limit' }) } });
    selectRouter(run, 0);
    await run('fetchSpeedtestServers()');
    assert.match(elements.get('details-content').innerHTML, /Réseau trop volumineux/);
});

test('coverage is opt-in and sends no credentials', async () => {
    const { run, requests, elements } = await dashboard();
    run('updateComputedCoverage()');
    assert.equal(requests.length, 0);
    elements.get('cov-computed').checked = true;
    run('updateComputedCoverage()');
    await run('requestQueue');
    const request = requests.find(r => r.path === '/api/coverage/options');
    assert.ok(request);
    assert.equal(request.options.headers.has('Authorization'), false);
    assert.equal(request.options.credentials, 'omit');
});

test('manual coverage refresh invalidates its cache, while read reset preserves it', async () => {
    const { run, elements } = await dashboard();
    run("coverageStore.cache.set('0/0,0', { data: {} })");
    elements.get('coverage-refresh').listeners.click();
    assert.equal(run('coverageStore.cache.size'), 0);
    run("coverageStore.cache.set('0/0,0', { data: {} }); resetDashboardSession()");
    assert.equal(run('coverageStore.cache.size'), 1);
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

test('long Retry-After suspends backlog and image retry until voluntary read reset', async () => {
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
    assert.match(elements.get('connection-status').textContent, /30 s.*suspendues/);
    limited = false;
    elements.get('resume-reads').listeners.click();
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

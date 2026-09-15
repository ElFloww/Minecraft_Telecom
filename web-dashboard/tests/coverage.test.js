import assert from 'node:assert/strict';
import { test } from 'node:test';
import { CoverageStore, signalState, coverageStep, coverageQuery, visibleCoverageTiles,
    coverageTileSize, retryDelay, validateCoverageOptions } from '../coverage.js';

const options = { tileSize: 128, minLevel: -3, maxLevel: 6, maxSamplesPerSide: 16,
    sharedTechnologies: true, technologies: ['2G', '3G', '4G', '5G'],
    steps: [1, 8, 16, 32, 64, 128, 256, 512, 1024, 2048], minY: -64, maxY: 319, maxRange: 4096, modelRevision: '0',
    bands: [{ id: 'BAND_700', technology: '4G', label: '700 MHz' },
        { id: 'N78', technology: '5G', label: '3500 MHz' }, { id: 'GSM', technology: '2G', label: '900 MHz' }] };
const filters = { step: 32, y: 'surface', antenna: 'all', technology: 'all', band: 'all' };
const tile = { tx: 0, tz: 0, level: 0, key: '0/0,0', distance: 0 };
const cell = { x: 0, y: 64, z: 0, state: 'signal', powerDbm: -75, technology: '4G',
    band: 'BAND_700', antenna: '-9223372036854775808', service: 'unavailable' };
const absent = { state: 'none', powerDbm: null, technology: null, band: null, antenna: null, service: 'unavailable' };
cell.technologies = { '2G': { ...absent }, '3G': { ...absent }, '4G': { ...cell }, '5G': { ...absent } };
const data = (overrides = {}) => ({ status: 'ready', revision: 'r1', level: 0, tileX: 0, tileZ: 0,
    originX: 0, originZ: 0, tileSize: 128, step: 32, height: 'surface', progress: 1,
    generatedAt: 10000, validForMs: 30000, maxRange: 4096, cells: [cell], ...overrides });
const response = value => ({ ok: true, status: 200, json: async () => value });

test('radio states use strict thresholds independently from service availability', () => {
    for (const [powerDbm, state] of [[-79, 'strong'], [-80, 'medium'], [-99, 'medium'],
        [-100, 'weak'], [-119, 'weak'], [-120, 'below'], [null, 'unknown']]) {
        assert.equal(signalState({ ...cell, powerDbm }), state);
    }
    assert.equal(signalState({ ...cell, state: 'none' }), 'none');
    assert.equal(signalState({ ...cell, state: 'unknown' }), 'unknown');
    assert.equal(signalState(cell, 'pending'), 'pending');
    assert.equal(signalState(cell), 'strong');
});

test('queries retain signed-long strings, negative tile coordinates and all filters', () => {
    const query = coverageQuery({ tx: -2, tz: 3 }, { step: 16, y: '-64',
        antenna: '-9223372036854775808', technology: '5G', band: 'N78' });
    assert.equal(query, '/api/coverage?tx=-2&tz=3&step=16&y=-64&antenna=-9223372036854775808&technology=all&band=N78&level=0');
    assert.deepEqual([coverageStep('auto', 0.25), coverageStep('auto', 1), coverageStep('auto', 4),
        coverageStep('16', 0.25)], [16, 16, 8, 16]);
});

test('viewport selection is center-first and bounded at 4K minimum zoom', () => {
    for (const budget of [49, 64]) {
        const tiles = visibleCoverageTiles({ width: 3840, height: 2160,
            pan: { x: 0.5, y: -512 }, zoom: 0.25 }, budget);
        assert.ok(tiles.length <= budget);
        assert.ok(tiles.every((t, i) => i === 0 || t.distance >= tiles[i - 1].distance));
    }
    const negative = visibleCoverageTiles({ width: 128, height: 128, pan: { x: 128, y: 128 }, zoom: 1 });
    assert.equal(negative.length, 16);
    assert.ok(negative.every(t => t.level === -2 && t.tx < 0 && t.tz < 0));
});

test('server options are the authority for vertical limits and band labels', () => {
    assert.equal(validateCoverageOptions(options), options);
    assert.throws(() => validateCoverageOptions({ ...options, minY: 400 }), /incompatibles/);
    assert.throws(() => validateCoverageOptions({ ...options, steps: [32] }), /incompatibles/);
    assert.throws(() => validateCoverageOptions({ ...options, modelRevision: 0 }), /incompatibles/);
    assert.throws(() => validateCoverageOptions({ ...options, maxLevel: 7 }), /incompatibles/);
    assert.throws(() => validateCoverageOptions({ ...options, steps: [16, 32, 64] }), /incompatibles/);
    for (const invalid of [{ minLevel: 0 }, { maxSamplesPerSide: 8 }, { sharedTechnologies: false },
        { technologies: ['4G', '5G'] }, { bands: [{ id: 'bad', label: 'bad', technology: '6G' }] }]) {
        assert.throws(() => validateCoverageOptions({ ...options, ...invalid }), /incompatibles/);
    }
});

test('radio LOD covers the entire rectangle and every corner at 4K/ultrawide minimum zoom', () => {
    for (const [width, height] of [[320, 640], [3840, 2160], [5120, 1440], [7680, 2160], [2160, 3840]]) {
        for (const zoom of [0.25, 0.5, 1, 2, 16, 32]) {
            for (const pan of [{ x: 0, y: 0 }, { x: 512.25, y: -1024.5 }, { x: -30000000, y: 30000000 }]) {
                for (const budget of [49, 64]) {
                    const tiles = visibleCoverageTiles({ width, height, zoom, pan }, budget);
                    const level = tiles[0].level, size = coverageTileSize(level) * zoom;
                    assert.ok(tiles.length <= budget);
                    const keys = new Set(tiles.map(t => t.key));
                    const minX = Math.floor(-pan.x / size), maxX = Math.ceil((width - pan.x) / size) - 1;
                    const minZ = Math.floor(-pan.y / size), maxZ = Math.ceil((height - pan.y) / size) - 1;
                    assert.equal(tiles.length, (maxX - minX + 1) * (maxZ - minZ + 1));
                    for (let x = minX; x <= maxX; x++) {
                        for (let z = minZ; z <= maxZ; z++) assert.ok(keys.has(`${level}/${x},${z}`));
                    }
                    for (const x of [0, width - 0.001]) {
                        for (const z of [0, height - 0.001]) {
                            assert.ok(keys.has(`${level}/${Math.floor((x - pan.x) / size)},${Math.floor((z - pan.y) / size)}`));
                        }
                    }
                    for (const value of ['auto', '1', '8', '16']) {
                        const step = coverageStep(value, zoom, level);
                        assert.ok(Number.isInteger(coverageTileSize(level) / step));
                        assert.ok(coverageTileSize(level) / step <= 16);
                        assert.ok(coverageTileSize(level) / step >= 1);
                        assert.ok(options.steps.includes(step));
                    }
                }
            }
        }
    }
    assert.equal(coverageStep('16', 0.25, 2), 32);
    assert.equal(coverageStep('16', 0.25, 3), 64);
});

test('all LOD responses validate scaled negative origins, bounded steps and level identity', async () => {
    for (let level = -3; level <= 6; level++) {
        const tileSize = coverageTileSize(level), step = coverageStep('1', 32, level);
        const t = { tx: -2, tz: -1, level, key: `${level}/-2,-1` };
        const f = { ...filters, step };
        const valid = data({ level, tileSize, step, tileX: -2, tileZ: -1,
            originX: -2 * tileSize, originZ: -tileSize,
            cells: [{ ...cell, x: -2 * tileSize + step / 2, z: -tileSize + step / 2 }] });
        for (const overrides of [null, { level: level + 1 }, { tileSize: tileSize * 2 }, { originX: 0 },
            { step: step * 2 }, { cells: Array((tileSize / step) ** 2 + 1).fill(valid.cells[0]) }, { level: undefined }]) {
            const store = new CoverageStore(async path => {
                const query = new URL(path, 'http://localhost').searchParams;
                assert.equal(query.get('level'), String(level));
                assert.equal(query.get('step'), String(step));
                return response({ ...valid, ...overrides });
            }, () => 10000);
            store.options = options;
            await store.tick([t], f);
            assert.equal(!!store.ready(t), overrides === null);
        }
    }
    for (const step of [1, 2, 48, 256, 4096]) {
        const store = new CoverageStore(async () => response(data({ step })), () => 10000);
        store.options = options;
        await store.tick([tile], { ...filters, step });
        assert.equal(store.ready(tile), null);
    }
});

test('LOD changes retain sixteen pending jobs, their original step and progress through HTTP 202', async () => {
    let now = 10000, status = 'pending';
    const requests = [];
    const store = new CoverageStore(async path => {
        requests.push(path);
        const q = new URL(path, 'http://localhost').searchParams;
        if (status === 'http202') return { status: 202 };
        const level = Number(q.get('level')), tx = Number(q.get('tx')), tz = Number(q.get('tz'));
        const tileSize = coverageTileSize(level);
        return response(data({ status, progress: status === 'pending' ? 0.5 : 1, level, tileSize,
            tileX: tx, tileZ: tz, originX: tx * tileSize, originZ: tz * tileSize,
            step: Number(q.get('step')), cells: [] }));
    }, () => now);
    store.options = options;
    const oldTiles = Array.from({ length: 16 }, (_, tx) => ({ level: 2, tx, tz: 0, key: `2/${tx},0` }));
    const oldFilters = { ...filters, precision: '16', level: 2, step: 64 };
    for (let i = 0; i < 16; i++) await store.tick(oldTiles, oldFilters);
    const generation = store.generation;
    const newTile = { level: 3, tx: 0, tz: 0, key: '3/0,0' };
    const newFilters = { ...oldFilters, level: 3, step: 128 };
    await store.tick([newTile], newFilters);
    assert.equal(requests.length, 16);
    assert.equal(store.generation, generation);
    now += 1000;
    status = 'http202';
    await store.tick([newTile], newFilters);
    assert.equal(store.cache.get(store.key(oldTiles[0], oldFilters)).data.progress, 0.5);
    assert.ok(requests.at(-1).includes('step=64'));
    assert.ok(requests.at(-1).endsWith('level=2'));
    now += 1000;
    status = 'ready';
    for (let i = 0; i < 16; i++) await store.tick([newTile], newFilters);
    assert.equal([...store.cache.values()].filter(e => e.pending || e.data.status === 'pending').length, 0);
    await store.tick([newTile], newFilters);
    assert.ok(store.ready(newTile));
    assert.ok(store.cache.has(store.key(oldTiles[0], oldFilters)) && store.entry(newTile));
    store.select({ ...newFilters, band: 'BAND_700' });
    assert.equal(store.cache.size, 17);
    assert.equal(store.ready(newTile), null);
});

test('auto step changes never draw old samples but finish and replace same-level jobs', async () => {
    let now = 10000, pending = true;
    const store = new CoverageStore(async path => response(data({ step: Number(new URL(path, 'http://localhost').searchParams.get('step')),
        status: pending ? 'pending' : 'ready' })), () => now);
    store.options = options;
    await store.tick([tile], { ...filters, precision: 'auto' });
    now += 1000;
    pending = false;
    const next = { ...filters, step: 16, precision: 'auto' };
    await store.tick([tile], next);
    assert.equal(store.cache.get(store.key(tile, filters)).data.step, 32);
    assert.equal(store.ready(tile), null);
    await store.tick([tile], next);
    assert.equal(store.ready(tile).step, 16);
});

test('partial pending data is hidden, polls after one second, then expires after thirty seconds', async () => {
    let now = 10000, calls = 0;
    const store = new CoverageStore(async () => response(++calls === 1 ? data({ status: 'pending', progress: 0.5 })
        : data({ generatedAt: now })), () => now);
    store.options = options;
    await store.tick([tile], filters);
    assert.equal(store.ready(tile), null);
    assert.deepEqual(store.entry(tile).data.cells, []);
    now += 999;
    await store.tick([tile], filters);
    assert.equal(calls, 1);
    now++;
    await store.tick([tile], filters);
    assert.equal(store.ready(tile).cells[0].service, 'unavailable');
    now += 30000;
    assert.equal(store.ready(tile), null);
    await store.tick([tile], filters);
    assert.equal(calls, 3);
});

test('only one request can run, and changed filters or sessions reject old responses', async () => {
    let release, calls = 0;
    const store = new CoverageStore(() => { calls++; return new Promise(resolve => { release = resolve; }); }, () => 10000);
    store.options = options;
    const first = store.tick([tile], filters);
    await store.tick([tile], filters);
    store.invalidate(true);
    await store.tick([tile], { ...filters, technology: '5G' });
    assert.equal(calls, 1);
    release(response(data()));
    await first;
    assert.equal(store.cache.size, 0);
    assert.equal(store.options, null);
    assert.equal(store.busy, false);
});

test('cache stays at 128 entries with distinct tile revisions and selections are isolated', async () => {
    const store = new CoverageStore(async path => {
        const tx = Number(new URL(path, 'http://localhost').searchParams.get('tx'));
        return response(data({ tileX: tx, originX: tx * 128, cells: [], revision: String(tx) }));
    }, () => 10000);
    store.options = options;
    for (let tx = 0; tx < 160; tx++) await store.tick([{ ...tile, tx, key: `0/${tx},0` }], filters);
    assert.equal(store.cache.size, 128);
    assert.equal(store.entry(tile), undefined);
    store.select({ ...filters, band: 'BAND_700' });
    assert.equal(store.cache.size, 128);
    assert.equal(store.ready({ ...tile, tx: 159 }), null);
});

test('two tiles with different job revisions coexist', async () => {
    const other = { ...tile, tx: 1, key: '0/1,0' };
    const store = new CoverageStore(async path => {
        const tx = Number(new URL(path, 'http://localhost').searchParams.get('tx'));
        return response(data({ tileX: tx, originX: tx * 128, revision: String(tx + 1), cells: [] }));
    }, () => 10000);
    store.options = options;
    await store.tick([tile], filters);
    await store.tick([other], filters);
    assert.equal(store.cache.size, 2);
    assert.equal(store.ready(tile).revision, '1');
    assert.equal(store.ready(other).revision, '2');
});

test('regenerating an expired tile replaces its pending/ready job without touching another tile', async () => {
    let now = 10000;
    let nextData = data({ revision: '1' });
    const other = { ...tile, tx: 1, key: '0/1,0' };
    const store = new CoverageStore(async () => response(nextData), () => now);
    store.options = options;
    await store.tick([tile], filters);
    now = 12000;
    nextData = data({ tileX: 1, originX: 128, revision: '2', generatedAt: now, cells: [] });
    await store.tick([other], filters);
    const otherEntry = store.entry(other);
    now = 40000;
    assert.equal(store.ready(tile), null);
    nextData = data({ revision: '3', status: 'pending', progress: 0.25, generatedAt: now });
    await store.tick([tile], filters);
    assert.equal(store.entry(tile).data.revision, '3');
    assert.equal(store.entry(tile).data.progress, 0.25);
    assert.deepEqual(store.entry(tile).data.cells, []);
    assert.equal(store.ready(tile), null);
    now = 41000;
    nextData = data({ revision: '3', generatedAt: now, cells: [{ ...cell, powerDbm: -105 }] });
    await store.tick([tile], filters);
    assert.equal(store.ready(tile).revision, '3');
    assert.equal(store.ready(tile).progress, 1);
    assert.equal(store.ready(tile).cells[0].powerDbm, -105);
    assert.equal(store.cache.size, 2);
    assert.equal(store.entry(other), otherEntry);
    assert.equal(store.ready(other).revision, '2');
});

test('429/503 respect bounded Retry-After/backoff and stop after four failures', async () => {
    let now = 10000, calls = 0;
    const store = new CoverageStore(async () => ({ ok: false, status: ++calls % 2 ? 429 : 503,
        headers: new Headers({ 'Retry-After': '2' }) }), () => now);
    for (let attempt = 1; attempt <= 4; attempt++) {
        await store.tick([tile], filters);
        assert.equal(calls, attempt);
        await store.tick([tile], filters);
        assert.equal(calls, attempt);
        now = store.retryAt;
    }
    assert.equal(store.stopped, true);
    now += 60000;
    await store.tick([tile], filters);
    assert.equal(calls, 4);
    store.invalidate();
    await store.tick([tile], filters);
    assert.equal(calls, 5);
    assert.equal(retryDelay('999999', 1, now), Infinity);
    assert.equal(retryDelay(new Date(now + 5000).toUTCString(), 1, now), 5000);
    assert.equal(retryDelay(null, 3, now), 4000);
});

test('long server cooldowns suspend retries rather than violate Retry-After', async () => {
    let calls = 0;
    const store = new CoverageStore(async () => { calls++; return { ok: false, status: 503,
        headers: new Headers({ 'Retry-After': '120' }) }; }, () => 10000);
    await store.tick([tile], filters);
    assert.equal(store.stopped, true);
    await store.tick([tile], filters);
    assert.equal(calls, 1);
});

test('invalid coordinates and numeric antenna IDs cannot appear as real samples', async () => {
    const store = new CoverageStore(async () => response(data({ cells: [cell,
        { ...cell, x: 128 }, { ...cell, antenna: 9223372036854775807 }] })), () => 10000);
    store.options = options;
    await store.tick([tile], filters);
    assert.equal(store.ready(tile).cells.length, 1);
});

test('expired server validity does not get a fresh thirty-second lifetime', async () => {
    const store = new CoverageStore(async () => response(data({ generatedAt: 6000, validForMs: 0 })), () => 10000);
    store.options = options;
    await store.tick([tile], filters);
    assert.equal(store.ready(tile), null);
});

test('remaining server validity works even when browser and server clocks differ', async () => {
    let now = 10000;
    const store = new CoverageStore(async () => response(data({ generatedAt: 1000, validForMs: 700 })), () => now);
    store.options = options;
    await store.tick([tile], filters);
    assert.ok(store.ready(tile));
    now = 10700;
    assert.equal(store.ready(tile), null);
});

test('remaining TTL is capped at thirty seconds without relying on generatedAt', async () => {
    let now = 10000;
    const store = new CoverageStore(async () => response(data({ generatedAt: 999999999, validForMs: 60000 })), () => now);
    store.options = options;
    await store.tick([tile], filters);
    now += 29999;
    assert.ok(store.ready(tile));
    now++;
    assert.equal(store.ready(tile), null);
});

test('a due pending job is polled before twenty unseen tiles, even outside the viewport', async () => {
    for (const visiblePending of [true, false]) {
        let now = 10000;
        const requests = [];
        const store = new CoverageStore(async path => {
            requests.push(path);
            return response(data({ status: requests.length === 1 ? 'pending' : 'ready' }));
        }, () => now);
        store.options = options;
        await store.tick([tile], filters);
        const unseen = Array.from({ length: 20 }, (_, i) => ({ tx: i + 1, tz: 0, key: `0/${i + 1},0`, distance: i }));
        now += 1000;
        await store.tick(visiblePending ? [...unseen, { ...tile, distance: 100 }] : unseen, filters);
        assert.equal(requests.length, 2);
        assert.equal(requests[1], requests[0]);
        assert.ok(store.ready(tile));
    }
});

test('a sixteen-job backend callback barrier drains without over-admitting new work', async () => {
    let now = 10000, calls = 0, peak = 0, completed = 0;
    const callbacks = new Map(), ready = new Set();
    const tiles = Array.from({ length: 49 }, (_, tx) => ({ tx, tz: 0, key: `0/${tx},0`, distance: tx }));
    const store = new CoverageStore(async path => {
        calls++;
        const tx = Number(new URL(path, 'http://localhost').searchParams.get('tx'));
        let status = 'pending';
        if (ready.has(tx)) {
            callbacks.delete(tx);
            ready.delete(tx);
            completed++;
            status = 'ready';
        } else if (!callbacks.has(tx)) {
            assert.ok(callbacks.size < 16, 'must poll/drain existing jobs before backend capacity is exceeded');
            callbacks.set(tx, () => ready.add(tx));
            peak = Math.max(peak, callbacks.size);
        }
        return response(data({ tileX: tx, originX: tx * 128, cells: [], status, revision: String(tx) }));
    }, () => now);
    store.options = options;
    for (let i = 0; i < 49; i++) await store.tick(tiles, filters);
    assert.equal(calls, 16);
    assert.equal(peak, 16);
    for (const callback of callbacks.values()) callback();
    now += 1000;
    for (let i = 0; i < 16; i++) await store.tick(tiles, filters);
    assert.equal(completed, 16);
    assert.equal(callbacks.size, 0);
    assert.equal(store.stopped, false);
    assert.equal(store.failures, 0);
    await store.tick(tiles, filters);
    assert.equal(callbacks.size, 1);
});

test('a five-request-per-second viewport starts producing ready tiles before the fifteen-second timeout', async () => {
    let now = 10000, firstReadyAt = null, overloads = 0;
    const jobs = new Map();
    const tiles = Array.from({ length: 49 }, (_, tx) => ({ tx, tz: 0, key: `0/${tx},0`, distance: tx }));
    const store = new CoverageStore(async path => {
        if (path.endsWith('/options')) return response(options);
        const tx = Number(new URL(path, 'http://localhost').searchParams.get('tx'));
        if (!jobs.has(tx)) {
            if (jobs.size >= 16) {
                overloads++;
                return { ok: false, status: 503, headers: new Headers({ 'Retry-After': '1' }) };
            }
            jobs.set(tx, now);
        }
        const status = now - jobs.get(tx) >= 1000 ? 'ready' : 'pending';
        if (status === 'ready') jobs.delete(tx);
        return response(data({ tileX: tx, originX: tx * 128, cells: [], status, revision: String(tx) }));
    }, () => now);
    for (let tick = 0; tick < 75; tick++, now += 200) {
        await store.tick(tiles, filters);
        if (firstReadyAt === null && tiles.some(t => store.ready(t))) firstReadyAt = now;
    }
    assert.ok(firstReadyAt !== null && firstReadyAt - 10000 <= 2000);
    assert.ok(tiles.filter(t => store.ready(t)).length >= 25);
    assert.equal(overloads, 0);
    assert.equal(store.stopped, false);
});

test('after a 503 cooldown, existing pending jobs resume before failed admissions', async () => {
    let now = 10000;
    const requests = [];
    const store = new CoverageStore(async path => {
        requests.push(path);
        if (requests.length === 2) return { ok: false, status: 503, headers: new Headers({ 'Retry-After': '1' }) };
        return response(data({ status: requests.length === 1 ? 'pending' : 'ready' }));
    }, () => now);
    store.options = options;
    const tiles = [tile, { ...tile, tx: 1, key: '0/1,0' }];
    await store.tick(tiles, filters);
    now += 200;
    await store.tick(tiles, filters);
    assert.equal(store.failures, 1);
    now = store.retryAt;
    await store.tick(tiles, filters);
    assert.equal(requests[2], requests[0]);
    assert.ok(store.ready(tile));
    assert.equal(store.failures, 0);
    assert.equal(store.stopped, false);
});

test('expired ready tiles and unseen tiles alternate rather than starving either group', async () => {
    const requests = [];
    const store = new CoverageStore(async path => {
        const tx = Number(new URL(path, 'http://localhost').searchParams.get('tx'));
        requests.push(tx);
        return response(data({ tileX: tx, originX: tx * 128, cells: [] }));
    }, () => 10000);
    store.options = options;
    store.select(filters);
    const tiles = Array.from({ length: 4 }, (_, tx) => ({ tx, tz: 0, key: `0/${tx},0`, distance: tx }));
    for (const t of tiles.slice(0, 2)) store.cache.set(store.key(t), { data: data({ tileX: t.tx }), expires: 0, nextAt: 0 });
    for (let i = 0; i < 4; i++) await store.tick(tiles, filters);
    assert.deepEqual(requests, [2, 0, 3, 1]);
});

test('options poll every two seconds and only modelRevision changes invalidate cached tiles', async () => {
    let now = 10000, modelRevision = '0';
    const requests = [];
    const store = new CoverageStore(async path => {
        requests.push(path);
        if (path.endsWith('/options')) return response({ ...options, modelRevision });
        const tx = Number(new URL(path, 'http://localhost').searchParams.get('tx'));
        return response(data({ tileX: tx, originX: tx * 128, cells: [], revision: String(tx + 1) }));
    }, () => now);
    await store.tick([tile], filters);
    const originalOptions = store.options;
    await store.tick([tile], filters);
    await store.tick([{ ...tile, tx: 1, key: '0/1,0' }], filters);
    assert.equal(store.cache.size, 2);
    now += 1999;
    await store.tick([], filters);
    assert.equal(requests.length, 3);
    now++;
    await store.tick([], filters);
    assert.equal(requests.at(-1), '/api/coverage/options');
    assert.equal(store.options, originalOptions);
    assert.equal(store.cache.size, 2);
    const generation = store.generation, selection = store.viewFilters;
    now += 2000;
    modelRevision = '1';
    await store.tick([], filters);
    assert.equal(store.cache.size, 0);
    assert.equal(store.options.modelRevision, '1');
    assert.equal(store.viewFilters, selection);
    assert.equal(store.generation, generation + 1);
    await store.tick([tile], filters);
    assert.equal(store.ready(tile).revision, '1');
});

test('stale snapshot revisions are hidden, refresh options once, and recover without errors or a polling loop', async () => {
    for (const status of ['ready', 'pending']) {
        let now = 10000, stale = true;
        const requests = [];
        const store = new CoverageStore(async path => {
            requests.push({ path, at: now });
            return response(path.endsWith('/options') ? { ...options, modelRevision: 'current' }
                : data({ modelRevision: stale ? 'stale-preparation' : 'current', status: stale ? status : 'ready' }));
        }, () => now);
        await store.tick([tile], filters);
        await store.tick([tile], filters);
        assert.equal(store.ready(tile), null);
        assert.equal(store.entry(tile).data, undefined, 'no stale measurements or projected cells enter the cache');
        assert.equal(store.nextOptionsAt, now);
        await store.tick([tile], filters);
        assert.equal(requests.at(-1).path, '/api/coverage/options');
        assert.equal(store.nextOptionsAt, now + 2000);
        for (let i = 0; i < 100; i++) await store.tick([tile], filters);
        assert.equal(requests.length, 3, 'no immediate options/tile retry loop');
        for (let i = 1; i <= 25; i++) {
            now += 200;
            await store.tick([tile], { ...filters, technology: i % 2 ? '4G' : '5G' });
            assert.equal(store.ready(tile), null);
            assert.equal(store.failures, 0);
            assert.equal(store.retryAt, 0);
            assert.equal(store.stopped, false);
        }
        const optionReads = requests.filter(r => r.path.endsWith('/options'));
        assert.ok(optionReads.length <= 4, 'repeated stale responses retain the normal two-second options poll');
        for (let i = 2; i < optionReads.length; i++) assert.ok(optionReads[i].at - optionReads[i - 1].at >= 2000);
        const tileReads = requests.filter(r => !r.path.endsWith('/options'));
        for (let i = 1; i < tileReads.length; i++) assert.ok(tileReads[i].at - tileReads[i - 1].at >= 1000);
        stale = false;
        for (let i = 0; i < 10 && !store.ready(tile); i++) {
            now += 200;
            await store.tick([tile], filters);
        }
        assert.equal(store.ready(tile).modelRevision, 'current');
        assert.equal(store.entry(tile).expires, now + 30000);
        assert.equal(store.failures, 0);
    }
});

test('options older than the response catch up through normal polling without accepting mismatched snapshots', async () => {
    let now = 10000, optionsRevision = '9';
    const requests = [];
    const store = new CoverageStore(async path => {
        requests.push(path);
        return response(path.endsWith('/options') ? { ...options, modelRevision: optionsRevision }
            : data({ modelRevision: '10' }));
    }, () => now);
    await store.tick([tile], filters);
    await store.tick([tile], filters);
    await store.tick([tile], filters);
    assert.equal(requests.at(-1), '/api/coverage/options');
    assert.equal(store.options.modelRevision, '9', 'identities are opaque, never ordered or copied from a tile');
    assert.equal(store.ready(tile), null);
    now += 1000;
    await store.tick([tile], filters);
    const count = requests.length;
    await store.tick([tile], filters);
    assert.equal(requests.length, count);
    assert.equal(store.failures, 0);
    assert.equal(store.stopped, false);
    optionsRevision = '10';
    now += 1000;
    // A due pending job may take its normal turn before the options poll.
    for (let i = 0; i < 3; i++) await store.tick([tile], filters);
    assert.equal(store.options.modelRevision, '10');
    assert.equal(store.ready(tile).modelRevision, '10');
    assert.equal(store.failures, 0);
    assert.equal(store.stopped, false);
    const recovered = requests.length;
    for (let i = 0; i < 100; i++) await store.tick([tile], filters);
    assert.equal(requests.length, recovered);
    now += 2000;
    await store.tick([tile], filters);
    assert.equal(requests.at(-1), '/api/coverage/options');
    assert.equal(requests.length, recovered + 1);
});

test('a mismatched refresh discards previous projections without renewing TTL or touching other tiles', async () => {
    const other = { ...tile, tx: 1 };
    let mismatch = false;
    const store = new CoverageStore(async path => {
        const tx = Number(new URL(path, 'http://localhost').searchParams.get('tx'));
        return response(data({ modelRevision: mismatch ? 'stale' : '0', tileX: tx, originX: tx * 128,
            cells: [{ ...cell, x: tx * 128 }] }));
    }, () => 10000);
    store.options = options;
    const selected = { ...filters, technology: '4G' };
    await store.tick([tile], selected);
    await store.tick([other], selected);
    assert.ok(store.ready(tile));
    const unrelated = store.entry(other);
    store.entry(tile).nextAt = 0;
    mismatch = true;
    await store.tick([tile], selected);
    assert.equal(store.ready(tile), null);
    assert.equal(store.entry(tile).data, undefined);
    assert.equal(store.entry(tile).projected, undefined);
    assert.equal(store.entry(tile).expires, undefined);
    assert.equal(store.entry(other), unrelated);
    assert.ok(store.ready(other));
    assert.equal(store.failures, 0);
    store.options = { ...options, modelRevision: 'new' };
    assert.equal(store.ready(other), null, 'readiness independently guards against a changed model revision');
});

test('snapshot modelRevision is optional, but must be a matching string when supplied', async () => {
    for (const modelRevision of [undefined, '0', null, 0]) {
        const store = new CoverageStore(async () => response(data({ modelRevision })), () => 10000);
        store.options = options;
        await store.tick([tile], filters);
        assert.equal(!!store.ready(tile), modelRevision === undefined || modelRevision === '0');
    }
});

test('revision recovery prioritizes options over due jobs without bypassing Retry-After', async () => {
    let now = 10000, deferOptions = false;
    const requests = [];
    const store = new CoverageStore(async path => {
        requests.push({ path, at: now });
        if (path.endsWith('/options')) return deferOptions ? { status: 202, headers: new Headers({ 'Retry-After': '2' }) }
            : response(options);
        return response(data({ modelRevision: 'stale' }));
    }, () => now);
    await store.tick([], filters);
    deferOptions = true;
    now += 2000;
    await store.tick([], filters);
    assert.equal(store.nextOptionsAt, now + 2000);
    await store.tick([tile], filters);
    assert.equal(store.nextOptionsAt, now + 2000);
    for (let i = 0; i < 9; i++) { now += 200; await store.tick([tile], filters); }
    assert.equal(requests.filter(r => r.path.endsWith('/options')).length, 2);
    now += 200;
    await store.tick([tile], filters);
    assert.equal(requests.at(-1).path, '/api/coverage/options');
    assert.equal(requests.at(-1).at, 14000);
    assert.equal(store.failures, 0);
    assert.equal(store.stopped, false);
});

test('due options get one turn without starving due pending jobs on subsequent ticks', async () => {
    let now = 10000;
    const requests = [];
    const store = new CoverageStore(async path => {
        requests.push(path);
        if (path.endsWith('/options')) return response(options);
        const tx = Number(new URL(path, 'http://localhost').searchParams.get('tx'));
        return response(data({ tileX: tx, originX: tx * 128, cells: [], status: 'pending' }));
    }, () => now);
    const tiles = [tile, { ...tile, tx: 1, key: '0/1,0' }];
    await store.tick(tiles, filters);
    await store.tick(tiles, filters);
    await store.tick(tiles, filters);
    now += 2000;
    await store.tick(tiles, filters);
    assert.ok(requests.at(-1).startsWith('/api/coverage?'));
    await store.tick(tiles, filters);
    assert.equal(requests.at(-1), '/api/coverage/options');
    await store.tick(tiles, filters);
    assert.ok(requests.at(-1).startsWith('/api/coverage?'));
    const count = requests.length;
    await store.tick(tiles, filters);
    assert.equal(requests.length, count);
});

test('options and tile body decoding share one busy barrier and reject obsolete model/session responses', async () => {
    let now = 10000, release, calls = 0;
    const store = new CoverageStore(async () => {
        calls++;
        return { ok: true, status: 200, json: () => new Promise(resolve => { release = resolve; }) };
    }, () => now);
    const initial = store.tick([tile], filters);
    await Promise.resolve();
    await store.tick([tile], filters);
    assert.equal(calls, 1);
    release(options);
    await initial;
    const reading = store.tick([tile], filters);
    await Promise.resolve();
    now += 2000;
    await store.tick([tile], filters);
    assert.equal(calls, 2);
    store.options = { ...options, modelRevision: '1' };
    release(data());
    await reading;
    assert.equal(store.cache.size, 0);
    const polling = store.tick([tile], filters);
    await Promise.resolve();
    store.invalidate();
    release({ ...options, modelRevision: 'stale' });
    await polling;
    assert.equal(store.options.modelRevision, '1');
    assert.equal(store.cache.size, 0);
});

test('HTTP 202 options retry for minutes without parsing bodies or counting failures', async () => {
    let now = 10000, paused = true, calls = 0;
    const store = new CoverageStore(async () => {
        calls++;
        return paused ? { status: 202, headers: new Headers({ 'Retry-After': '1' }),
            json() { throw new Error('pending is not options'); } } : response(options);
    }, () => now);
    for (let i = 0; i < 1500; i++, now += 200) {
        await store.tick([tile], filters);
        assert.equal(store.failures, 0);
        assert.equal(store.stopped, false);
        assert.equal(store.cache.size, 0);
        assert.equal(store.options, null);
    }
    assert.equal(calls, 300);
    assert.match(store.message, /pause ou occupe/);
    paused = false;
    await store.tick([tile], filters);
    assert.equal(store.options, options);
});

test('HTTP 202 before tile data keeps bounded due jobs through five minutes and resumes with remaining TTL', async () => {
    let now = 10000, paused = true;
    const requests = [];
    const store = new CoverageStore(async path => {
        requests.push({ path, at: now });
        if (path.endsWith('/options')) return response(options);
        const tx = Number(new URL(path, 'http://localhost').searchParams.get('tx'));
        return paused ? { status: 202, headers: new Headers({ 'Retry-After': '1' }),
            json() { throw new Error('pending is not a tile'); } }
            : response(data({ tileX: tx, originX: tx * 128, cells: [], validForMs: 700 }));
    }, () => now);
    const tiles = Array.from({ length: 49 }, (_, tx) => ({ tx, tz: 0, key: `0/${tx},0`, distance: tx }));
    for (let i = 0; i < 1500; i++, now += 200) {
        await store.tick(tiles, filters);
        assert.equal(store.failures, 0);
        assert.equal(store.stopped, false);
        assert.ok(store.cache.size <= 16);
        assert.ok([...store.cache.values()].every(entry => entry.pending && !entry.data));
        assert.ok(tiles.every(t => store.ready(t) === null));
    }
    const lastAt = new Map();
    for (const request of requests) {
        if (lastAt.has(request.path)) assert.ok(request.at - lastAt.get(request.path) >= 1000);
        lastAt.set(request.path, request.at);
    }
    const due = [...store.cache].filter(([, entry]) => entry.nextAt <= now);
    assert.ok(due.length > 0);
    paused = false;
    await store.tick(tiles, filters);
    if (!tiles.some(t => store.ready(t))) {
        now += 200;
        await store.tick(tiles, filters);
    }
    const ready = tiles.find(t => store.ready(t));
    assert.ok(ready, 'due jobs recover instead of stopping after four pending responses');
    assert.equal(store.entry(ready).expires, now + 700);
    now += 700;
    assert.equal(store.ready(ready), null);
});

test('HTTP 202 does not renew an existing tile TTL or discard partial job progress', async () => {
    let now = 10000, pending = false;
    const store = new CoverageStore(async () => pending
        ? { status: 202, headers: new Headers({ 'Retry-After': '1' }) }
        : response(data({ validForMs: 700 })), () => now);
    store.options = options;
    await store.tick([tile], filters);
    const original = store.entry(tile);
    now += 1000;
    pending = true;
    await store.tick([tile], filters);
    assert.equal(store.entry(tile).data, original.data);
    assert.equal(store.entry(tile).expires, original.expires);
    assert.equal(store.ready(tile), null);
    assert.equal(store.failures, 0);
    now += 1000;
    pending = false;
    await store.tick([tile], filters);
    assert.ok(store.ready(tile));
});

test('Retry-After handles seconds, dates, invalid values and the thirty-second suspension boundary', () => {
    const now = Date.UTC(2026, 8, 15, 12);
    for (const value of [null, '', ' ', 'invalid', '-1', '0', '0.1']) assert.equal(retryDelay(value, 1, now), 1000);
    assert.ok(Math.abs(retryDelay('1.001', 1, now) - 1001) < 0.001);
    assert.equal(retryDelay('30', 1, now), 30000);
    assert.equal(retryDelay('30.001', 1, now), Infinity);
    assert.equal(retryDelay(new Date(now + 30000).toUTCString(), 1, now), 30000);
    assert.equal(retryDelay(new Date(now + 31000).toUTCString(), 1, now), Infinity);
    assert.equal(retryDelay(new Date(now - 1000).toUTCString(), 1, now), 1000);
});

test('4K at zoom 32 can sample every block; awkward alignment adapts without truncating the viewport', () => {
    const view = { width: 3840, height: 2160, zoom: 32, pan: { x: 1920, y: 1080 } };
    const tiles = visibleCoverageTiles(view);
    assert.equal(tiles[0].level, -3);
    assert.equal(tiles.length, 48);
    for (const precision of ['auto', '1', '8', '16']) {
        assert.equal(coverageStep(precision, 32, -3), precision === 'auto' ? 1 : Number(precision));
    }
    const shifted = visibleCoverageTiles({ ...view, pan: { x: 100, y: 100 } });
    assert.ok(shifted.length <= 49);
    assert.equal(coverageStep('1', 32, shifted[0].level), 8);
    assert.equal(coverageStep('1', 32, -2), 8, 'never invent the unsupported 2-block step');
    assert.equal(coverageStep('1', 32, -1), 8, 'never invent the unsupported 4-block step');
    assert.equal(coverageStep('auto', 4, -3), 8);
    assert.equal(coverageStep('auto', 1, -3), 16);
});

test('negative tiles accept 1/8/16 steps and 256 cells but reject overflow', async () => {
    for (const step of [1, 8, 16]) {
        const t = { tx: -2, tz: -3, level: -3 };
        const cells = Array.from({ length: (16 / step) ** 2 }, (_, i) => ({ ...cell,
            x: -32 + i % (16 / step) * step + Math.floor(step / 2),
            z: -48 + Math.floor(i / (16 / step)) * step + Math.floor(step / 2) }));
        for (const overflow of [false, true]) {
            const store = new CoverageStore(async path => {
                const q = new URL(path, 'http://localhost').searchParams;
                assert.equal(q.get('level'), '-3');
                assert.equal(q.get('step'), String(step));
                return response(data({ level: -3, tileX: -2, tileZ: -3, originX: -32, originZ: -48,
                    tileSize: 16, step, cells: overflow ? [...cells, cells[0]] : cells }));
            }, () => 10000);
            store.options = options;
            await store.tick([t], { ...filters, step });
            assert.equal(store.ready(t)?.cells.length ?? 0, overflow ? 0 : cells.length);
        }
    }
});

test('2G/4G/5G switches project independent shared measurements once with no new fetch or TTL renewal', async () => {
    let now = 10000;
    const requests = [];
    const technologies = { ...cell.technologies,
        '2G': { ...cell.technologies['4G'], technology: '2G', band: 'GSM', powerDbm: -60 },
        '5G': { ...cell.technologies['4G'], technology: '5G', band: 'N78', powerDbm: -115 } };
    const dominant = { ...cell, ...technologies['5G'], technologies };
    const store = new CoverageStore(async path => { requests.push(path); return response(data({ cells: [dominant] })); }, () => now);
    store.options = options;
    await store.tick([tile], { ...filters, technology: '4G' });
    const entry = store.entry(tile), generation = store.generation, four = store.ready(tile);
    assert.equal(four.cells[0].powerDbm, -75);
    assert.equal(new URL(requests[0], 'http://localhost').searchParams.get('technology'), 'all');
    for (const technology of ['5G', '2G', '3G', '4G', 'all', '5G', '2G', '4G']) {
        now += 100;
        await store.tick([tile], { ...filters, technology });
        const projected = store.ready(tile);
        assert.equal(projected.cells[0].technology, technology === 'all' ? '5G' : technology === '3G' ? null : technology);
        assert.equal(projected.cells[0].state, technology === '3G' ? 'none' : 'signal');
        assert.equal(projected.cells[0].x, dominant.x);
        assert.equal(projected.cells[0].y, dominant.y);
        assert.equal(projected.cells[0].z, dominant.z);
        for (let i = 0; i < 100; i++) assert.equal(store.ready(tile), projected);
    }
    assert.equal(store.ready(tile), four);
    assert.equal(store.entry(tile), entry);
    assert.equal(store.generation, generation);
    assert.equal(entry.expires, 40000);
    assert.equal(requests.length, 1);
    assert.equal(entry.data.cells[0], dominant);
    now = 40000;
    assert.equal(store.ready(tile), null);
});

test('technology switches during decoding reuse the same pending physical request', async () => {
    let release, calls = 0;
    const store = new CoverageStore(async () => {
        calls++;
        return { status: 200, json: () => new Promise(resolve => { release = resolve; }) };
    }, () => 10000);
    store.options = options;
    const pending = store.tick([tile], { ...filters, technology: '4G' });
    await Promise.resolve();
    const generation = store.generation;
    await store.tick([tile], { ...filters, technology: '5G' });
    release(data());
    await pending;
    assert.equal(calls, 1);
    assert.equal(store.generation, generation);
    assert.equal(store.ready(tile).cells[0].state, 'none');
    await store.tick([tile], { ...filters, technology: '4G' });
    assert.equal(store.ready(tile).cells[0].powerDbm, -75);
    assert.equal(calls, 1);
});

test('band, antenna, height and geometry partition the cache, not technology or precision labels', async () => {
    let calls = 0;
    const store = new CoverageStore(async path => {
        calls++;
        const q = new URL(path, 'http://localhost').searchParams;
        return response(data({ height: q.get('y'), step: Number(q.get('step')), cells: [] }));
    }, () => 10000);
    store.options = options;
    const selections = [filters, { ...filters, band: 'BAND_700' }, { ...filters, y: '-64' },
        { ...filters, antenna: '-9223372036854775808' }, { ...filters, step: 16 }];
    for (const selection of selections) {
        await store.tick([tile], selection);
        assert.ok(store.ready(tile));
    }
    assert.equal(calls, 5);
    assert.equal(store.cache.size, 5);
    for (const selection of selections) {
        await store.tick([tile], { ...selection, technology: '5G', precision: 'auto' });
        assert.ok(store.ready(tile));
    }
    assert.equal(calls, 5);
});

test('shared radio payloads reject mismatched bands, technologies and unknown states without inventing signal', async () => {
    const unknown = { ...absent, state: 'unknown', service: 'unknown' };
    const clean = { ...cell, technologies: { ...cell.technologies, '5G': unknown } };
    const badRadios = [{ ...unknown, powerDbm: -80 }, { ...unknown, service: 'available' },
        { ...unknown, band: 'N78' }, { ...cell.technologies['4G'], technology: '5G' },
        { ...cell.technologies['4G'], technology: '6G' }, { ...cell.technologies['4G'], band: 'invalid' },
        { ...cell.technologies['4G'], antenna: 123 }, { ...cell.technologies['4G'], powerDbm: null }, null];
    const invalid = badRadios.map(radio => ({ ...cell, technologies: { ...cell.technologies, '4G': radio } }));
    const store = new CoverageStore(async () => response(data({ cells: [clean, ...invalid,
        { ...cell, technologies: {} }, { ...cell, band: 'N78' }] })), () => 10000);
    store.options = options;
    await store.tick([tile], { ...filters, technology: '5G' });
    assert.equal(store.ready(tile).cells.length, 1);
    assert.equal(signalState(store.ready(tile).cells[0]), 'unknown');
    assert.equal(store.ready(tile).cells[0].powerDbm, null);
    const bandStore = new CoverageStore(async () => response(data()), () => 10000);
    bandStore.options = options;
    await bandStore.tick([tile], { ...filters, band: 'N78' });
    assert.equal(bandStore.ready(tile).cells.length, 0);
});

test('switching technology preserves stopped retries and the cache eviction order', async () => {
    let now = 10000, failing = false, calls = 0;
    const store = new CoverageStore(async path => {
        calls++;
        if (failing) return { status: 503, headers: new Headers({ 'Retry-After': '31' }) };
        const tx = Number(new URL(path, 'http://localhost').searchParams.get('tx'));
        return response(data({ tileX: tx, originX: tx * 128, cells: [] }));
    }, () => now);
    store.options = options;
    for (let tx = 0; tx < 128; tx++) await store.tick([{ ...tile, tx }], filters);
    store.select({ ...filters, technology: '4G' });
    for (let i = 0; i < 10; i++) store.ready(tile);
    await store.tick([{ ...tile, tx: 128 }], filters);
    assert.equal(store.entry(tile), undefined, 'projecting is not a FIFO touch');
    assert.equal(store.cache.size, 128);
    failing = true;
    await store.tick([{ ...tile, tx: 129 }], filters);
    const count = calls;
    now += 100000;
    for (const technology of ['4G', '5G', '2G', 'all']) {
        await store.tick([tile], { ...filters, technology });
        assert.equal(store.stopped, true);
        assert.equal(store.failures, 1);
    }
    assert.equal(calls, count);
});

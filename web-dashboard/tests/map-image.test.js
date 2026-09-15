import assert from 'node:assert/strict';
import { test } from 'node:test';
import { MapImageStore, MAP_IMAGE_BYTES } from '../map-image.js';
import { metadata, pngBlob, pngResponse } from './map-image-fixture.js';

function fixture({ fetcher = async () => pngResponse(), decoder } = {}) {
    let now = 1000;
    const requests = [], images = [];
    const store = new MapImageStore(async (path, options) => {
        requests.push({ path, options });
        return fetcher(path, options);
    }, { now: () => now, decode: async blob => {
        if (decoder) return decoder(blob);
        const view = new DataView(await blob.slice(0, 33).arrayBuffer());
        const image = { width: view.getUint32(16), height: view.getUint32(20), closed: 0, close() { this.closed++; } };
        images.push(image);
        return image;
    } });
    store.select('world', metadata());
    return { store, requests, images, advance: ms => { now += ms; } };
}

test('only ready, nonempty metadata with mapId and a new revision admits an image request', async () => {
    const { store, requests } = fixture();
    for (const data of [undefined, {}, metadata({ ready: false }), metadata({ empty: true }), metadata({ revision: 1 })]) {
        store.select('world', data);
        await store.tick();
    }
    for (const mapId of [null, undefined, '']) {
        store.select(mapId, metadata());
        await store.tick();
    }
    assert.equal(requests.length, 0);
    store.select('world', metadata());
    await store.tick();
    for (let i = 0; i < 500; i++) {
        store.select('world', metadata({ updatedAt: i }));
        await store.tick();
    }
    assert.equal(requests.length, 1);
});

test('authoritative newer headers override metadata geometry and revision without a redundant catch-up fetch', async () => {
    const { store, requests } = fixture({ fetcher: async () => pngResponse({ revision: 'r2', originX: -12000,
        originZ: -8000, blocksPerPixel: 16, width: 2048, height: 1024 }) });
    await store.tick();
    assert.equal(store.snapshot.revision, 'r2');
    assert.equal(store.snapshot.originX, -12000);
    assert.equal(store.snapshot.blocksPerPixel, 16);
    const draws = [];
    store.draw({ drawImage: (...args) => draws.push(args) }, { x: 100, y: 200 }, 0.25);
    assert.deepEqual(draws[0].slice(1), [-2900, -1800, 8192, 4096]);
    assert.equal(draws[0].length, 5, 'full image is transformed, not viewport-cropped or stretched to the viewport');
    assert.ok(draws[0][1] < 0 && draws[0][1] + draws[0][3] > 3840);
    assert.ok(draws[0][2] < 0 && draws[0][2] + draws[0][4] > 2160);
    await store.tick();
    store.select('world', metadata({ revision: 'r2' }));
    await store.tick();
    assert.equal(requests.length, 1);
});

test('304 sends If-None-Match and preserves image and authoritative geometry without more polling', async () => {
    let response = pngResponse();
    const { store, requests, images, advance } = fixture({ fetcher: async () => response });
    await store.tick();
    const snapshot = store.snapshot;
    response = { status: 304, headers: new Headers(), blob() { assert.fail('304 has no body'); } };
    store.select('world', metadata({ revision: 'r2', originX: 8000 }));
    await store.tick();
    assert.equal(requests[1].options.headers.get('If-None-Match'), '"r1"');
    assert.equal(store.snapshot, snapshot);
    assert.equal(images[0].closed, 0);
    advance(60000);
    await store.tick();
    assert.equal(requests.length, 2);
});

test('a failed background rebuild preserves the valid image without repeated downloads', async () => {
    const { store, requests, images } = fixture();
    await store.tick();
    const previous = store.snapshot;
    store.select('world', { ready: false, error: 'Invalid saved terrain' });
    for (let i = 0; i < 500; i++) await store.tick();
    assert.equal(store.snapshot, previous);
    assert.equal(requests.length, 1);
    assert.equal(images[0].closed, 0);
    assert.match(store.message, /indisponible/);
});

for (const status of [202, 429, 503, 504]) {
    test(`${status} retries only after Retry-After and retains the previous snapshot`, async () => {
        let response = pngResponse();
        const { store, requests, images, advance } = fixture({ fetcher: async () => response });
        await store.tick();
        response = { status, headers: new Headers(status === 202 ? {} : { 'Retry-After': '2' }),
            blob() { assert.fail('pending has no image'); } };
        store.select('world', metadata({ revision: 'r2' }));
        await store.tick();
        assert.equal(store.snapshot.image, images[0]);
        assert.equal(images[0].closed, 0);
        advance((status === 202 ? 1000 : 2000) - 1);
        await store.tick();
        assert.equal(requests.length, 2);
        response = pngResponse({ revision: 'r2' });
        advance(1);
        await store.tick();
        assert.equal(requests.length, 3);
        assert.equal(store.snapshot.image, images[1]);
        assert.equal(images[0].closed, 1);
        await store.tick();
        assert.equal(requests.length, 3);
    });
}

test('204 marks this revision empty without decoding or repeated polling; new revision can replace the old image', async () => {
    let response = pngResponse();
    const { store, requests, images, advance } = fixture({ fetcher: async () => response });
    await store.tick();
    response = { status: 204, headers: new Headers({ 'Retry-After': '6' }), blob() { assert.fail('empty body'); } };
    store.select('world', metadata({ revision: 'empty' }));
    await store.tick();
    assert.equal(store.snapshot.image, images[0]);
    assert.match(store.message, /vide/);
    advance(60000);
    await store.tick();
    assert.equal(requests.length, 2);
    response = pngResponse({ revision: 'r3' });
    store.select('world', metadata({ revision: 'r3' }));
    await store.tick();
    assert.equal(requests.length, 3);
    assert.equal(images[0].closed, 1);
});

test('new metadata during HTTP or decode never starts a second request or decode', async () => {
    let releaseFetch, releaseDecode;
    const { store, requests } = fixture({
        fetcher: () => new Promise(resolve => { releaseFetch = resolve; }),
        decoder: () => new Promise(resolve => { releaseDecode = resolve; }),
    });
    const pending = store.tick();
    for (let i = 0; i < 500; i++) {
        store.select('world', metadata({ revision: `r${i + 2}` }));
        await store.tick();
    }
    assert.equal(requests.length, 1);
    releaseFetch(pngResponse({ revision: 'r501' }));
    for (let i = 0; i < 32; i++) await Promise.resolve();
    assert.equal(typeof releaseDecode, 'function');
    for (let i = 0; i < 500; i++) await store.tick();
    assert.equal(requests.length, 1);
    releaseDecode({ width: 512, height: 512, close() {} });
    await pending;
    assert.equal(store.pending, false);
    await store.tick();
    assert.equal(requests.length, 1, 'response already includes the newest metadata revision');
});

for (const stage of ['fetch', 'decode']) {
    test(`world/token invalidation at ${stage} keeps the pending slot and closes stale bitmaps`, async () => {
        let release, closed = 0;
        const { store, requests } = fixture({
            fetcher: stage === 'fetch' ? () => new Promise(resolve => { release = resolve; }) : async () => pngResponse(),
            decoder: stage === 'decode' ? () => new Promise(resolve => { release = resolve; }) : undefined,
        });
        const pending = store.tick();
        for (let i = 0; i < 32; i++) await Promise.resolve();
        const current = requests[0].options.isCurrent;
        store.invalidate();
        store.select('world-b', metadata());
        assert.equal(current(), false);
        await store.tick();
        assert.equal(requests.length, 1);
        assert.equal(store.pending, true);
        if (stage === 'fetch') release({ ...pngResponse(), blob() { assert.fail('stale body must not be read'); } });
        else release({ width: 512, height: 512, close() { closed++; } });
        await pending;
        assert.equal(store.snapshot, null);
        assert.equal(store.pending, false);
        assert.equal(closed, stage === 'decode' ? 1 : 0);
    });
}

test('decode failure and wrong decoded dimensions preserve old image until a valid replacement', async () => {
    let revision = 'r1', fail = false, badSize = false;
    const images = [];
    const { store, advance } = fixture({ fetcher: async () => pngResponse({ revision }), decoder: async () => {
        if (fail) throw new Error('corrupt PNG');
        const image = { width: badSize ? 1024 : 512, height: 512, closed: 0, close() { this.closed++; } };
        images.push(image);
        return image;
    } });
    await store.tick();
    const original = store.snapshot;
    revision = 'r2';
    store.select('world', metadata({ revision }));
    fail = true;
    await store.tick();
    assert.equal(store.snapshot, original);
    assert.equal(images[0].closed, 0);
    fail = false;
    badSize = true;
    advance(3000);
    await store.tick();
    assert.equal(store.snapshot, original);
    assert.equal(images[1].closed, 1);
    badSize = false;
    advance(3000);
    await store.tick();
    assert.notEqual(store.snapshot, original);
    assert.equal(images[0].closed, 1);
});

test('old snapshot remains drawable throughout asynchronous replacement decode', async () => {
    let release;
    const { store, images } = fixture();
    await store.tick();
    const original = store.snapshot;
    store.decode = () => new Promise(resolve => { release = resolve; });
    store.select('world', metadata({ revision: 'r2' }));
    const pending = store.tick();
    for (let i = 0; i < 32; i++) await Promise.resolve();
    const draws = [];
    store.draw({ drawImage: (...args) => draws.push(args) }, { x: 0, y: 0 }, 1);
    assert.equal(draws[0][0], original.image);
    assert.equal(images[0].closed, 0);
    release({ width: 512, height: 512, close() {} });
    await pending;
    assert.equal(images[0].closed, 1);
});

test('invalid headers, world identity, PNG signature and oversized IHDR are rejected before decode', async () => {
    const responses = [
        pngResponse({ width: 2049 }), pngResponse({ height: 2049 }), pngResponse({ width: 0 }),
        pngResponse({ blocksPerPixel: 0 }), pngResponse({ originX: 1.5 }), pngResponse({ revision: '' }),
        pngResponse({}, { 'X-Map-Width': '' }), pngResponse({}, { 'X-Map-Scale': '1e3' }),
        pngResponse({}, { 'Content-Type': 'text/html' }), pngResponse({}, { 'X-Map-Id': 'wrong-world' }),
        { ...pngResponse(), blob: async () => pngBlob(100000, 100000) },
        { ...pngResponse(), blob: async () => new Blob([new Uint8Array(33)]) },
        { ...pngResponse(), blob: async () => new Blob([new Uint8Array(32)]) },
        { ...pngResponse(), blob: async () => ({ size: MAP_IMAGE_BYTES + 1024 * 1024 + 1,
            slice() { assert.fail('oversized blob must not be read'); } }) },
    ];
    for (const response of responses) {
        const { store } = fixture({ fetcher: async () => response, decoder() { assert.fail('preflight should reject'); } });
        await store.tick();
        assert.equal(store.snapshot, null);
        assert.equal(store.pending, false);
    }
});

test('max-size snapshots stay at 16 MiB active with one bounded decode and close each replaced bitmap', async () => {
    let revision = '0';
    const { store, requests, images } = fixture({ fetcher: async () => pngResponse({ revision, width: 2048, height: 2048 }) });
    for (let i = 0; i < 50; i++) {
        revision = String(i);
        store.select('world', metadata({ revision }));
        await store.tick();
        assert.equal(store.bytes, 16 * 1024 * 1024);
        assert.equal(images.filter(image => !image.closed).length, 1);
    }
    const draws = [];
    const ctx = { drawImage: (...args) => draws.push(args) };
    for (let i = 0; i < 500; i++) {
        store.draw(ctx, { x: -i * 100, y: i * 37 }, i % 2 ? 0.25 : 16);
        await store.tick();
    }
    assert.equal(draws.length, 500);
    assert.equal(requests.length, 50);
    assert.equal(images.length, 50, 'pan/zoom never decodes or allocates another bitmap');
    store.invalidate();
    assert.equal(store.bytes, 0);
    assert.ok(images.every(image => image.closed === 1));
});

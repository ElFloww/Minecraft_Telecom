import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { test } from 'node:test';
import vm from 'node:vm';

const source = (await readFile(new URL('../main.js', import.meta.url), 'utf8')).replace("import './style.css';", '');

async function dashboard() {
    const requests = [];
    const elements = new Map();
    const context = vm.createContext({
        document: {
            body: { style: {} },
            getElementById(id) {
                if (!elements.has(id)) elements.set(id, {
                    style: {}, value: '', textContent: '', clientWidth: 800, clientHeight: 600,
                    addEventListener() {}, getContext() { return {}; },
                });
                return elements.get(id);
            },
        },
        window: { addEventListener() {} },
        console, Headers, AbortSignal,
        setTimeout(callback, delay) { if (delay === 60) queueMicrotask(callback); },
        setInterval() {}, requestAnimationFrame() {},
        createImageBitmap: async () => ({ close() {} }),
        fetch: async (path, options) => {
            requests.push({ path, options });
            return { ok: true, status: 200, json: async () => path.includes('network') ? { nodes: [], edges: [] } : [],
                blob: async () => ({}) };
        },
    });
    vm.runInContext(source, context);
    await vm.runInContext('requestQueue', context);
    requests.length = 0;
    return { context, requests, elements, run: code => vm.runInContext(code, context) };
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

test('tiles use authenticated fetch and decoded blobs, not URL credentials', async () => {
    const { run, requests } = await dashboard();
    run("setSessionToken('tile-secret')");
    await run('requestQueue');
    run('activeTileRequests = 1');
    await run("fetchTile({ cx: -1, cz: 2 }, '-1,2')");
    const tile = requests.find(request => request.path.startsWith('/api/tile'));
    assert.equal(tile.path, '/api/tile?cx=-1&cz=2');
    assert.equal(tile.options.headers.get('Authorization'), 'Bearer tile-secret');
    assert.equal(run("!!tileCache.get('-1,2').image"), true);
    assert.equal(run('activeTileRequests'), 0);
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

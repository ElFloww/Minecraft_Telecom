import assert from 'node:assert/strict';
import { test } from 'node:test';
import { SpeedtestStore, speedtestPlot } from '../speedtest.js';

const node = (overrides = {}, id = '9223372036854775807') => ({ id, type: 'ROUTER', ip: '192.168.1.1', speedtest: {
    deviceId: `router:${id}`, sessionId: '00000000-0000-0000-0000-000000000001', state: 'DOWNLOAD',
    active: true, ticksElapsed: 1, totalTicksPerPhase: 300, actualBandwidth: 35,
    downloadBandwidth: 35, uploadBandwidth: 0, pingMs: 12, serverId: '-9223372036854775808', serverName: 'Server', ...overrides,
} });

test('UI uses the engine warmup/plateau allocations verbatim, never inventing peaks or averaging snapshots', () => {
    const store = new SpeedtestStore();
    const values = [35, 52, 79, 97, 94, 100, 96, 95];
    values.forEach((actualBandwidth, i) => store.capture(node({ actualBandwidth, ticksElapsed: i * 4 + 1,
        downloadBandwidth: 61 }), 0, i * 200));
    const entry = store.get(node().id);
    assert.deepEqual(entry.down.map(point => point.value), values);
    assert.equal(entry.snapshot.downloadBandwidth, 61);
    assert.equal(entry.max, 100);
    const plot = speedtestPlot(entry);
    assert.equal(plot.down.length, values.length);
    plot.down.forEach((point, i) => {
        assert.equal(point.y, 102 - values[i] / 100 * 94);
        assert.equal(point.x, 292 - (values.length - 1 - i) / (values.length - 1) * 284);
    });
    assert.deepEqual(plot.up, []);
});

test('ten-minute phases use a shared sliding window spanning retained samples, each aligned to its own last tick', () => {
    const store = new SpeedtestStore();
    for (let ticksElapsed = 0; ticksElapsed < 12000; ticksElapsed += 40) {
        store.capture(node({ ticksElapsed, totalTicksPerPhase: 12000 }), 0, ticksElapsed * 50);
    }
    for (const ticksElapsed of [0, 40, 80]) {
        store.capture(node({ state: 'UPLOAD', ticksElapsed, totalTicksPerPhase: 12000 }), 0, 600000 + ticksElapsed * 50);
    }
    const entry = store.get(node().id);
    const plot = speedtestPlot(entry);
    assert.equal(plot.down.length, 120);
    assert.equal(plot.up.length, 3);
    assert.equal(plot.windowSeconds, 119 * 40 / 20);
    assert.equal(plot.down[0].x, 8);
    assert.equal(plot.down.at(-1).x, 292);
    assert.equal(plot.up.at(-1).x, 292);
    assert.equal(plot.up[0].x, 292 - 80 / (119 * 40) * 284);
    for (const points of [plot.down, plot.up]) {
        assert.ok(points.every(point => Number.isFinite(point.x) && point.x >= 8 && point.x <= 292));
    }
});

test('empty and singleton sliding windows remain finite and tick-zero zero allocations are retained', () => {
    const store = new SpeedtestStore();
    store.capture(node({ state: 'PING', ticksElapsed: 0 }), 0, 0);
    const entry = store.get(node().id);
    assert.deepEqual(speedtestPlot(entry), { down: [], up: [], max: 0, windowSeconds: 0.05 });
    const zero = node({ ticksElapsed: 0, actualBandwidth: 0 });
    assert.equal(store.capture(zero, 0, 100), true);
    assert.equal(store.capture(zero, 0, 200), false);
    assert.deepEqual(entry.down, [{ ticks: 0, value: 0 }]);
    assert.deepEqual(speedtestPlot(entry).down, [{ x: 292, y: 102 }]);
});

test('rising number is time based, bounded by the last instantaneous sample, with immediate downward correction', () => {
    for (const fps of [15, 30, 60, 144]) {
        const store = new SpeedtestStore();
        store.capture(node({ actualBandwidth: 100 }), 0, 0);
        const entry = store.get(node().id);
        let previous = 0;
        for (let i = 0; i <= fps; i++) {
            const value = store.view(entry, i * 1000 / fps).value;
            assert.ok(value >= previous && value <= 100);
            previous = value;
        }
        assert.equal(store.view(entry, 1000).value, 100 - 100 * Math.exp(-1000 / 350));
        store.capture(node({ ticksElapsed: 21, actualBandwidth: 40, downloadBandwidth: 83 }), 0, 1000);
        assert.equal(store.view(entry, 1000).value, 40);
        assert.equal(entry.snapshot.downloadBandwidth, 83, 'server average is not the smoothed instant');
        assert.equal(entry.down.length, 2, 'frames cannot create history');
    }
    const store = new SpeedtestStore();
    store.capture(node({ actualBandwidth: 100 }), 0, 0);
    const entry = store.get(node().id);
    const early = store.view(entry, 20).value;
    store.capture(node({ ticksElapsed: 2, actualBandwidth: 90 }), 0, 20);
    assert.ok(Math.abs(store.view(entry, 20).value - early) < 1e-12, 'a lower target cannot cause an upward jump');
});

test('120 points per direction and 256 devices LRU remain bounded, including dedup after eviction', () => {
    const store = new SpeedtestStore();
    for (const state of ['DOWNLOAD', 'UPLOAD']) {
        for (let ticksElapsed = 1; ticksElapsed <= 400; ticksElapsed++) {
            const sample = node({ state, ticksElapsed, totalTicksPerPhase: 600, actualBandwidth: ticksElapsed });
            assert.equal(store.capture(sample, 0, ticksElapsed), true);
            assert.equal(store.capture(sample, 0, ticksElapsed + 1), false);
        }
    }
    const entry = store.get(node().id);
    assert.equal(entry.down.length, 120);
    assert.equal(entry.up.length, 120);
    assert.equal(entry.down[0].ticks, 281);
    assert.equal(entry.max, 400);
    assert.equal(store.capture(node({ ticksElapsed: 1 }), 0, 10000), false);
    for (let i = 0; i < 255; i++) store.capture(node({}, String(i)), 0, 0);
    store.get(node().id);
    store.capture(node({}, 'new'), 0, 0);
    assert.equal(store.devices.size, 256);
    assert.equal(store.devices.has('0'), false, 'least recently used device was evicted');
    assert.equal(store.get(node().id), entry);
});

test('dedup does not renew freshness; stale numbers, progress and activity freeze after six seconds', () => {
    const store = new SpeedtestStore();
    const sample = node({ totalTicksPerPhase: 12000 });
    store.capture(sample, 0, 0);
    const entry = store.get(sample.id);
    const before = store.view(entry, 6000);
    assert.equal(before.stale, false);
    assert.equal(store.capture(sample, 0, 7000), false);
    const stale = store.view(entry, 7000);
    assert.equal(stale.stale, true);
    for (const key of ['value', 'progress', 'activity']) assert.equal(stale[key], before[key]);
    assert.deepEqual(store.view(entry, 60000), stale);
    store.capture(node({ ticksElapsed: 41, totalTicksPerPhase: 12000, actualBandwidth: 50 }), 0, 60000);
    assert.equal(store.view(entry, 60000).stale, false);
    assert.equal(entry.down.length, 2);
});

test('PING is capped at 60 ticks, total is 33 seconds for 300 ticks and phases require confirmation', () => {
    for (const duration of [20, 300, 600, 12000]) {
        const store = new SpeedtestStore();
        const ping = Math.min(duration, 60);
        store.capture(node({ state: 'PING', ticksElapsed: 0, totalTicksPerPhase: duration }), 0, 0);
        const entry = store.get(node().id);
        assert.equal(store.view(entry, 0).totalSeconds, (duration * 2 + ping) / 20);
        assert.equal(store.view(entry, 6000).progress, ping / (duration * 2 + ping) * 100);
        assert.equal(entry.snapshot.state, 'PING');
        for (const state of ['DOWNLOAD', 'UPLOAD']) {
            store.capture(node({ state, ticksElapsed: duration - 1, totalTicksPerPhase: duration }), 0, 7000);
            const view = store.view(entry, 10000);
            assert.ok(view.progress < 100);
            assert.ok(view.phaseProgress < 100);
            assert.equal(view.waitingPhase, true);
            assert.equal(entry.snapshot.state, state);
        }
        store.capture(node({ state: 'FINISHED', active: false, ticksElapsed: 0, totalTicksPerPhase: duration }), 0, 11000);
        assert.equal(store.view(entry, 11000).progress, 100);
    }
});

test('terminal averages, ping, destination and curves freeze; failure is never successful completion', () => {
    for (const state of ['FINISHED', 'FAILED', 'REJECTED']) {
        const store = new SpeedtestStore();
        store.capture(node({ state: 'UPLOAD', ticksElapsed: 150 }), 0, 0);
        const entry = store.get(node().id);
        store.capture(node({ state, active: false, ticksElapsed: 0, actualBandwidth: 0,
            downloadBandwidth: 123456, uploadBandwidth: 6789 }), 0, 2000);
        const terminal = store.view(entry, 2000);
        assert.equal(terminal.terminal, true);
        assert.equal(terminal.progress === 100, state === 'FINISHED');
        assert.equal(terminal.phaseProgress, state === 'FINISHED' ? 100 : 50);
        assert.equal(terminal.value, 123456);
        assert.deepEqual(store.view(entry, 100000), terminal);
        assert.equal(entry.down.length, 0);
        assert.equal(entry.up.length, 1, 'terminal zero cannot fabricate a dip');
        assert.equal(store.capture(node({ state, ticksElapsed: 200, downloadBandwidth: 9, serverName: 'Wrong' }), 0, 3000), false);
        assert.equal(entry.snapshot.serverName, 'Server');
        assert.equal(entry.snapshot.pingMs, 12);
        assert.equal(entry.snapshot.uploadBandwidth, 6789);
    }
});

test('isolated failure has unknown advancement; only the same UUID retains confirmed, not extrapolated progress', () => {
    const store = new SpeedtestStore();
    const failure = node({ state: 'FAILED', active: false, ticksElapsed: 200 });
    store.capture(failure, 0, 0);
    let entry = store.get(node().id);
    assert.equal(entry.failedProgress, null);
    assert.equal(store.view(entry, 5000).progress, null);
    assert.equal(store.view(entry, 5000).phaseProgress, null);
    store.capture(node({ sessionId: 'new', ticksElapsed: 100 }), 0, 1000);
    entry = store.get(node().id);
    const confirmed = store.view(entry, 1000);
    assert.ok(store.view(entry, 5000).progress > confirmed.progress);
    store.capture(node({ sessionId: 'new', state: 'FAILED', ticksElapsed: 190, active: false }), 0, 5000);
    assert.equal(store.view(entry, 10000).progress, confirmed.progress);
    assert.equal(store.view(entry, 10000).phaseProgress, confirmed.phaseProgress);
    store.capture(node({ sessionId: 'isolated-new-failure', state: 'FAILED', active: false }), 0, 11000);
    assert.equal(store.view(store.get(node().id), 12000).progress, null);
    store.capture(node({ sessionId: 'isolated-success', state: 'FINISHED', active: false }), 0, 13000);
    assert.equal(store.view(store.get(node().id), 14000).progress, 100);
});

test('opaque session journal rejects old to new to old rollbacks and stays bounded per device', () => {
    const store = new SpeedtestStore();
    const old = node({ sessionId: 'z-old' });
    store.capture(old, 0, 0);
    store.capture(node({ sessionId: 'a-new', actualBandwidth: 17 }), 0, 2000);
    const current = store.get(node().id);
    assert.equal(store.capture(old, 0, 4000), false);
    assert.equal(store.get(node().id), current);
    assert.equal(current.snapshot.sessionId, 'a-new');
    assert.equal(current.receivedAt, 2000);
    assert.deepEqual(current.down, [{ ticks: 1, value: 17 }]);
    for (let i = 0; i < 300; i++) {
        assert.equal(store.capture(node({ sessionId: `session-${i}`, state: i % 2 ? 'FAILED' : 'FINISHED', active: false }), 0, i + 5000), true);
        assert.ok(store.get(node().id).seenSessionIds.size <= 128);
    }
    assert.equal(store.get(node().id).seenSessionIds.size, 128);
    assert.equal(store.capture(node({ sessionId: 'session-298' }), 0, 9000), false);
    assert.equal(store.capture(node({ sessionId: 'session-298' }, 'another-device'), 0, 9000), true);
    store.resetWorld(1);
    assert.equal(store.devices.size, 0);
    assert.equal(store.capture(old, 1, 10000), true, 'the new world owns an independent session journal');
});

test('new phase resets smoothing but retains DOWN, new UUID resets all history, wrong device/world are ignored', () => {
    const store = new SpeedtestStore();
    store.capture(node(), 0, 0);
    let entry = store.get(node().id);
    store.capture(node({ state: 'UPLOAD', ticksElapsed: 1, actualBandwidth: 12 }), 0, 2000);
    assert.equal(entry.down.length, 1);
    assert.equal(entry.up.length, 1);
    assert.equal(store.view(entry, 2000).value, 0);
    assert.equal(store.capture(node({ deviceId: 'router:wrong' }), 0, 2500), false);
    store.capture(node({ sessionId: '00000000-0000-0000-0000-000000000002', state: 'PING' }), 0, 3000);
    entry = store.get(node().id);
    assert.equal(entry.down.length + entry.up.length, 0);
    assert.equal(entry.max, 0);
    store.resetWorld(2);
    assert.equal(store.devices.size, 0);
    assert.equal(store.capture(node(), 0, 4000), false);
    assert.equal(store.capture(node(), 2, 4000), true);
});

test('reduced motion shows exact instants and confirmed progress, with no animated activity', () => {
    const store = new SpeedtestStore();
    store.capture(node(), 0, 0, true);
    const entry = store.get(node().id);
    const first = store.view(entry, 0, true);
    assert.equal(first.value, 35);
    assert.equal(first.activity, 0);
    assert.deepEqual(store.view(entry, 5000, true), first);
    store.capture(node({ ticksElapsed: 41, actualBandwidth: 97 }), 0, 5000, true);
    assert.equal(store.view(entry, 5000, true).value, 97);
    assert.ok(store.view(entry, 5000, true).progress > first.progress);
});

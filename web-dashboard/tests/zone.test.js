import test from 'node:test';
import assert from 'node:assert/strict';
import { worldPoint, zoneBounds, zoneError, zoneTileCount, exactCoverageTiles, exactCoverageViewport,
    TERRAIN_ZONE_LIMIT, COVERAGE_ZONE_LIMIT } from '../zone.js';

test('zone selection transforms screen coordinates with negative floor semantics', () => {
    const point = worldPoint(298, 99, { left: 300, top: 100 }, { x: 0, y: 0 }, 2);
    assert.deepEqual(point, { x: -1, z: -1 });
    assert.deepEqual(zoneBounds(point, { x: 20, z: 0 }), { minX: -1, minZ: -1, maxX: 20, maxZ: 0 });
});

test('terrain and coverage have separate budgets and exact steps', () => {
    const bounds = { minX: 0, minZ: 0, maxX: 1023, maxZ: 1023 };
    assert.equal(TERRAIN_ZONE_LIMIT, 4096);
    assert.equal(COVERAGE_ZONE_LIMIT, 1024);
    assert.equal(zoneTileCount(bounds, 16), 4096);
    assert.equal(zoneError('terrain', bounds, 16), '');
    assert.notEqual(zoneError('coverage', bounds, 1), '');
    assert.equal(zoneError('coverage', bounds, 8), '');
    assert.notEqual(zoneError('coverage', bounds, 32), '');
    assert.notEqual(zoneError('terrain', { ...bounds, maxX: 1024 }, 16), '');
});

test('limits count physical tiles across negative, elongated and unaligned bounds', () => {
    for (const [kind, span, limit, step] of [['terrain', 16, 4096, 16],
        ...[1, 8, 16].map(step => ['coverage', step * 16, 1024, step])]) {
        for (const negative of [false, true]) {
            const bounds = { minX: negative ? -limit * span : 0, maxX: negative ? -1 : limit * span - 1,
                minZ: -span, maxZ: -1 };
            assert.equal(zoneTileCount(bounds, span), limit);
            assert.equal(zoneError(kind, bounds, step), '');
            const extra = { ...bounds, maxX: bounds.maxX + 1 };
            assert.equal(zoneTileCount(extra, span), limit + 1);
            assert.notEqual(zoneError(kind, extra, step), '');
            const tall = { minX: bounds.minZ, maxX: bounds.maxZ, minZ: bounds.minX, maxZ: bounds.maxX };
            assert.equal(zoneError(kind, tall, step), '');
        }
    }
    assert.equal(zoneTileCount({ minX: -1, minZ: -1, maxX: 0, maxZ: 0 }, 16), 4);
});

test('invalid integer bounds, spans and overflow cannot bypass zone budgets', () => {
    const bounds = { minX: 0, minZ: 0, maxX: 15, maxZ: 15 };
    for (const maxX of [-1, 1.5, NaN, Infinity, 30000000, Number.MAX_SAFE_INTEGER + 1, '15']) {
        assert.equal(zoneTileCount({ ...bounds, maxX }, 16), Infinity);
        assert.notEqual(zoneError('terrain', { ...bounds, maxX }), '');
        assert.notEqual(zoneError('coverage', { ...bounds, maxX }, 1), '');
    }
    for (const span of [0, -16, 0.5, NaN, Infinity, Number.MAX_SAFE_INTEGER + 1, '16']) {
        assert.equal(zoneTileCount(bounds, span), Infinity);
    }
    assert.notEqual(zoneError('terrain', { minX: -30000000, minZ: -30000000, maxX: 29999999, maxZ: 29999999 }), '');
    assert.notEqual(zoneError('coverage', bounds, -1), '');
});

test('1024 descriptors retain exact steps and reject duplicates, mismatches and 1025 tiles', () => {
    for (const step of [1, 8, 16]) {
        const level = step === 1 ? -3 : step === 8 ? 0 : 1;
        const span = step * 16;
        const job = { step, bounds: { minX: -512 * span, minZ: -span, maxX: 512 * span - 1, maxZ: -1 },
            coverageTiles: Array.from({ length: 1024 }, (_, i) => ({ x: i - 512, z: -1, level, step })) };
        const tiles = exactCoverageTiles(job);
        assert.equal(tiles.length, 1024);
        assert.ok(tiles.every(tile => tile.step === step && tile.level === level && tile.tileSize === span));
        assert.throws(() => exactCoverageTiles({ ...job, coverageTiles: [...job.coverageTiles, job.coverageTiles[0]] }));
        for (const bad of [null, { ...job.coverageTiles[0], step: 32 }, { ...job.coverageTiles[0], level: 6 },
            { ...job.coverageTiles[0], x: 512 }, { ...job.coverageTiles[0], x: -0.5 }, job.coverageTiles[1]]) {
            assert.throws(() => exactCoverageTiles({ ...job, coverageTiles: [bad, ...job.coverageTiles.slice(1)] }));
        }
        const view = exactCoverageViewport(tiles, { width: 512, height: 512, pan: { x: 256, y: 256 }, zoom: 0.001 });
        assert.equal(view.tiles.length, 64);
        assert.equal(view.visibleKeys.size, 1024);
        assert.equal(view.limited, true);
        assert.ok(view.tiles.every(tile => tile.step === step && tile.level === level));
    }
});

test('exact viewport prioritizes the centre, has a small margin and reaches either end of long zones', () => {
    const tiles = exactCoverageTiles({ step: 1, bounds: { minX: -8192, minZ: 0, maxX: 8191, maxZ: 15 },
        coverageTiles: Array.from({ length: 1024 }, (_, i) => ({ x: i - 512, z: 0, step: 1, level: -3 })) });
    for (const panX of [8192, -8160]) {
        const view = exactCoverageViewport(tiles, { width: 32, height: 16, pan: { x: panX, y: 0 }, zoom: 1 });
        assert.ok(view.tiles.length <= 4);
        assert.ok(view.tiles.every(tile => tile.tx * 16 + panX < 48 && (tile.tx + 1) * 16 + panX > -16));
        assert.ok(view.tiles.some(tile => tile.tx === (panX > 0 ? -512 : 511)));
        assert.equal(view.limited, false);
    }
    const view = exactCoverageViewport(tiles, { width: 16384, height: 16, pan: { x: 8192, y: 0 }, zoom: 1 });
    assert.equal(view.tiles.length, 64);
    assert.deepEqual(new Set(view.tiles.map(tile => tile.tx)), new Set(Array.from({ length: 64 }, (_, i) => i - 32)));
    assert.ok(view.tiles.every((tile, i) => i === 0 || tile.distance >= view.tiles[i - 1].distance));
});

test('zone results preserve their one-block grid regardless of viewport LOD', () => {
    const job = { step: 1, bounds: { minX: -16, minZ: 0, maxX: -1, maxZ: 15 },
        coverageTiles: [{ x: -1, z: 0, level: -3, step: 1 }] };
    const [tile] = exactCoverageTiles(job);
    assert.equal(tile.tileSize, 16);
    assert.equal(tile.step, 1);
    assert.equal(tile.tx, -1);
    assert.throws(() => exactCoverageTiles({ ...job, coverageTiles: [...job.coverageTiles, ...job.coverageTiles] }));
});

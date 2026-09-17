export const WORLD_MIN = -30000000;
export const WORLD_MAX = 29999999;
export const TERRAIN_ZONE_LIMIT = 4096;
export const COVERAGE_ZONE_LIMIT = 1024;

export function worldPoint(clientX, clientY, rect, pan, zoom) {
    const clamp = value => Math.max(WORLD_MIN, Math.min(WORLD_MAX, Math.floor(value)));
    return { x: clamp((clientX - rect.left - pan.x) / zoom),
        z: clamp((clientY - rect.top - pan.y) / zoom) };
}

export function zoneBounds(a, b) {
    return { minX: Math.min(a.x, b.x), minZ: Math.min(a.z, b.z),
        maxX: Math.max(a.x, b.x), maxZ: Math.max(a.z, b.z) };
}

export function validBounds(bounds) {
    return bounds && ['minX', 'minZ', 'maxX', 'maxZ'].every(key => Number.isInteger(bounds[key])
        && bounds[key] >= WORLD_MIN && bounds[key] <= WORLD_MAX)
        && bounds.minX <= bounds.maxX && bounds.minZ <= bounds.maxZ;
}

export function zoneTileCount(bounds, span) {
    if (!validBounds(bounds) || !Number.isSafeInteger(span) || span <= 0) return Infinity;
    const count = (Math.floor(bounds.maxX / span) - Math.floor(bounds.minX / span) + 1)
        * (Math.floor(bounds.maxZ / span) - Math.floor(bounds.minZ / span) + 1);
    return Number.isSafeInteger(count) && count > 0 ? count : Infinity;
}

export function zoneError(kind, bounds, step) {
    if (!validBounds(bounds)) return 'Bornes entieres requises entre -30000000 et 29999999, min <= max.';
    if (kind === 'terrain') return zoneTileCount(bounds, 16) > TERRAIN_ZONE_LIMIT ? `Limite : ${TERRAIN_ZONE_LIMIT} chunks de terrain.` : '';
    if (kind !== 'coverage') return 'Type de zone incompatible.';
    if (![1, 8, 16].includes(step)) return 'Choisir un pas de couverture : 1, 8 ou 16 blocs.';
    return zoneTileCount(bounds, step * 16) > COVERAGE_ZONE_LIMIT ? `Limite : ${COVERAGE_ZONE_LIMIT} tuiles physiques (pas x 16 blocs).` : '';
}

export function exactCoverageTiles(job) {
    if (!Array.isArray(job.coverageTiles) || job.coverageTiles.length > COVERAGE_ZONE_LIMIT
        || zoneError('coverage', job.bounds, job.step)) throw new Error('Tuiles de zone incompatibles');
    const level = job.step === 1 ? -3 : job.step === 8 ? 0 : 1;
    const tileSize = job.step * 16;
    const keys = new Set();
    return job.coverageTiles.map(tile => {
        const key = `${tile?.level}/${tile?.x},${tile?.z}`;
        if (!tile || !Number.isSafeInteger(tile.x) || !Number.isSafeInteger(tile.z) || tile.level !== level
            || tile.step !== job.step || keys.has(key)
            || tile.x < Math.floor(job.bounds.minX / tileSize) || tile.x > Math.floor(job.bounds.maxX / tileSize)
            || tile.z < Math.floor(job.bounds.minZ / tileSize) || tile.z > Math.floor(job.bounds.maxZ / tileSize)) {
            throw new Error('Tuiles de zone incompatibles');
        }
        keys.add(key);
        return { tx: tile.x, tz: tile.z, level: tile.level, step: tile.step, tileSize, key, distance: 0 };
    });
}

export function exactCoverageViewport(tiles, { width, height, pan, zoom }) {
    if (!(width > 0 && height > 0 && zoom > 0)) return { tiles: [], visibleKeys: new Set(), limited: false };
    const cx = (width / 2 - pan.x) / zoom, cz = (height / 2 - pan.y) / zoom;
    const candidates = [];
    const visibleKeys = new Set();
    for (const tile of tiles) {
        const size = tile.tileSize * zoom;
        const x = tile.tx * size + pan.x, z = tile.tz * size + pan.y;
        const margin = Math.min(32, size);
        if (x >= width + margin || x + size <= -margin || z >= height + margin || z + size <= -margin) continue;
        const visible = x < width && x + size > 0 && z < height && z + size > 0;
        if (visible) visibleKeys.add(tile.key);
        candidates.push({ ...tile, visible, distance: (tile.tx + 0.5 - cx / tile.tileSize) ** 2
            + (tile.tz + 0.5 - cz / tile.tileSize) ** 2 });
    }
    // The window depends only on geometry, never on cache readiness or LRU eviction.
    candidates.sort((a, b) => Number(b.visible) - Number(a.visible) || a.distance - b.distance);
    return { tiles: candidates.slice(0, 64), visibleKeys, limited: visibleKeys.size > 64 };
}

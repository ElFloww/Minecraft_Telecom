export const COVERAGE_STYLES = {
    strong: { color: '#22c55e', label: 'Fort > -80 dBm' },
    medium: { color: '#eab308', label: 'Moyen > -100 dBm' },
    weak: { color: '#f97316', label: 'Faible > -120 dBm' },
    below: { color: '#ef4444', label: 'Sous seuil <= -120 dBm' },
    none: { color: '#94a3b8', label: 'Signal absent' },
    unknown: { color: '#c4b5fd', label: 'Signal inconnu' },
    pending: { color: '#64748b', label: 'En attente / expire' },
};

export function signalState(cell, status = 'ready') {
    if (status !== 'ready') return 'pending';
    if (cell?.state === 'none') return 'none';
    if (cell?.state !== 'signal' || !Number.isFinite(cell.powerDbm)) return 'unknown';
    return cell.powerDbm > -80 ? 'strong' : cell.powerDbm > -100 ? 'medium'
        : cell.powerDbm > -120 ? 'weak' : 'below';
}

export const COVERAGE_TECHNOLOGIES = ['2G', '3G', '4G', '5G'];
export const COVERAGE_STEPS = [1, 8, 16, 32, 64, 128, 256, 512, 1024, 2048];

export function coverageTileSize(level) {
    return level >= 0 ? 128 << level : 128 >> -level;
}

export function coverageStep(value, zoom, level = 0) {
    const desired = value === 'auto' ? (zoom >= 16 ? 1 : zoom >= 2 ? 8 : 16) : Number(value);
    return COVERAGE_STEPS.find(step => step >= Math.max(desired, coverageTileSize(level) / 16));
}

export function coverageQuery(tile, filters) {
    // Canonical requests keep both the legacy dominant fields and shared technology slots reusable.
    return '/api/coverage?' + new URLSearchParams({ tx: String(tile.tx), tz: String(tile.tz),
        step: String(filters.step), y: filters.y, antenna: filters.antenna,
        technology: 'all', band: filters.band, level: String(tile.level ?? 0) });
}

export function visibleCoverageTiles({ width, height, pan, zoom }, budget = 49) {
    if (!(width > 0 && height > 0 && zoom > 0)) return [];
    let level = -3, size, minX, maxX, minZ, maxZ;
    do {
        size = coverageTileSize(level) * zoom;
        minX = Math.floor(-pan.x / size); maxX = Math.ceil((width - pan.x) / size) - 1;
        minZ = Math.floor(-pan.y / size); maxZ = Math.ceil((height - pan.y) / size) - 1;
        if ((maxX - minX + 1) * (maxZ - minZ + 1) <= budget || level === 6) break;
        level++;
    } while (true);
    const cx = (width / 2 - pan.x) / size, cz = (height / 2 - pan.y) / size;
    const tiles = [];
    // Coarsen the grid, never truncate its visible rectangle.
    for (let tx = minX; tx <= maxX; tx++) {
        for (let tz = minZ; tz <= maxZ; tz++) {
            tiles.push({ level, tileSize: coverageTileSize(level), tx, tz, key: `${level}/${tx},${tz}`,
                distance: (tx + 0.5 - cx) ** 2 + (tz + 0.5 - cz) ** 2 });
        }
    }
    return tiles.sort((a, b) => a.distance - b.distance);
}

export function retryDelay(value, failures, now) {
    const text = value?.trim() ?? '';
    const seconds = /^\d+(\.\d+)?$/.test(text) ? Number(text) : NaN;
    const retry = Number.isFinite(seconds) ? seconds * 1000
        : /[A-Za-z]/.test(text) ? Date.parse(text) - now : NaN;
    // A longer server cooldown suspends automatic retries instead of retrying too early.
    if (retry > 30000) return Infinity;
    return Math.min(30000, Math.max(1000 * 2 ** (failures - 1), Number.isFinite(retry) ? retry : 0));
}

export function validateCoverageOptions(data) {
    if (data?.tileSize !== 128 || data.maxRange !== 4096 || typeof data.modelRevision !== 'string' || !Number.isInteger(data.minY)
        || !Number.isInteger(data.maxY) || data.minY > data.maxY
        || data.minLevel !== -3 || data.maxLevel !== 6 || data.maxSamplesPerSide !== 16
        || data.sharedTechnologies !== true
        || !Array.isArray(data.steps) || data.steps.length !== COVERAGE_STEPS.length
        || !COVERAGE_STEPS.every(step => data.steps.includes(step))
        || !Array.isArray(data.technologies) || data.technologies.length !== 4
        || !COVERAGE_TECHNOLOGIES.every(tech => data.technologies.includes(tech))
        || !Array.isArray(data.bands) || data.bands.some(b => !b || typeof b.id !== 'string'
            || typeof b.label !== 'string' || !COVERAGE_TECHNOLOGIES.includes(b.technology))
        || new Set(data.bands.map(b => b.id)).size !== data.bands.length) {
        throw new Error('Options de couverture incompatibles');
    }
    return data;
}

function validateTile(data, tile, filters, options) {
    const level = tile.level ?? 0, tileSize = coverageTileSize(level);
    const samples = tileSize / filters.step;
    if (!['ready', 'pending'].includes(data?.status) || typeof data.revision !== 'string'
        || (data.modelRevision !== undefined && typeof data.modelRevision !== 'string')
        || !Number.isInteger(level) || level < -3 || level > 6 || data.level !== level
        || !COVERAGE_STEPS.includes(filters.step)
        || !Number.isInteger(samples) || samples < 1 || samples > 16
        || data.tileX !== tile.tx || data.tileZ !== tile.tz || data.tileSize !== tileSize
        || data.originX !== tile.tx * tileSize || data.originZ !== tile.tz * tileSize
        || data.step !== filters.step || data.height !== filters.y || data.maxRange !== 4096
        || !Number.isFinite(data.generatedAt) || !Number.isFinite(data.validForMs) || data.validForMs < 0
        || !Number.isFinite(data.progress) || data.progress < 0 || data.progress > 1
        || !Array.isArray(data.cells) || data.cells.length > samples ** 2) {
        throw new Error('Tuile de couverture incompatible');
    }
    // Partial jobs are never represented as completed radio measurements.
    if (data.status === 'pending') return { ...data, cells: [] };
    const validRadio = (radio, technology = null) => {
        if (!radio || !['signal', 'none', 'unknown'].includes(radio.state)) return false;
        if (radio.state !== 'signal') return radio.powerDbm === null && radio.technology === null
            && radio.band === null && radio.antenna === null
            && radio.service === (radio.state === 'unknown' ? 'unknown' : 'unavailable');
        return Number.isFinite(radio.powerDbm) && COVERAGE_TECHNOLOGIES.includes(radio.technology)
            && (!technology || radio.technology === technology)
            && options.bands.some(b => b.id === radio.band && b.technology === radio.technology)
            && (filters.band === 'all' || radio.band === filters.band)
            && typeof radio.antenna === 'string' && /^-?\d+$/.test(radio.antenna)
            && (filters.antenna === 'all' || radio.antenna === filters.antenna)
            && ['available', 'unavailable'].includes(radio.service);
    };
    const cells = data.cells.filter(c => c && Number.isFinite(c.x) && Number.isFinite(c.y) && Number.isFinite(c.z)
        && c.x >= data.originX && c.x < data.originX + tileSize && c.z >= data.originZ && c.z < data.originZ + tileSize
        && (filters.y === 'surface' || c.y === Number(filters.y))
        && validRadio(c) && c.technologies
        && COVERAGE_TECHNOLOGIES.every(tech => validRadio(c.technologies[tech], tech)));
    return { ...data, cells };
}

export class CoverageStore {
    constructor(fetcher, clock = Date.now) {
        this.fetcher = fetcher;
        this.clock = clock;
        this.cache = new Map();
        this.generation = 0;
        this.busy = false;
        this.options = null;
        this.nextOptionsAt = Infinity;
        this.invalidate();
    }

    invalidate(resetOptions = false) {
        this.generation++;
        this.cache.clear();
        this.failures = 0;
        this.retryAt = 0;
        this.stopped = false;
        this.message = '';
        this.preferUnseen = true;
        this.pendingTurn = false;
        this.optionsPendingAt = 0;
        this.mismatchRevision = null;
        if (resetOptions) {
            this.options = null;
            this.nextOptionsAt = Infinity;
        }
        // Keep busy until the old request settles, even after changing session.
    }

    select(filters) {
        // Technology is a local projection. All selections retain bounded physical jobs.
        this.viewFilters = filters;
    }

    key(tile, filters = this.viewFilters) {
        return JSON.stringify([filters.band, filters.antenna, filters.y, tile.level ?? 0, tile.tx, tile.tz, filters.step]);
    }

    entry(tile) {
        return this.cache.get(this.key(tile));
    }

    ready(tile, now = this.clock()) {
        const entry = this.entry(tile);
        if (entry?.data?.status !== 'ready' || entry.expires <= now
            || (entry.data.modelRevision !== undefined && entry.data.modelRevision !== this.options?.modelRevision)
            || entry.data.step !== this.viewFilters.step
            || entry.data.level !== (tile.level ?? 0)) return null;
        const technology = this.viewFilters.technology;
        if (technology === 'all') return entry.data;
        if (!COVERAGE_TECHNOLOGIES.includes(technology)) return null;
        // Allocate at most once per entry/technology, not on every canvas frame.
        entry.projected ??= new Map();
        if (!entry.projected.has(technology)) {
            entry.projected.set(technology, { ...entry.data, cells: entry.data.cells.map(cell => ({
                ...cell.technologies[technology], x: cell.x, y: cell.y, z: cell.z,
            })) });
        }
        return entry.projected.get(technology);
    }

    async tick(tiles, filters) {
        this.select(filters);
        const now = this.clock();
        if (this.busy || this.stopped || now < this.retryAt) return;
        const visible = new Map(tiles.map(t => [this.key(t), t]));
        const pending = [...this.cache].filter(([, entry]) => entry.pending || entry.data?.status === 'pending');
        // Drain due jobs before admitting new ones, including jobs left behind by panning.
        const duePending = pending.filter(([, entry]) => entry.nextAt <= now)
            .sort(([a, ae], [b, be]) => Number(visible.has(b)) - Number(visible.has(a))
                || ae.nextAt - be.nextAt || (visible.get(a)?.distance ?? 0) - (visible.get(b)?.distance ?? 0))[0];
        const optionsDue = this.options ? now >= this.nextOptionsAt : now >= this.optionsPendingAt;
        let tile = null;
        let requestFilters = filters;
        if (this.options && duePending && (!optionsDue || !this.pendingTurn)) {
            const [, entry] = duePending;
            tile = entry.tile;
            requestFilters = entry.filters;
            this.pendingTurn = true;
        } else if (this.options && !optionsDue && pending.length < 16) {
            const unseen = tiles.filter(t => !this.entry(t)).sort((a, b) => a.distance - b.distance)[0];
            const expired = tiles.filter(t => this.entry(t)?.data?.status === 'ready'
                && !this.entry(t).pending && this.entry(t).nextAt <= now)
                .sort((a, b) => this.entry(a).nextAt - this.entry(b).nextAt || a.distance - b.distance)[0];
            tile = this.preferUnseen ? unseen ?? expired : expired ?? unseen;
            if (tile) this.preferUnseen = !!this.entry(tile);
        }
        if (!tile && !optionsDue) return;
        if (!tile) this.pendingTurn = false;
        const generation = this.generation;
        const key = tile ? this.key(tile, requestFilters) : null;
        const modelRevision = this.options?.modelRevision;
        this.busy = true;
        try {
            const response = await this.fetcher(tile ? coverageQuery(tile, requestFilters) : '/api/coverage/options', {
                isCurrent: () => generation === this.generation && (!tile || modelRevision === this.options?.modelRevision),
            });
            if (generation !== this.generation || (tile && modelRevision !== this.options?.modelRevision)) return;
            if (response.status === 202 || response.status === 204) {
                const delay = retryDelay(response.headers?.get('Retry-After'), response.status === 204 ? 6 : 1, this.clock());
                const nextAt = this.clock() + delay;
                // HTTP pending has no coverage data. Retain the job and original TTL without inventing samples.
                if (tile) this.cache.set(key, { ...this.cache.get(key), tile, filters: requestFilters, pending: true, nextAt });
                else this.optionsPendingAt = this.nextOptionsAt = nextAt;
                this.failures = 0;
                this.message = !Number.isFinite(delay) ? 'Retry-After superieur a 30 s : lecture suspendue, actualiser pour reprendre.'
                    : response.status === 202 ? 'Jeu en pause ou occupe : en attente du tick Minecraft.' : 'Zones non chargees : lecture differee de 30 s.';
                return;
            }
            if (response.status !== 200) {
                const retryable = [429, 503, 504].includes(response.status);
                const error = new Error(`Couverture HTTP ${response.status}`);
                error.retryable = retryable;
                error.retryAfter = response.headers?.get('Retry-After');
                throw error;
            }
            const data = await response.json();
            if (generation !== this.generation || (tile && modelRevision !== this.options?.modelRevision)) return;
            if (!tile) {
                const clean = validateCoverageOptions(data);
                if (this.options && this.options.modelRevision !== clean.modelRevision) {
                    this.generation++;
                    this.cache.clear();
                }
                // Stable polls must not rebuild controls or overwrite an in-progress Y edit.
                if (JSON.stringify(this.options) !== JSON.stringify(clean)) this.options = clean;
                this.nextOptionsAt = this.clock() + 2000;
            } else if (typeof data?.modelRevision === 'string' && data.modelRevision !== modelRevision) {
                const received = this.clock();
                // A queued preparation can predate invalidation, or options can lag behind the tile.
                this.cache.set(key, { tile, filters: requestFilters, pending: true, revisionMismatch: true, nextAt: received + 1000 });
                if (this.mismatchRevision !== modelRevision) {
                    // Refresh early once per known revision, then keep normal polling and server cooldowns.
                    this.nextOptionsAt = Math.min(this.nextOptionsAt, Math.max(received, this.optionsPendingAt));
                    this.pendingTurn = true;
                    this.mismatchRevision = modelRevision;
                }
            } else {
                const clean = validateTile(data, tile, requestFilters, this.options);
                const received = this.clock();
                // validForMs is the server's remaining TTL; client/server clocks may differ.
                const expires = received + Math.min(30000, clean.validForMs);
                // Revisions belong to individual tile jobs, not the whole coverage cache.
                this.cache.delete(key);
                this.cache.set(key, { data: clean, expires, tile, filters: requestFilters,
                    nextAt: clean.status === 'pending' ? received + 1000 : Math.max(received + 1000, expires) });
            }
            this.failures = 0;
            this.retryAt = 0;
            this.message = [...this.cache.values()].some(entry => entry.revisionMismatch)
                ? 'Revision de couverture modifiee : synchronisation en cours.'
                : [...this.cache.values()].some(entry => entry.pending)
                    ? 'Jeu en pause ou occupe : en attente du tick Minecraft.' : '';
        } catch (error) {
            if (generation !== this.generation || (tile && modelRevision !== this.options?.modelRevision)) return;
            if (error.deferred) {
                this.retryAt = error.retryAt;
                return;
            }
            this.failures++;
            const delay = retryDelay(error.retryAfter, this.failures, this.clock());
            this.stopped = error.retryable === false || this.failures >= 4 || !Number.isFinite(delay);
            this.retryAt = this.clock() + delay;
            this.message = this.stopped ? `${error.message}. Reessais suspendus : actualiser pour reprendre.`
                : `${error.message}. Nouvelle tentative dans ${Math.ceil((this.retryAt - this.clock()) / 1000)} s.`;
        } finally {
            this.busy = false;
            while (this.cache.size > 128) {
                const oldest = [...this.cache.keys()].find(key => !this.cache.get(key).pending
                    && this.cache.get(key).data?.status !== 'pending');
                if (oldest === undefined) break;
                this.cache.delete(oldest);
            }
        }
    }
}

import './style.css';
import { CoverageStore, COVERAGE_STYLES, signalState, coverageStep, visibleCoverageTiles, retryDelay } from './coverage.js';
import { MapImageStore } from './map-image.js';

let sessionToken = '';
let sessionGeneration = 0;
let requestQueue = Promise.resolve();
let queuedRequests = 0;
let apiNextAt = 0;
let apiRetryAt = 0;
const routeRetries = new Map();
let speedtestPending = false;
let tilesPaused = false;
const authStatus = document.getElementById('auth-status');

function deferredRequest(retryAt, message = 'Lecture differee') {
    return Object.assign(new Error(message), { deferred: true, retryAt });
}

// Five starts/second, one HTTP request in flight, and at most eight queued callers.
function apiFetch(path, options = {}) {
    if (queuedRequests >= 8) return Promise.reject(deferredRequest(Date.now() + 1000, 'HTTP request queue full'));
    queuedRequests++;
    const generation = sessionGeneration;
    const { isCurrent = () => true, ...fetchOptions } = options;
    const current = () => generation === sessionGeneration && isCurrent() && !document.hidden;
    const request = requestQueue.then(async () => {
        // Recheck after every wait: a preceding response may have deferred the entire backlog.
        while (true) {
            if (!current()) throw deferredRequest(Date.now() + 1000, 'Lecture annulee');
            if (apiRetryAt === Infinity) throw deferredRequest(Infinity);
            const delay = Math.max(apiNextAt, apiRetryAt) - Date.now();
            if (delay <= 0) break;
            await new Promise(resolve => setTimeout(resolve, Math.min(200, delay)));
        }
        if (Date.now() < (routeRetries.get(path) || 0)) throw deferredRequest(routeRetries.get(path));
        const headers = new Headers(options.headers);
        if (sessionToken) headers.set('Authorization', `Bearer ${sessionToken}`);
        apiNextAt = Date.now() + 200;
        const response = await fetch(path, { ...fetchOptions, headers, cache: 'no-store', credentials: 'omit',
            redirect: 'error', signal: AbortSignal.timeout(5000) });
        // Even stale responses must protect the shared HTTP budget.
        if ([429, 503, 504].includes(response.status)) {
            apiRetryAt = Math.max(apiRetryAt, Date.now() + retryDelay(response.headers?.get('Retry-After'), 1, Date.now()));
        }
        if (!current()) throw deferredRequest(Date.now() + 1000, 'Lecture annulee');
        routeRetries.delete(path);
        if ([202, 204].includes(response.status)) {
            routeRetries.set(path, Date.now() + retryDelay(response.headers?.get('Retry-After'), response.status === 204 ? 6 : 1, Date.now()));
            while (routeRetries.size > 128) routeRetries.delete(routeRetries.keys().next().value);
        }
        if (response.status === 401 || response.status === 403) {
            authStatus.textContent = 'Accès refusé : vérifier le token et l’origine autorisée.';
            tilesPaused = true;
        } else if ([429, 503, 504].includes(response.status)) {
            authStatus.textContent = apiRetryAt === Infinity
                ? 'Retry-After supérieur à 30 s : lectures suspendues. Revalider la session pour reprendre.'
                : 'Serveur occupé : lectures différées selon Retry-After.';
        } else if (routeRetries.get(path) === Infinity) {
            authStatus.textContent = 'Retry-After supérieur à 30 s : cette lecture est suspendue. Revalider la session pour reprendre.';
        } else if (response.status === 202) {
            authStatus.textContent = 'Jeu en pause ou occupé : en attente du tick Minecraft.';
        } else if (response.status === 204) {
            authStatus.textContent = path.startsWith('/api/map-image?')
                ? 'Carte physique vide : attente d\'une nouvelle revision.'
                : 'Zones de terrain non chargées. Nouvelle lecture après 30 s.';
        } else if (response.status === 200 && routeRetries.size === 0) {
            authStatus.textContent = sessionToken ? 'Session avec token actif.' : 'Lecture locale sans token.';
        }
        return response;
    });
    requestQueue = request.catch(() => {}).finally(() => { queuedRequests--; });
    return request;
}

function setSessionToken(value) {
    sessionToken = value;
    sessionGeneration++;
    if (apiRetryAt === Infinity) apiRetryAt = 0;
    routeRetries.clear();
    networkNextAt = nperfNextAt = playerNextAt = 0;
    tilesPaused = false;
    terrainMapId = null;
    invalidateTerrainImage();
    coverageStore.invalidate(true);
    appliedCoverageOptions = null;
    coverageFields.disabled = true;
    coverageAntenna.replaceChildren(new Option('Toutes les antennes actives', 'all'));
    coverageBand.replaceChildren(new Option('Toutes les bandes', 'all'));
    mapPointer = null;
    tooltip.style.display = 'none';
    networkData = { nodes: [], edges: [] };
    nodeMap = new Map();
    networkNodesKey = '';
    document.getElementById('stat-nodes').textContent = '0';
    document.getElementById('stat-edges').textContent = '0';
    nperfData = [];
    selectedNode = selectedEdge = hoveredNode = hoveredEdge = null;
    detailsPanel.style.display = 'none';
    authStatus.textContent = value ? 'Vérification du token...' : 'Token effacé.';
    document.getElementById('session-token').value = '';
    fetchNetworkData();
    fetchNperfData();
}

document.getElementById('session-auth').addEventListener('submit', event => {
    event.preventDefault();
    setSessionToken(document.getElementById('session-token').value.trim());
});
document.getElementById('clear-token').addEventListener('click', () => setSessionToken(''));

function escapeHtml(value) {
    return String(value).replace(/[&<>"']/g, character => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[character]));
}

const canvas = document.getElementById('network-map');
const ctx = canvas.getContext('2d');
const tooltip = document.getElementById('tooltip');
const detailsPanel = document.getElementById('details-panel');
const detailsClose = document.getElementById('details-close');
const detailsContent = document.getElementById('details-content');
const detailsTitle = document.getElementById('details-title');

let networkData = { nodes: [], edges: [] };
let nodeMap = new Map();
let networkNodesKey = '';
let pan = { x: 0, y: 0 };
let zoom = 1;
let isDragging = false;
let hasDragged = false;
let lastMouse = { x: 0, y: 0 };
let hoveredNode = null;
let selectedNode = null;
let selectedEdge = null;
let hoveredEdge = null;
let animationTime = 0;

let nperfData = [];

const coverageStore = new CoverageStore(apiFetch);
const coverageToggle = document.getElementById('cov-computed');
const coverageFields = document.getElementById('coverage-filters');
const coverageAntenna = document.getElementById('coverage-antenna');
const coverageTechnology = document.getElementById('coverage-technology');
const coverageBand = document.getElementById('coverage-band');
const coverageHeight = document.getElementById('coverage-height');
const coverageY = document.getElementById('coverage-y');
const coveragePrecision = document.getElementById('coverage-step');
const coverageStatus = document.getElementById('coverage-status');
let appliedCoverageOptions = null;
let mapPointer = null;

function updateCoverageBands() {
    const previous = coverageBand.value;
    const bands = (coverageStore.options?.bands || []).filter(b => coverageTechnology.value === 'all'
        || b.technology === coverageTechnology.value);
    coverageBand.replaceChildren(new Option('Toutes les bandes', 'all'),
        ...bands.map(b => new Option(`${b.label} (${b.technology})`, b.id)));
    coverageBand.value = bands.some(b => b.id === previous) ? previous : 'all';
}

function updateCoverageAntennas() {
    const previous = coverageAntenna.value;
    // Network IDs are signed-long strings; never round-trip them through Number.
    const antennas = networkData.nodes.filter(n => n.type === 'ANTENNA' && n.active !== false
        && typeof n.id === 'string' && /^-?\d+$/.test(n.id));
    coverageAntenna.replaceChildren(new Option('Toutes les antennes actives', 'all'),
        ...antennas.map(n => new Option(`${n.id} (${n.x}, ${n.y}, ${n.z})`, n.id)));
    coverageAntenna.value = antennas.some(n => n.id === previous) ? previous : 'all';
}

function currentCoverageFilters() {
    const options = coverageStore.options;
    let y = 'surface';
    if (coverageHeight.value === 'y') {
        const value = Number(coverageY.value);
        if (!options || !/^-?\d+$/.test(coverageY.value) || !Number.isInteger(value)
            || value < options.minY || value > options.maxY) return null;
        y = String(value);
    }
    const level = coverageView()[0]?.level ?? 0;
    return { level, precision: coveragePrecision.value, step: coverageStep(coveragePrecision.value, zoom, level), y, antenna: coverageAntenna.value,
        technology: coverageTechnology.value, band: coverageBand.value };
}

let coverageViewport = {};
function coverageView() {
    const budget = 49;
    if (coverageViewport.width !== canvas.width || coverageViewport.height !== canvas.height
        || coverageViewport.x !== pan.x || coverageViewport.z !== pan.y
        || coverageViewport.zoom !== zoom || coverageViewport.budget !== budget) {
        coverageViewport = { width: canvas.width, height: canvas.height, x: pan.x, z: pan.y, zoom, budget,
            tiles: visibleCoverageTiles({ width: canvas.width, height: canvas.height, pan, zoom }, budget) };
    }
    return coverageViewport.tiles;
}

function updateCoverageSelection() {
    tooltip.style.display = 'none';
    updateComputedCoverage();
}

for (const control of [coverageAntenna, coverageTechnology, coverageBand, coverageHeight, coveragePrecision]) {
    control.addEventListener('change', () => {
        if (control === coverageTechnology) updateCoverageBands();
        coverageY.disabled = coverageHeight.value !== 'y';
        updateCoverageSelection();
    });
}
coverageY.addEventListener('input', updateCoverageSelection);
coverageToggle.addEventListener('change', () => {
    releaseCoverageLayer();
    updateCoverageSelection();
});
document.getElementById('coverage-refresh').addEventListener('click', () => {
    for (const [path, retryAt] of routeRetries) {
        if (path.startsWith('/api/coverage') && retryAt === Infinity) routeRetries.delete(path);
    }
    coverageStore.invalidate(!coverageStore.options);
    if (coverageStore.nextOptionsAt === Infinity) coverageStore.nextOptionsAt = 0;
    tooltip.style.display = 'none';
    updateComputedCoverage();
});
document.getElementById('cov-nperf').addEventListener('change', () => {
    tooltip.style.display = 'none';
    fetchNperfData();
});
document.getElementById('show-infra').addEventListener('change', () => {
    hoveredNode = hoveredEdge = null;
    tooltip.style.display = 'none';
});

function updateComputedCoverage() {
    if (document.hidden) return;
    if (!coverageToggle.checked) {
        coverageStatus.textContent = 'Calque calculé désactivé.';
        coverageFields.disabled = true;
        return;
    }
    if (coverageStore.options && appliedCoverageOptions !== coverageStore.options) {
        appliedCoverageOptions = coverageStore.options;
        coverageY.min = String(appliedCoverageOptions.minY);
        coverageY.max = String(appliedCoverageOptions.maxY);
        coverageY.value = String(Math.max(appliedCoverageOptions.minY,
            Math.min(appliedCoverageOptions.maxY, Number(coverageY.value) || 0)));
        document.getElementById('coverage-y-range').textContent = `(${coverageY.min} à ${coverageY.max})`;
        updateCoverageBands();
    }
    coverageFields.disabled = !coverageStore.options;
    const filters = currentCoverageFilters();
    if (!filters) {
        coverageStatus.textContent = tilesPaused ? 'Couverture suspendue : vérifier la session.' : coverageStore.message
            || (coverageStore.options ? `Y entier requis entre ${coverageY.min} et ${coverageY.max}.`
                : 'Chargement des limites de hauteur...');
        // Keep polling model/height options even while fixed Y is missing or invalid.
        if (!tilesPaused) coverageStore.tick([], { step: 32, y: 'surface', antenna: 'all', technology: 'all', band: 'all' });
        return;
    }
    const tiles = coverageView();
    coverageStore.select(filters);
    if (!tilesPaused) coverageStore.tick(tiles, filters);
    const ready = tiles.filter(t => coverageStore.ready(t)).length;
    const progress = tiles.reduce((sum, t) => sum + (coverageStore.ready(t) ? 1
        : coverageStore.entry(t)?.data?.status === 'pending' ? coverageStore.entry(t).data.progress : 0), 0);
    const state = tilesPaused ? 'Couverture suspendue : vérifier la session.' : coverageStore.message
        || (!coverageStore.options ? 'Chargement des options de couverture...'
            : `${ready}/${tiles.length} tuiles prêtes, ${Math.round(progress / Math.max(1, tiles.length) * 100)} % calculé.`);
    const precision = filters.precision === 'auto' ? 'Auto recommandé'
        : `minimum demandé : ${filters.precision} bloc${filters.precision === '1' ? '' : 's'}`;
    coverageStatus.textContent = `${state} Technologie : ${filters.technology === 'all' ? 'Toutes (dominante)' : filters.technology}. Pas effectif : ${filters.step} bloc${filters.step === 1 ? '' : 's'}, ${filters.precision === 'auto' || filters.step !== Number(filters.precision) ? 'adapté au zoom' : 'précision demandée atteinte'} (LOD ${filters.level}, ${precision}), ${filters.y === 'surface' ? 'surface' : `Y ${filters.y}`}.`;
}
setInterval(updateComputedCoverage, 200);

function sampleRadius(step) {
    return step * zoom / 2;
}

let coverageLayer = null;
let coverageLayerUnavailable = false;

function releaseCoverageLayer() {
    if (!coverageLayer) return;
    coverageLayer.canvas.width = coverageLayer.canvas.height = 0;
    coverageLayer = null;
}

function drawComputedCoverage() {
    if (!coverageToggle.checked) {
        releaseCoverageLayer();
        return;
    }
    const filters = currentCoverageFilters();
    if (!filters) {
        releaseCoverageLayer();
        return;
    }
    // Select the effective grid before rendering, without cancelling old LOD jobs.
    coverageStore.select(filters);
    const tiles = coverageView();
    const ready = tiles.map(tile => coverageStore.ready(tile));
    // One viewport-sized RGBA surface, capped at 32 MiB (including a 3840x2160 viewport).
    if (!coverageLayerUnavailable && typeof OffscreenCanvas !== 'undefined'
        && canvas.width > 0 && canvas.height > 0 && canvas.width * canvas.height <= 8388608) {
        try {
            if (coverageLayer && (coverageLayer.canvas.width !== canvas.width || coverageLayer.canvas.height !== canvas.height)) {
                releaseCoverageLayer();
            }
            if (!coverageLayer) {
                coverageLayer = { canvas: new OffscreenCanvas(canvas.width, canvas.height) };
                coverageLayer.context = coverageLayer.canvas.getContext('2d');
            }
            const target = coverageLayer.context;
            if (!target || target.isContextLost?.()) throw new Error('Radio canvas unavailable');
            const key = JSON.stringify([canvas.width, canvas.height, pan.x, pan.y, zoom, filters,
                coverageStore.generation, coverageStore.options?.modelRevision]);
            // Ready data/projections are immutable per cache entry; expiry changes the reference to null.
            if (coverageLayer.key !== key || ready.length !== coverageLayer.ready.length
                || ready.some((data, i) => data !== coverageLayer.ready[i])) {
                target.clearRect(0, 0, canvas.width, canvas.height);
                paintComputedCoverage(target, tiles, ready);
                coverageLayer.key = key;
                coverageLayer.ready = ready;
            }
            ctx.drawImage(coverageLayer.canvas, 0, 0);
            return;
        } catch {
            releaseCoverageLayer();
            coverageLayerUnavailable = true;
        }
    } else {
        releaseCoverageLayer();
    }
    paintComputedCoverage(ctx, tiles, ready);
}

function paintComputedCoverage(target, tiles, ready) {
    for (let i = 0; i < tiles.length; i++) {
        const tile = tiles[i], data = ready[i];
        if (!data) {
            const size = tile.tileSize * zoom;
            const x = tile.tx * size + pan.x, z = tile.tz * size + pan.y;
            target.strokeStyle = 'rgba(148,163,184,0.45)';
            target.lineWidth = 1;
            target.setLineDash([3, 5]);
            target.strokeRect(x + 1, z + 1, size - 2, size - 2);
            target.beginPath(); target.moveTo(x, z); target.lineTo(x + size, z + size); target.stroke();
            target.setLineDash([]);
            continue;
        }
        const radius = sampleRadius(data.step);
        const centerOffset = data.step / 2 - Math.floor(data.step / 2);
        for (const cell of data.cells) {
            const x = (cell.x + centerOffset) * zoom + pan.x, z = (cell.z + centerOffset) * zoom + pan.y;
            const state = signalState(cell);
            target.fillStyle = target.strokeStyle = COVERAGE_STYLES[state].color;
            target.globalAlpha = state === 'none' || state === 'unknown' ? 0.12 : 0.4;
            target.fillRect(x - radius, z - radius, radius * 2, radius * 2);
            target.globalAlpha = 1;
            if (state === 'none' || state === 'unknown') {
                target.lineWidth = 1.5;
                target.strokeRect(x - radius, z - radius, radius * 2, radius * 2);
            }
        }
    }
}

function positionTooltip(clientX, clientY) {
    tooltip.style.left = `${Math.max(8, Math.min(clientX + 15, window.innerWidth - tooltip.offsetWidth - 8))}px`;
    tooltip.style.top = `${Math.max(8, Math.min(clientY + 15, window.innerHeight - tooltip.offsetHeight - 8))}px`;
}

function showCoverageTooltip() {
    if (!mapPointer || hoveredNode || hoveredEdge || isDragging) return;
    const rect = canvas.getBoundingClientRect();
    const x = (mapPointer.x - rect.left - pan.x) / zoom;
    const z = (mapPointer.y - rect.top - pan.y) / zoom;
    tooltip.style.display = 'none';
    if (document.getElementById('cov-nperf').checked) {
        const point = nperfData.find(p => Math.hypot(p.x - x, p.z - z) <= Math.max(1 / zoom, 5));
        if (point) {
            tooltip.textContent = `Relevé Nperf mesuré : X ${point.x}, Z ${point.z}, technologie ${point.t}, niveau ${point.s}. Ce relevé ne décrit pas la disponibilité actuelle du service.`;
            tooltip.style.display = 'block';
            positionTooltip(mapPointer.x, mapPointer.y);
            return;
        }
    }
    const filters = currentCoverageFilters();
    if (!coverageToggle.checked || !filters) return;
    const tile = coverageView().find(t => t.tx === Math.floor(x / t.tileSize) && t.tz === Math.floor(z / t.tileSize));
    if (!tile) return;
    const data = coverageStore.ready(tile);
    if (!data) {
        const entry = coverageStore.entry(tile)?.data;
        tooltip.textContent = `Couverture calculée : en attente / expirée. Technologie : ${filters.technology === 'all' ? 'Toutes (dominante)' : filters.technology}. Pas effectif : ${filters.step} bloc${filters.step === 1 ? '' : 's'}. Tuile ${tile.key}, hauteur ${filters.y}. ${entry?.status === 'pending' ? `${Math.round(entry.progress * 100)} % calculé. ` : ''}Aucune donnée radio valide affichée.`;
    } else {
        const offset = Math.floor(data.step / 2);
        const cell = data.cells.find(c => x >= c.x - offset && x < c.x - offset + data.step
            && z >= c.z - offset && z < c.z - offset + data.step);
        if (!cell) return;
        const service = { available: 'disponible', unavailable: 'indisponible', unknown: 'inconnu' }[cell.service];
        tooltip.textContent = `Zone estimée de ${data.step} × ${data.step} blocs, calcul au centre : X ${cell.x}, Y ${cell.y}, Z ${cell.z}\nSélection : ${filters.technology === 'all' ? 'Toutes (dominante)' : filters.technology}\nSignal : ${COVERAGE_STYLES[signalState(cell)].label}${cell.state === 'signal' && Number.isFinite(cell.powerDbm) ? ` (${cell.powerDbm} dBm)` : ''}\nTechnologie : ${cell.technology ?? 'inconnue'} ; bande : ${cell.band ?? 'inconnue'}\nSource du signal : antenne ${cell.antenna ?? 'inconnue'}\nService : ${service}\nRévision : ${data.revision}`;
    }
    tooltip.style.display = 'block';
    positionTooltip(mapPointer.x, mapPointer.y);
}


const COLORS = {
    SERVER: '#ef4444',
    ROUTER: '#f97316',
    ANTENNA: '#06b6d4',
    NRO: '#d946ef',
    NRA: '#84cc16',
    PM: '#eab308',
    SR: '#22c55e'
};

function resize() {
    if (initialCenterDone) {
        pan.x += (canvas.clientWidth - canvas.width) / 2;
        pan.y += (canvas.clientHeight - canvas.height) / 2;
    }
    canvas.width = canvas.clientWidth;
    canvas.height = canvas.clientHeight;
}
window.addEventListener('resize', resize);

let initialCenterDone = false;
let networkPending = false;
let networkNextAt = 0;
async function fetchNetworkData() {
    if (networkPending || document.hidden) return;
    networkPending = true;
    const generation = sessionGeneration;
    try {
        const res = await apiFetch('/api/network');
        networkNextAt = Date.now() + (res.status === 202 ? retryDelay(res.headers?.get('Retry-After'), 1, Date.now()) : 2000);
        if (res.status === 200) {
            const data = await res.json();
            if (generation !== sessionGeneration) return;
            if ((typeof data.mapId === 'string' || data.mapId === null) && data.mapId !== terrainMapId) {
                terrainMapId = data.mapId;
                invalidateTerrainImage();
                coverageStore.invalidate(true);
                appliedCoverageOptions = null;
                nperfData = [];
                routeRetries.clear();
            }
            mapImageStore.select(terrainMapId, data.mapImage);
            updateTerrain();
            networkData = data;
            const nodesKey = JSON.stringify(networkData.nodes);
            if (nodesKey !== networkNodesKey) {
                networkNodesKey = nodesKey;
                nodeMap = new Map(networkData.nodes.map(n => [n.id, n]));
            }
            updateCoverageAntennas();
            document.getElementById('stat-nodes').innerText = networkData.nodes.length;
            document.getElementById('stat-edges').innerText = networkData.edges.length;
            
            if (!initialCenterDone && networkData.nodes.length > 0) {
                let sumX = 0;
                let sumZ = 0;
                for (const n of networkData.nodes) {
                    sumX += n.x;
                    sumZ += n.z;
                }
                const avgX = sumX / networkData.nodes.length;
                const avgZ = sumZ / networkData.nodes.length;
                
                pan.x = canvas.width / 2 - (avgX * zoom);
                pan.y = canvas.height / 2 - (avgZ * zoom);
                initialCenterDone = true;
            }
            
            // Auto refresh details panel
            if (selectedNode) {
                const upToDate = networkData.nodes.find(n => n.id === selectedNode.id);
                if (upToDate) {
                    selectedNode = upToDate;
                    showNodeDetails(upToDate);
                }
            } else if (selectedEdge) {
                const upToDate = networkData.edges.find(e => e.source === selectedEdge.source && e.target === selectedEdge.target);
                if (upToDate) {
                    selectedEdge = upToDate;
                    showEdgeDetails(upToDate);
                }
            }
        }
    } catch (e) {
        networkNextAt = e.deferred ? e.retryAt : Date.now() + 2000;
        if (!e.deferred) console.warn("Could not fetch network data. Is the Minecraft server running?", e);
    } finally {
        networkPending = false;
    }
}

let playerPending = false;
let playerNextAt = 0;
async function fetchPlayerData() {
    if (!initialCenterDone && !playerPending && !document.hidden && Date.now() >= playerNextAt) {
        playerPending = true;
        try {
            const generation = sessionGeneration;
            const world = terrainGeneration;
            const res = await apiFetch('/api/player', { isCurrent: () => world === terrainGeneration });
            playerNextAt = Date.now() + retryDelay(res.headers?.get('Retry-After'), 1, Date.now());
            if (res.status === 200) {
                const p = await res.json();
                if (generation !== sessionGeneration || world !== terrainGeneration) return;
                pan.x = canvas.width / 2 - (p.x * zoom);
                pan.y = canvas.height / 2 - (p.z * zoom);
                initialCenterDone = true;
            }
        } catch (e) {
            playerNextAt = e.deferred ? e.retryAt : Date.now() + 2000;
        } finally {
            playerPending = false;
        }
    }
}
setTimeout(fetchPlayerData, 500);
setInterval(fetchPlayerData, 200);

setInterval(() => { if (Date.now() >= networkNextAt) fetchNetworkData(); }, 200);
fetchNetworkData();

canvas.addEventListener('pointerdown', e => {
    canvas.setPointerCapture(e.pointerId);
    isDragging = true;
    hasDragged = false;
    lastMouse = { x: e.clientX, y: e.clientY };
});
window.addEventListener('pointerup', () => {
    isDragging = false;
});
window.addEventListener('pointercancel', () => { isDragging = false; });

canvas.addEventListener('click', e => {
    if (hasDragged) return; // Don't open if they were panning the map
    
    if (hoveredNode) {
        selectedNode = hoveredNode;
        selectedEdge = null;
        showNodeDetails(hoveredNode);
    } else if (hoveredEdge) {
        selectedEdge = hoveredEdge;
        selectedNode = null;
        showEdgeDetails(hoveredEdge);
    } else {
        selectedNode = null;
        selectedEdge = null;
        detailsPanel.style.display = 'none';
    }
});

detailsClose.addEventListener('click', () => {
    selectedNode = null;
    selectedEdge = null;
    detailsPanel.style.display = 'none';
});

function showNodeDetails(node) {
    detailsPanel.style.display = 'flex';
    detailsTitle.innerText = `Équipement: ${node.type}`;
    detailsTitle.style.color = COLORS[node.type] || '#fff';
    
    // We use the maximum of the ratios to determine the overall load
    let loadPctDown = Math.min(100, (node.usageDown / node.capacityDown) * 100);
    let loadPctUp = Math.min(100, (node.usageUp / node.capacityUp) * 100);
    let loadPct = Math.max(loadPctDown, loadPctUp) || 0;
    
    let machines = ['SERVER', 'NRO', 'NRA', 'PM', 'SR'].includes(node.type) ? countDownstream(node) : 0;
    
    let html = `
        <div class="section-title">Informations Générales</div>
        <div class="info-row"><span class="label">Statut</span> <span>${loadPct >= 100 ? '<span style="color:#ef4444">Saturé</span>' : (loadPct > 0 ? '<span style="color:#22c55e">En Ligne</span>' : '<span style="color:#94a3b8">Inactif</span>')}</span></div>
        <div class="info-row"><span class="label">Position (X,Y,Z)</span> <span>${node.x}, ${node.y}, ${node.z}</span></div>
        <div class="info-row"><span class="label">Adresse IP</span> <span>${escapeHtml(node.ip || 'Non assignée')}</span></div>
        ${node.cidr ? `<div class="info-row"><span class="label">Réseau (CIDR)</span> <span>${escapeHtml(node.cidr)}</span></div>` : ''}
        ${machines > 0 ? `<div class="info-row"><span class="label">Appareils connectés</span> <span>${machines}</span></div>` : ''}
        
        <div class="section-title">Bande Passante (Global)</div>
        <div class="info-row"><span class="label">Capacité Descendante</span> <span class="capacity-text">${formatSpeed(node.capacityDown)}</span></div>
        <div class="info-row"><span class="label">Téléchargement (Down)</span> <span class="usage-down">${formatSpeed(node.usageDown)}</span></div>
        <div class="progress-container" style="height: 4px; margin-bottom: 12px;"><div class="progress-bar" style="width: ${loadPctDown}%; background: hsl(${120 - loadPctDown*1.2}, 100%, 50%)"></div></div>

        <div class="info-row"><span class="label">Capacité Montante</span> <span class="capacity-text">${formatSpeed(node.capacityUp)}</span></div>
        <div class="info-row"><span class="label">Envoi (Up)</span> <span class="usage-up">${formatSpeed(node.usageUp)}</span></div>
        <div class="progress-container" style="height: 4px;"><div class="progress-bar" style="width: ${loadPctUp}%; background: hsl(${120 - loadPctUp*1.2}, 100%, 50%)"></div></div>
    `;

    if (node.type === 'ANTENNA' && node.frequencies && node.frequencies.length > 0) {
        html += `<div class="section-title">Utilisation par Fréquence</div>`;
        
        for (const freq of node.frequencies) {
            let freqLoad = Math.min(100, (freq.usage / freq.max) * 100);
            let color = freq.technology === '5G' ? '#44AAFF' : (freq.technology === '4G' ? '#44DDAA' : (freq.technology === '3G' ? '#FF9944' : '#AA88FF'));
            
            html += `
                <div class="info-row" style="margin-bottom: 2px;">
                    <span class="label" style="color: ${color}">${freq.label} (${freq.technology})</span> 
                    <span>${formatSpeed(freq.usage)} / ${formatSpeed(freq.max)}</span>
                </div>
                <div class="progress-container" style="height: 4px;"><div class="progress-bar" style="width: ${freqLoad}%; background: ${freqLoad > 90 ? '#ef4444' : color}"></div></div>
            `;
        }
    }
    
    if (node.type === 'ROUTER') {
        html += `
            <div class="section-title">Speedtest Distant</div>
            <select id="speedtest-duration" class="duration-select">
                <option value="300">15 secondes</option>
                <option value="600">30 secondes</option>
                <option value="1200">60 secondes</option>
                <option value="6000">5 minutes</option>
                <option value="12000">10 minutes</option>
            </select>
            <button id="btn-speedtest" class="speedtest-btn">Démarrer Speedtest</button>
        `;
    }

    const previousDuration = document.getElementById('speedtest-duration')?.value;
    detailsContent.innerHTML = html;
    if (previousDuration && node.type === 'ROUTER') document.getElementById('speedtest-duration').value = previousDuration;

    if (node.type === 'ROUTER') {
        const btn = document.getElementById('btn-speedtest');
        btn.disabled = !sessionToken || speedtestPending;
        if (!sessionToken) btn.innerText = 'Token requis pour démarrer';
        btn.addEventListener('click', () => {
            if (speedtestPending || !sessionToken) return;
            speedtestPending = true;
            const duration = document.getElementById('speedtest-duration').value;
            btn.disabled = true;
            btn.innerText = "Démarrage...";
            apiFetch('/api/speedtest', {
                method: 'POST',
                headers: {'Content-Type': 'application/json'},
                body: JSON.stringify({ pos: node.id, duration: parseInt(duration) })
            }).then(r => {
                if(r.ok) {
                    btn.innerText = "Speedtest en cours !";
                } else {
                    btn.innerText = `Erreur HTTP ${r.status}`;
                    btn.disabled = false;
                }
            }).catch(e => {
                btn.innerText = "Erreur de connexion";
                btn.disabled = false;
            }).finally(() => { speedtestPending = false; });
        });
    }
}

function showEdgeDetails(edge) {
    detailsPanel.style.display = 'flex';
    detailsTitle.innerText = `Câble: ${edge.type}`;
    detailsTitle.style.color = '#fff';
    
    let loadPct = Math.min(100, Math.max(edge.usageDown, edge.usageUp) / edge.capacity * 100);
    
    let html = `
        <div class="section-title">Informations Câble</div>
        <div class="info-row"><span class="label">Statut</span> <span>${loadPct >= 100 ? '<span style="color:#ef4444">Saturé</span>' : (loadPct > 0 ? '<span style="color:#22c55e">Actif</span>' : '<span style="color:#94a3b8">Inactif</span>')}</span></div>
        <div class="info-row"><span class="label">Longueur</span> <span>${edge.length} blocs</span></div>
        
        <div class="section-title">Bande Passante</div>
        <div class="info-row"><span class="label">Capacité Max</span> <span class="capacity-text">${formatSpeed(edge.capacity)}</span></div>
        <div class="info-row"><span class="label">Flux Descendant</span> <span class="usage-down">${formatSpeed(edge.usageDown)}</span></div>
        <div class="info-row"><span class="label">Flux Montant</span> <span class="usage-up">${formatSpeed(edge.usageUp)}</span></div>
        
        <div class="section-title">Charge Câble</div>
        <div class="info-row"><span class="label">Saturation</span> <span>${loadPct.toFixed(1)}%</span></div>
        <div class="progress-container"><div class="progress-bar" style="width: ${loadPct}%; background: hsl(${120 - loadPct*1.2}, 100%, 50%)"></div></div>
    `;
    detailsContent.innerHTML = html;
}

function distToSegment(p, v, w) {
    const l2 = (w.x - v.x) ** 2 + (w.y - v.y) ** 2;
    if (l2 === 0) return Math.hypot(p.x - v.x, p.y - v.y);
    let t = ((p.x - v.x) * (w.x - v.x) + (p.y - v.y) * (w.y - v.y)) / l2;
    t = Math.max(0, Math.min(1, t));
    return Math.hypot(p.x - (v.x + t * (w.x - v.x)), p.y - (v.y + t * (w.y - v.y)));
}

function countDownstream(startNode) {
    let count = 0;
    const visited = new Set();
    const queue = [startNode.id];
    visited.add(startNode.id);
    
    const adj = {};
    for (const e of networkData.edges) {
        if (!adj[e.source]) adj[e.source] = [];
        if (!adj[e.target]) adj[e.target] = [];
        adj[e.source].push(e.target);
        adj[e.target].push(e.source);
    }
    
    while(queue.length > 0) {
        const currId = queue.shift();
        const currNode = nodeMap.get(currId);
        
        if (currNode && currNode.id !== startNode.id) {
            if (currNode.type === 'ROUTER' || currNode.type === 'ANTENNA') {
                count++;
            }
        }
        
        const neighbors = adj[currId] || [];
        for (const n of neighbors) {
            if (!visited.has(n)) {
                const nNode = nodeMap.get(n);
                if (nNode && nNode.capacity <= currNode.capacity) {
                    visited.add(n);
                    queue.push(n);
                }
            }
        }
    }
    return count;
}

function formatSpeed(mbps) {
    if (mbps >= 1000) return (mbps / 1000).toFixed(1) + ' Gbps';
    return mbps + ' Mbps';
}

window.addEventListener('pointermove', e => {
    if (isDragging) {
        pan.x += e.clientX - lastMouse.x;
        pan.y += e.clientY - lastMouse.y;
        lastMouse = { x: e.clientX, y: e.clientY };
        hasDragged = true;
    }
    
    const rect = canvas.getBoundingClientRect();
    const mouseX = e.clientX - rect.left;
    const mouseY = e.clientY - rect.top;
    
    hoveredNode = null;
    hoveredEdge = null;

    if (e.target !== canvas || mouseX < 0 || mouseY < 0 || mouseX > canvas.width || mouseY > canvas.height) {
        mapPointer = null;
        tooltip.style.display = 'none';
        document.body.style.cursor = '';
        return;
    }
    mapPointer = { x: e.clientX, y: e.clientY };

    for (const node of document.getElementById('show-infra').checked ? networkData.nodes : []) {
        const nx = node.x * zoom + pan.x;
        const ny = node.z * zoom + pan.y;
        const dist = Math.hypot(mouseX - nx, mouseY - ny);
        const radius = node.type === 'SERVER' ? 12 : (node.type === 'ANTENNA' ? 10 : 8);
        if (dist <= radius * 1.5) {
            hoveredNode = node;
            break;
        }
    }
    
    if (!hoveredNode && document.getElementById('show-infra').checked) {
        for (const edge of networkData.edges) {
            const n1 = nodeMap.get(edge.source);
            const n2 = nodeMap.get(edge.target);
            if (n1 && n2) {
                const p1 = { x: n1.x * zoom + pan.x, y: n1.z * zoom + pan.y };
                const p2 = { x: n2.x * zoom + pan.x, y: n2.z * zoom + pan.y };
                const dist = distToSegment({x: mouseX, y: mouseY}, p1, p2);
                if (dist < 6) {
                    hoveredEdge = edge;
                    break;
                }
            }
        }
    }
    
    // Simple tooltip with just the name
    if (hoveredNode) {
        tooltip.style.display = 'block';
        tooltip.style.left = (e.clientX + 15) + 'px';
        tooltip.style.top = (e.clientY + 15) + 'px';
        tooltip.innerHTML = `<div class="title" style="color: ${COLORS[hoveredNode.type]}; border: none; padding: 0; margin: 0;">${hoveredNode.type} <span style="font-size: 0.75rem; color: #94a3b8">(Clic pour détails)</span></div>`;
        document.body.style.cursor = 'pointer';
    } else if (hoveredEdge) {
        tooltip.style.display = 'block';
        tooltip.style.left = (e.clientX + 15) + 'px';
        tooltip.style.top = (e.clientY + 15) + 'px';
        let loadPct = Math.min(100, Math.max(hoveredEdge.usageDown, hoveredEdge.usageUp) / hoveredEdge.capacity * 100);
        let color = `hsl(${120 - loadPct*1.2}, 100%, 50%)`;
        if(loadPct === 0) color = '#94a3b8';
        tooltip.innerHTML = `<div class="title" style="color: ${color}; border: none; padding: 0; margin: 0;">Câble ${hoveredEdge.type} <span style="font-size: 0.75rem; color: #94a3b8">(Clic pour détails)</span></div>`;
        document.body.style.cursor = 'pointer';
    } else {
        tooltip.style.display = 'none';
        document.body.style.cursor = isDragging ? 'grabbing' : 'grab';
        showCoverageTooltip();
    }
    if (tooltip.style.display !== 'none') positionTooltip(e.clientX, e.clientY);
});

canvas.addEventListener('pointerleave', () => {
    mapPointer = null;
    hoveredNode = hoveredEdge = null;
    tooltip.style.display = 'none';
});

function zoomAt(factor, x, y) {
    const nextZoom = Math.max(0.25, Math.min(32, zoom * factor));
    const ratio = nextZoom / zoom;
    zoom = nextZoom;
    pan.x = x - (x - pan.x) * ratio;
    pan.y = y - (y - pan.y) * ratio;
    updateComputedCoverage();
}
document.getElementById('zoom-in').addEventListener('click', () => zoomAt(1.5, canvas.width / 2, canvas.height / 2));
document.getElementById('zoom-out').addEventListener('click', () => zoomAt(1 / 1.5, canvas.width / 2, canvas.height / 2));

canvas.addEventListener('wheel', e => {
    e.preventDefault();
    const rect = canvas.getBoundingClientRect();
    const mouseX = e.clientX - rect.left;
    const mouseY = e.clientY - rect.top;
    
    zoomAt(e.deltaY < 0 ? 1.1 : 0.9, mouseX, mouseY);
});

const mapImageStore = new MapImageStore(apiFetch, { decode: blob => createImageBitmap(blob), now: () => Date.now() });
let terrainMapId = null;
let terrainGeneration = 0;
const terrainStatus = document.getElementById('terrain-status');

function invalidateTerrainImage() {
    terrainGeneration++;
    mapImageStore.invalidate();
    for (const path of routeRetries.keys()) if (path.startsWith('/api/map-image?')) routeRetries.delete(path);
}

function updateTerrain() {
    if (document.hidden) return;
    const snapshot = mapImageStore.snapshot;
    terrainStatus.textContent = `${mapImageStore.message}${snapshot
        ? ` Dernier snapshot valide affiche : pas raster ${snapshot.blocksPerPixel} blocs/pixel (blocksPerPixel). Detail limite par cette resolution, sans chargement au zoom.` : ''}`;
    if (!tilesPaused && Date.now() >= apiRetryAt) mapImageStore.tick();
}
setInterval(updateTerrain, 200);


const nperfColors = {
    1: ['rgba(59, 130, 246, 0.25)', 'rgba(59, 130, 246, 0.5)', 'rgba(59, 130, 246, 0.75)', 'rgba(59, 130, 246, 1)'], // Blue (2G)
    2: ['rgba(34, 197, 94, 0.25)', 'rgba(34, 197, 94, 0.5)', 'rgba(34, 197, 94, 0.75)', 'rgba(34, 197, 94, 1)'],   // Green (3G)
    3: ['rgba(249, 115, 22, 0.25)', 'rgba(249, 115, 22, 0.5)', 'rgba(249, 115, 22, 0.75)', 'rgba(249, 115, 22, 1)'], // Orange (4G)
    4: ['rgba(239, 68, 68, 0.25)', 'rgba(239, 68, 68, 0.5)', 'rgba(239, 68, 68, 0.75)', 'rgba(239, 68, 68, 1)'],   // Red (4G+)
    5: ['rgba(168, 85, 247, 0.25)', 'rgba(168, 85, 247, 0.5)', 'rgba(168, 85, 247, 0.75)', 'rgba(168, 85, 247, 1)']  // Purple (5G)
};

let nperfPending = false;
let nperfNextAt = 0;
async function fetchNperfData() {
    if (nperfPending || document.hidden || !document.getElementById('cov-nperf').checked) return;
    nperfPending = true;
    const generation = sessionGeneration;
    const world = terrainGeneration;
    try {
        const response = await apiFetch('/api/nperf_map', { isCurrent: () => world === terrainGeneration });
        nperfNextAt = Date.now() + (response.status === 202 ? retryDelay(response.headers?.get('Retry-After'), 1, Date.now()) : 2000);
        if (response.status !== 200) return;
        const data = await response.json();
        if (generation !== sessionGeneration || world !== terrainGeneration) return;
        nperfData = data;
        
        if (!initialCenterDone && nperfData.length > 0) {
            let sumX = 0;
            let sumZ = 0;
            for (const p of nperfData) {
                sumX += p.x;
                sumZ += p.z;
            }
            pan.x = canvas.width / 2 - ((sumX / nperfData.length) * zoom);
            pan.y = canvas.height / 2 - ((sumZ / nperfData.length) * zoom);
            initialCenterDone = true;
        }
    } catch (e) {
        nperfNextAt = e.deferred ? e.retryAt : Date.now() + 2000;
        if (!e.deferred) console.error(e);
    } finally {
        nperfPending = false;
    }
}
setInterval(() => { if (Date.now() >= nperfNextAt) fetchNperfData(); }, 200);
fetchNperfData();

function drawNperfCoverage() {
    const cb = document.getElementById('cov-nperf');
    if (!cb || !cb.checked || !nperfData) return;

    for (const point of nperfData) {
        const sx = (point.x * zoom) + pan.x;
        const sy = (point.z * zoom) + pan.y;
        const radius = Math.max(1, 5 * zoom);

        if (sx + radius < 0 || sx - radius > canvas.width || sy + radius < 0 || sy - radius > canvas.height) continue;

        const colors = nperfColors[point.t];
        if (colors) {
            ctx.fillStyle = colors[point.s - 1] || colors[0];
            ctx.beginPath();
            ctx.arc(sx, sy, radius, 0, Math.PI * 2);
            ctx.fill();
        }
    }
}


let animationFrame;
const reducedMotion = window.matchMedia?.('(prefers-reduced-motion: reduce)');
function draw() {
    if (document.hidden) return;
    if (!reducedMotion?.matches) animationTime += 0.05;
    ctx.imageSmoothingEnabled = false;
    ctx.clearRect(0, 0, canvas.width, canvas.height);
    
    mapImageStore.draw(ctx, pan, zoom);



    ctx.strokeStyle = 'rgba(255,255,255,0.05)';
    ctx.lineWidth = 1;
    const gridSize = 16 * zoom;
    const offsetX = (pan.x % gridSize + gridSize) % gridSize;
    const offsetY = (pan.y % gridSize + gridSize) % gridSize;
    
    ctx.beginPath();
    for (let x = offsetX; x < canvas.width; x += gridSize) {
        ctx.moveTo(x, 0); ctx.lineTo(x, canvas.height);
    }
    for (let y = offsetY; y < canvas.height; y += gridSize) {
        ctx.moveTo(0, y); ctx.lineTo(canvas.width, y);
    }
    ctx.stroke();

    drawComputedCoverage();
    drawNperfCoverage();

    const showInfra = document.getElementById('show-infra');
    if (!showInfra || showInfra.checked) {
        // SMALLER CABLES!
        ctx.lineWidth = Math.max(0.5, 1 * zoom);
        
        for (const edge of networkData.edges) {
            const n1 = nodeMap.get(edge.source);
            const n2 = nodeMap.get(edge.target);
            
            if (n1 && n2) {
                const x1 = n1.x * zoom + pan.x;
                const y1 = n1.z * zoom + pan.y;
                const x2 = n2.x * zoom + pan.x;
                const y2 = n2.z * zoom + pan.y;
                if (Math.max(x1, x2) < -20 || Math.min(x1, x2) > canvas.width + 20
                    || Math.max(y1, y2) < -20 || Math.min(y1, y2) > canvas.height + 20) continue;
                
                let maxUsage = Math.max(edge.usageDown, edge.usageUp);
                let loadPct = Math.min(100, (maxUsage / edge.capacity) * 100);
                
                // COLOR CHANGE LOGIC
                let hue = 120 - (loadPct * 1.2); // 120 is Green, 0 is Red
                
                if (maxUsage === 0) {
                    // If 0 usage, make it dull transparent grey
                    ctx.strokeStyle = (hoveredEdge === edge) ? 'rgba(255, 255, 255, 0.8)' : 'rgba(255, 255, 255, 0.5)';
                } else {
                    // If traffic passing, color it from green to red based on saturation
                    ctx.strokeStyle = `hsla(${hue}, 100%, 50%, ${(hoveredEdge === edge) ? 1 : 0.8})`;
                }
                
                ctx.setLineDash([]);
                ctx.beginPath();
                ctx.moveTo(x1, y1);
                ctx.lineTo(x2, y2);
                ctx.stroke();
                
                if (maxUsage > 0 && loadPct < 100) {
                    let speed = 1 + (loadPct / 100) * 5;
                    // Dash animation using a brighter color or white to represent packets
                    ctx.strokeStyle = 'rgba(255, 255, 255, 0.8)';
                    ctx.lineWidth = Math.max(0.3, 0.5 * zoom);
                    ctx.setLineDash([4 * zoom, 12 * zoom]);
                    ctx.lineDashOffset = -animationTime * speed; 
                    ctx.beginPath();
                    ctx.moveTo(x1, y1);
                    ctx.lineTo(x2, y2);
                    ctx.stroke();
                    // Reset line width for next edge
                    ctx.lineWidth = Math.max(0.5, 1 * zoom);
                }
                ctx.setLineDash([]);
            }
        }
    }
    
    if (!showInfra || showInfra.checked) {
        for (const node of networkData.nodes) {
            const x = node.x * zoom + pan.x;
            const y = node.z * zoom + pan.y;
            if (x < -128 || y < -128 || x > canvas.width + 128 || y > canvas.height + 128) continue;
            
            const isHovered = hoveredNode && hoveredNode.id === node.id;
            let baseRadius = node.type === 'SERVER' ? 8 : (node.type === 'ANTENNA' ? 6 : 5);
            const radius = Math.max(3, baseRadius * Math.min(2, Math.max(0.5, zoom))) * (isHovered ? 1.5 : 1);
            
            const color = COLORS[node.type] || '#ffffff';
            
            ctx.shadowColor = color;
            ctx.shadowBlur = isHovered ? 20 : (node.usageDown > 0 ? 10 : 0);
            
            ctx.fillStyle = color;
            ctx.beginPath();
            ctx.arc(x, y, radius, 0, Math.PI * 2);
            ctx.fill();
            
            ctx.shadowBlur = 0;
            
            ctx.strokeStyle = '#0f172a';
            ctx.lineWidth = 2;
            ctx.stroke();
            
            // Pulse animation if there is traffic
            let maxUsage = Math.max(node.usageDown, node.usageUp);
            if (maxUsage > 0 && node.capacity > 0) {
                let loadPct = Math.min(100, (maxUsage / node.capacity) * 100);
                let speed = 1 + (loadPct / 100) * 5;
                let pulseTime = (animationTime * speed) % 2; // 0 to 2
                
                if (pulseTime < 1) {
                    ctx.beginPath();
                    ctx.arc(x, y, radius + (pulseTime * radius * 3), 0, Math.PI * 2);
                    ctx.strokeStyle = `rgba(255, 255, 255, ${0.5 * (1 - pulseTime)})`;
                    ctx.lineWidth = 1;
                    ctx.stroke();
                }
            }
        }
    }
    
    showCoverageTooltip();
    animationFrame = requestAnimationFrame(draw);
}

resize();
animationFrame = requestAnimationFrame(draw);
document.addEventListener('visibilitychange', () => {
    cancelAnimationFrame(animationFrame);
    if (!document.hidden) {
        resize();
        updateComputedCoverage();
        fetchNetworkData();
        fetchNperfData();
        animationFrame = requestAnimationFrame(draw);
    } else {
        mapPointer = null;
        tooltip.style.display = 'none';
    }
});
if (typeof ResizeObserver !== 'undefined') new ResizeObserver(resize).observe(canvas);

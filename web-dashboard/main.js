import './style.css';

const canvas = document.getElementById('network-map');
const ctx = canvas.getContext('2d');
const tooltip = document.getElementById('tooltip');
const detailsPanel = document.getElementById('details-panel');
const detailsClose = document.getElementById('details-close');
const detailsContent = document.getElementById('details-content');
const detailsTitle = document.getElementById('details-title');

let networkData = { nodes: [], edges: [] };
let pan = { x: 0, y: 0 };
let zoom = 1;
let isDragging = false;
let hasDragged = false;
let lastMouse = { x: 0, y: 0 };
let hoveredNode = null;
let hoveredEdge = null;
let animationTime = 0;

let nperfData = [];
let fetchBudget = 10;


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
    canvas.width = canvas.clientWidth;
    canvas.height = canvas.clientHeight;
}
window.addEventListener('resize', resize);

let initialCenterDone = false;
async function fetchNetworkData() {
    try {
        const res = await fetch('/api/network?t=' + Date.now());
        if (res.ok) {
            networkData = await res.json();
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
        }
    } catch (e) {
        console.warn("Could not fetch network data. Is the Minecraft server running?", e);
    }
}

setTimeout(async () => {
    if (!initialCenterDone) {
        try {
            const res = await fetch('/api/player?t=' + Date.now());
            if (res.ok) {
                const p = await res.json();
                pan.x = canvas.width / 2 - (p.x * zoom);
                pan.y = canvas.height / 2 - (p.z * zoom);
                initialCenterDone = true;
            } else {
                pan.x = canvas.width / 2;
                pan.y = canvas.height / 2;
            }
        } catch (e) {
            pan.x = canvas.width / 2;
            pan.y = canvas.height / 2;
        }
    }
}, 500);

setInterval(fetchNetworkData, 2000);
fetchNetworkData();

canvas.addEventListener('mousedown', e => {
    isDragging = true;
    hasDragged = false;
    lastMouse = { x: e.clientX, y: e.clientY };
});
window.addEventListener('mouseup', () => {
    isDragging = false;
});

canvas.addEventListener('click', e => {
    if (hasDragged) return; // Don't open if they were panning the map
    
    if (hoveredNode) {
        showNodeDetails(hoveredNode);
    } else if (hoveredEdge) {
        showEdgeDetails(hoveredEdge);
    } else {
        detailsPanel.style.display = 'none';
    }
});

detailsClose.addEventListener('click', () => {
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
        <div class="info-row"><span class="label">Adresse IP</span> <span>${node.ip || 'Non assignée'}</span></div>
        ${node.cidr ? `<div class="info-row"><span class="label">Réseau (CIDR)</span> <span>${node.cidr}</span></div>` : ''}
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

    detailsContent.innerHTML = html;

    if (node.type === 'ROUTER') {
        const btn = document.getElementById('btn-speedtest');
        btn.addEventListener('click', () => {
            const duration = document.getElementById('speedtest-duration').value;
            btn.disabled = true;
            btn.innerText = "Démarrage...";
            fetch('/api/speedtest', {
                method: 'POST',
                headers: {'Content-Type': 'application/json'},
                body: JSON.stringify({ pos: node.id, duration: parseInt(duration), maxDown: node.capacityDown, maxUp: node.capacityUp })
            }).then(r => {
                if(r.ok) {
                    btn.innerText = "Speedtest en cours !";
                } else {
                    btn.innerText = "Erreur";
                    btn.disabled = false;
                }
            }).catch(e => {
                btn.innerText = "Erreur de connexion";
                btn.disabled = false;
            });
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
    
    const nodeMap = new Map(networkData.nodes.map(n => [n.id, n]));
    
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

window.addEventListener('mousemove', e => {
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
    
    drawCoverage();

    for (const node of networkData.nodes) {
        const nx = node.x * zoom + pan.x;
        const ny = node.z * zoom + pan.y;
        const dist = Math.hypot(mouseX - nx, mouseY - ny);
        const radius = node.type === 'SERVER' ? 12 : (node.type === 'ANTENNA' ? 10 : 8);
        if (dist <= radius * 1.5) {
            hoveredNode = node;
            break;
        }
    }
    
    if (!hoveredNode) {
        const nodeMap = new Map(networkData.nodes.map(n => [n.id, n]));
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
    }
});

canvas.addEventListener('wheel', e => {
    e.preventDefault();
    const rect = canvas.getBoundingClientRect();
    const mouseX = e.clientX - rect.left;
    const mouseY = e.clientY - rect.top;
    
    const wheel = e.deltaY < 0 ? 1.1 : 0.9;
    zoom *= wheel;
    
    pan.x = mouseX - (mouseX - pan.x) * wheel;
    pan.y = mouseY - (mouseY - pan.y) * wheel;
});

let tileCache = new Map();


const nperfColors = {
    1: ['rgba(59, 130, 246, 0.25)', 'rgba(59, 130, 246, 0.5)', 'rgba(59, 130, 246, 0.75)', 'rgba(59, 130, 246, 1)'], // Blue (2G)
    2: ['rgba(34, 197, 94, 0.25)', 'rgba(34, 197, 94, 0.5)', 'rgba(34, 197, 94, 0.75)', 'rgba(34, 197, 94, 1)'],   // Green (3G)
    3: ['rgba(249, 115, 22, 0.25)', 'rgba(249, 115, 22, 0.5)', 'rgba(249, 115, 22, 0.75)', 'rgba(249, 115, 22, 1)'], // Orange (4G)
    4: ['rgba(239, 68, 68, 0.25)', 'rgba(239, 68, 68, 0.5)', 'rgba(239, 68, 68, 0.75)', 'rgba(239, 68, 68, 1)'],   // Red (4G+)
    5: ['rgba(168, 85, 247, 0.25)', 'rgba(168, 85, 247, 0.5)', 'rgba(168, 85, 247, 0.75)', 'rgba(168, 85, 247, 1)']  // Purple (5G)
};

async function fetchNperfData() {
    try {
        const response = await fetch('/api/nperf_map?t=' + Date.now());
        if (!response.ok) return;
        nperfData = await response.json();
        
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
        console.error(e);
    }
}
setInterval(fetchNperfData, 2000);
fetchNperfData();

function drawCoverage() {
    const cb = document.getElementById('cov-nperf');
    if (!cb || !cb.checked || !nperfData) return;

    for (const point of nperfData) {
        const sx = (point.x * zoom) + pan.x;
        const sy = (point.z * zoom) + pan.y;
        const size = Math.max(1, zoom);

        if (sx + size < 0 || sx > canvas.width || sy + size < 0 || sy > canvas.height) continue;

        const colors = nperfColors[point.t];
        if (colors) {
            ctx.fillStyle = colors[point.s - 1] || colors[0];
            ctx.fillRect(sx, sy, size, size);
        }
    }
}


function draw() {
    animationTime += 0.05;
    ctx.imageSmoothingEnabled = false;
    ctx.clearRect(0, 0, canvas.width, canvas.height);
    
    let minCx = Math.floor((-pan.x) / (16 * zoom));
    let minCz = Math.floor((-pan.y) / (16 * zoom));
    let maxCx = Math.floor((canvas.width - pan.x) / (16 * zoom));
    let maxCz = Math.floor((canvas.height - pan.y) / (16 * zoom));

    const centerCx = Math.floor((minCx + maxCx) / 2);
    const centerCz = Math.floor((minCz + maxCz) / 2);
    const radius = 20;
    
    if (minCx < centerCx - radius) minCx = centerCx - radius;
    if (maxCx > centerCx + radius) maxCx = centerCx + radius;
    if (minCz < centerCz - radius) minCz = centerCz - radius;
    if (maxCz > centerCz + radius) maxCz = centerCz + radius;

    
    for (let cx = minCx; cx <= maxCx; cx++) {
        for (let cz = minCz; cz <= maxCz; cz++) {
            const key = `${cx},${cz}`;
            if (!tileCache.has(key)) {
                if (fetchBudget > 0) {
                    fetchBudget--;
                    tileCache.set(key, null);
                    const img = new Image();
                    img.crossOrigin = "Anonymous";
                    img.src = `/api/tile?cx=${cx}&cz=${cz}&t=${Date.now()}`;
                    img.onload = () => tileCache.set(key, img);
                    img.onerror = () => tileCache.set(key, false);
                }
            } else {
                const img = tileCache.get(key);
                if (img && img !== false) {
                    ctx.drawImage(img, cx * 16 * zoom + pan.x, cz * 16 * zoom + pan.y, 16.2 * zoom, 16.2 * zoom);
                }
            }
        }
    }



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

    const nodeMap = new Map(networkData.nodes.map(n => [n.id, n]));
    
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
    
    drawCoverage();

    for (const node of networkData.nodes) {
        const x = node.x * zoom + pan.x;
        const y = node.z * zoom + pan.y;
        
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
    }
    
    requestAnimationFrame(draw);
}

resize();
requestAnimationFrame(draw);

setInterval(() => {
    fetchBudget = Math.min(fetchBudget + 5, 20);
}, 100);

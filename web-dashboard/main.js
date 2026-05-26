import './style.css';

const canvas = document.getElementById('network-map');
const ctx = canvas.getContext('2d');
const tooltip = document.getElementById('tooltip');

let networkData = { nodes: [], edges: [] };
let pan = { x: 0, y: 0 };
let zoom = 1;
let isDragging = false;
let lastMouse = { x: 0, y: 0 };
let hoveredNode = null;
let hoveredEdge = null;
let animationTime = 0;

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

async function fetchNetworkData() {
    try {
        const res = await fetch('/api/network');
        if (res.ok) {
            networkData = await res.json();
            document.getElementById('stat-nodes').innerText = networkData.nodes.length;
            document.getElementById('stat-edges').innerText = networkData.edges.length;
        }
    } catch (e) {
        console.warn("Could not fetch network data. Is the Minecraft server running?", e);
    }
}

setTimeout(() => {
    pan.x = canvas.width / 2;
    pan.y = canvas.height / 2;
}, 100);

setInterval(fetchNetworkData, 2000);
fetchNetworkData();

canvas.addEventListener('mousedown', e => {
    isDragging = true;
    lastMouse = { x: e.clientX, y: e.clientY };
});
window.addEventListener('mouseup', () => isDragging = false);

// Distance from point to line segment
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
                // Go downwards in capacity (hierarchy)
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
    }
    
    const rect = canvas.getBoundingClientRect();
    const mouseX = e.clientX - rect.left;
    const mouseY = e.clientY - rect.top;
    
    hoveredNode = null;
    hoveredEdge = null;
    
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
    
    if (hoveredNode) {
        tooltip.style.display = 'block';
        tooltip.style.left = (e.clientX + 15) + 'px';
        tooltip.style.top = (e.clientY + 15) + 'px';
        
        let loadPct = Math.min(100, Math.max(hoveredNode.usageDown, hoveredNode.usageUp) / hoveredNode.capacity * 100);
        let countHtml = '';
        if (['SERVER', 'NRO', 'NRA', 'PM', 'SR'].includes(hoveredNode.type)) {
            const machines = countDownstream(hoveredNode);
            countHtml = `<div class="info-row"><span class="label">Machines liées:</span> <span>${machines}</span></div>`;
        }
        
        let html = `<div class="title" style="color: ${COLORS[hoveredNode.type]}">${hoveredNode.type}</div>`;
        html += `<div class="info-row"><span class="label">IP:</span> <span>${hoveredNode.ip || 'N/A'}</span></div>`;
        html += countHtml;
        html += `<div class="info-row"><span class="label">Down:</span> <span class="usage-down">${formatSpeed(hoveredNode.usageDown)}</span></div>`;
        html += `<div class="info-row"><span class="label">Up:</span> <span class="usage-up">${formatSpeed(hoveredNode.usageUp)}</span></div>`;
        html += `<div class="info-row"><span class="label">Capacité:</span> <span class="capacity-text">${formatSpeed(hoveredNode.capacity)}</span></div>`;
        html += `<div class="progress-container"><div class="progress-bar" style="width: ${loadPct}%; background: ${loadPct > 90 ? '#ef4444' : '#38bdf8'}"></div></div>`;
        
        tooltip.innerHTML = html;
        document.body.style.cursor = 'pointer';
    } else if (hoveredEdge) {
        tooltip.style.display = 'block';
        tooltip.style.left = (e.clientX + 15) + 'px';
        tooltip.style.top = (e.clientY + 15) + 'px';
        
        let loadPct = Math.min(100, Math.max(hoveredEdge.usageDown, hoveredEdge.usageUp) / hoveredEdge.capacity * 100);
        
        let html = `<div class="title" style="color: #ffffff">Câble ${hoveredEdge.type}</div>`;
        html += `<div class="info-row"><span class="label">Down:</span> <span class="usage-down">${formatSpeed(hoveredEdge.usageDown)}</span></div>`;
        html += `<div class="info-row"><span class="label">Up:</span> <span class="usage-up">${formatSpeed(hoveredEdge.usageUp)}</span></div>`;
        html += `<div class="info-row"><span class="label">Capacité:</span> <span class="capacity-text">${formatSpeed(hoveredEdge.capacity)}</span></div>`;
        html += `<div class="progress-container"><div class="progress-bar" style="width: ${loadPct}%; background: ${loadPct > 90 ? '#ef4444' : '#38bdf8'}"></div></div>`;
        
        tooltip.innerHTML = html;
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

function draw() {
    animationTime++;
    ctx.imageSmoothingEnabled = false;
    ctx.clearRect(0, 0, canvas.width, canvas.height);
    
    let minCx = Math.floor((-pan.x) / (16 * zoom));
    let minCz = Math.floor((-pan.y) / (16 * zoom));
    let maxCx = Math.floor((canvas.width - pan.x) / (16 * zoom));
    let maxCz = Math.floor((canvas.height - pan.y) / (16 * zoom));

    const centerCx = Math.floor((minCx + maxCx) / 2);
    const centerCz = Math.floor((minCz + maxCz) / 2);
    const radius = 150;
    
    if (minCx < centerCx - radius) minCx = centerCx - radius;
    if (maxCx > centerCx + radius) maxCx = centerCx + radius;
    if (minCz < centerCz - radius) minCz = centerCz - radius;
    if (maxCz > centerCz + radius) maxCz = centerCz + radius;

    let fetchBudget = 20;

    for (let cx = minCx; cx <= maxCx; cx++) {
        for (let cz = minCz; cz <= maxCz; cz++) {
            const key = `${cx},${cz}`;
            if (!tileCache.has(key)) {
                if (fetchBudget > 0) {
                    fetchBudget--;
                    tileCache.set(key, null);
                    const img = new Image();
                    img.crossOrigin = "Anonymous";
                    img.src = `/api/tile?cx=${cx}&cz=${cz}`;
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
    
    ctx.lineWidth = Math.max(2, 3 * zoom);
    
    for (const edge of networkData.edges) {
        const n1 = nodeMap.get(edge.source);
        const n2 = nodeMap.get(edge.target);
        
        if (n1 && n2) {
            const x1 = n1.x * zoom + pan.x;
            const y1 = n1.z * zoom + pan.y;
            const x2 = n2.x * zoom + pan.x;
            const y2 = n2.z * zoom + pan.y;
            
            let maxUsage = Math.max(edge.usageDown, edge.usageUp);
            let isSaturated = maxUsage > edge.capacity;
            
            ctx.strokeStyle = (hoveredEdge === edge) ? 'rgba(255, 255, 255, 0.8)' : 'rgba(255, 255, 255, 0.2)';
            if (isSaturated) ctx.strokeStyle = 'rgba(239, 68, 68, 0.5)';
            
            ctx.setLineDash([]);
            ctx.beginPath();
            ctx.moveTo(x1, y1);
            ctx.lineTo(x2, y2);
            ctx.stroke();
            
            if (maxUsage > 0 && !isSaturated) {
                let speed = 1 + (maxUsage / edge.capacity) * 5;
                ctx.strokeStyle = edge.type.includes('FIBER') ? '#38bdf8' : '#f59e0b';
                ctx.lineWidth = Math.max(1, 2 * zoom);
                ctx.setLineDash([5 * zoom, 15 * zoom]);
                ctx.lineDashOffset = -animationTime * speed; 
                ctx.beginPath();
                ctx.moveTo(x1, y1);
                ctx.lineTo(x2, y2);
                ctx.stroke();
            }
            ctx.setLineDash([]);

            if (isSaturated) {
                const cx = (x1 + x2) / 2;
                const cy = (y1 + y2) / 2;
                ctx.fillStyle = '#ef4444';
                ctx.beginPath();
                ctx.arc(cx, cy, 3 * zoom, 0, Math.PI * 2);
                ctx.fill();
            }
        }
    }
    
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

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
    draw();
}

window.addEventListener('resize', resize);

async function fetchNetworkData() {
    try {
        const res = await fetch('http://localhost:8080/api/network');
        if (res.ok) {
            networkData = await res.json();
            document.getElementById('stat-nodes').innerText = networkData.nodes.length;
            document.getElementById('stat-edges').innerText = networkData.edges.length;
            draw();
        }
    } catch (e) {
        console.warn("Could not fetch network data. Is the Minecraft server running?", e);
    }
}

// Initial pan to center
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

window.addEventListener('mousemove', e => {
    if (isDragging) {
        pan.x += e.clientX - lastMouse.x;
        pan.y += e.clientY - lastMouse.y;
        lastMouse = { x: e.clientX, y: e.clientY };
        draw();
    }
    
    // Check hover
    const rect = canvas.getBoundingClientRect();
    const mouseX = e.clientX - rect.left;
    const mouseY = e.clientY - rect.top;
    
    hoveredNode = null;
    
    for (const node of networkData.nodes) {
        const nx = node.x * zoom + pan.x;
        const ny = node.z * zoom + pan.y; // Z axis in minecraft is Y on screen
        
        const dist = Math.hypot(mouseX - nx, mouseY - ny);
        const radius = node.type === 'SERVER' ? 12 : (node.type === 'ANTENNA' ? 10 : 8);
        
        if (dist <= radius) {
            hoveredNode = node;
            break;
        }
    }
    
    if (hoveredNode) {
        tooltip.style.display = 'block';
        tooltip.style.left = e.clientX + 'px';
        tooltip.style.top = e.clientY + 'px';
        
        let html = `<div class="title" style="color: ${COLORS[hoveredNode.type]}">${hoveredNode.type}</div>`;
        html += `<div class="info-row"><span class="label">Pos:</span> <span>${hoveredNode.x}, ${hoveredNode.y}, ${hoveredNode.z}</span></div>`;
        if (hoveredNode.ip) {
            html += `<div class="info-row"><span class="label">IP:</span> <span>${hoveredNode.ip}</span></div>`;
        }
        if (hoveredNode.technologies && hoveredNode.technologies.length > 0) {
            html += `<div class="info-row"><span class="label">Tech:</span> <span>`;
            hoveredNode.technologies.forEach(t => {
                html += `<span class="tech-badge">${t}</span>`;
            });
            html += `</span></div>`;
        }
        tooltip.innerHTML = html;
        document.body.style.cursor = 'pointer';
    } else {
        tooltip.style.display = 'none';
        document.body.style.cursor = isDragging ? 'grabbing' : 'grab';
    }
    
    draw();
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
    
    draw();
});

let tileCache = new Map();

function draw() {
    ctx.imageSmoothingEnabled = false; // keep pixels sharp
    ctx.clearRect(0, 0, canvas.width, canvas.height);
    
    // Calculate visible chunks
    let minCx = Math.floor((-pan.x) / (16 * zoom));
    let minCz = Math.floor((-pan.y) / (16 * zoom));
    let maxCx = Math.floor((canvas.width - pan.x) / (16 * zoom));
    let maxCz = Math.floor((canvas.height - pan.y) / (16 * zoom));

    // Cap the range to prevent JS freezing if zoomed out too much (e.g., limit to 200x200 chunks)
    const centerCx = Math.floor((minCx + maxCx) / 2);
    const centerCz = Math.floor((minCz + maxCz) / 2);
    const radius = 150;
    
    if (minCx < centerCx - radius) minCx = centerCx - radius;
    if (maxCx > centerCx + radius) maxCx = centerCx + radius;
    if (minCz < centerCz - radius) minCz = centerCz - radius;
    if (maxCz > centerCz + radius) maxCz = centerCz + radius;

    let fetchBudget = 200; // max new tiles to request per frame

    for (let cx = minCx; cx <= maxCx; cx++) {
        for (let cz = minCz; cz <= maxCz; cz++) {
            const key = `${cx},${cz}`;
            if (!tileCache.has(key)) {
                if (fetchBudget > 0) {
                    fetchBudget--;
                    // Mark as fetching
                    tileCache.set(key, null);
                    
                    const img = new Image();
                    img.crossOrigin = "Anonymous";
                    img.src = `http://localhost:8080/api/tile?cx=${cx}&cz=${cz}`;
                    img.onload = () => {
                        tileCache.set(key, img);
                        draw(); // trigger redraw when image loads
                    };
                    img.onerror = () => {
                        // If 404 (chunk not generated), store false to avoid refetching
                        tileCache.set(key, false);
                    };
                }
            } else {
                const img = tileCache.get(key);
                if (img && img !== false) {
                    ctx.drawImage(img, cx * 16 * zoom + pan.x, cz * 16 * zoom + pan.y, 16.2 * zoom, 16.2 * zoom);
                }
            }
        }
    }

    // Draw Grid overlay
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

    // Draw Edges
    const nodeMap = new Map(networkData.nodes.map(n => [n.id, n]));
    
    ctx.lineWidth = 2 * Math.min(1, Math.max(0.5, zoom));
    
    for (const edge of networkData.edges) {
        const n1 = nodeMap.get(edge.source);
        const n2 = nodeMap.get(edge.target);
        
        if (n1 && n2) {
            const x1 = n1.x * zoom + pan.x;
            const y1 = n1.z * zoom + pan.y;
            const x2 = n2.x * zoom + pan.x;
            const y2 = n2.z * zoom + pan.y;
            
            // Saturation logic
            let color = 'rgba(255, 255, 255, 0.4)';
            let isSaturated = edge.usage > edge.capacity;
            
            if (isSaturated) color = 'rgba(239, 68, 68, 0.8)'; // Red
            else if (edge.usage > 0) color = 'rgba(56, 189, 248, 0.8)'; // Blue active
            
            ctx.strokeStyle = color;
            ctx.beginPath();
            ctx.moveTo(x1, y1);
            ctx.lineTo(x2, y2);
            ctx.stroke();
            
            if (isSaturated) {
                // Draw warning on edge
                const cx = (x1 + x2) / 2;
                const cy = (y1 + y2) / 2;
                ctx.fillStyle = '#ef4444';
                ctx.beginPath();
                ctx.arc(cx, cy, 3 * zoom, 0, Math.PI * 2);
                ctx.fill();
            }
        }
    }
    
    // Draw Nodes
    for (const node of networkData.nodes) {
        const x = node.x * zoom + pan.x;
        const y = node.z * zoom + pan.y;
        
        const isHovered = hoveredNode && hoveredNode.id === node.id;
        let baseRadius = node.type === 'SERVER' ? 8 : (node.type === 'ANTENNA' ? 6 : 5);
        const radius = Math.max(3, baseRadius * Math.min(2, Math.max(0.5, zoom))) * (isHovered ? 1.5 : 1);
        
        const color = COLORS[node.type] || '#ffffff';
        
        // Glow effect
        ctx.shadowColor = color;
        ctx.shadowBlur = isHovered ? 20 : 10;
        
        ctx.fillStyle = color;
        ctx.beginPath();
        ctx.arc(x, y, radius, 0, Math.PI * 2);
        ctx.fill();
        
        ctx.shadowBlur = 0; // reset
        
        // Outline
        ctx.strokeStyle = '#0f172a';
        ctx.lineWidth = 2;
        ctx.stroke();
    }
}

resize();

import sys
import re

with open("web-dashboard/main.js") as f:
    content = f.read()

# 1. Add activeTileRequests tracking
content = content.replace("let fetchBudget = 100;", "let fetchBudget = 100;\nlet activeTileRequests = 0;")
content = content.replace("setInterval(() => {\n    fetchBudget = Math.min(fetchBudget + 10, 50);\n}, 100);", "setInterval(() => {\n    fetchBudget = Math.min(fetchBudget + 20, 100);\n}, 100);")

# Update tile loading loop to restrict active requests
old_tile_fetch = """        for (const tile of fetchQueue) {
            if (fetchBudget <= 0) break;
            fetchBudget--;
            const key = `${tile.cx},${tile.cz}`;
            tileCache.set(key, null);
            const img = new Image();
            img.crossOrigin = "Anonymous";
            img.src = `/api/tile?cx=${tile.cx}&cz=${tile.cz}&t=${Date.now()}`;
            img.onload = () => tileCache.set(key, img);
            img.onerror = () => tileCache.set(key, false);
        }"""

new_tile_fetch = """        for (const tile of fetchQueue) {
            if (fetchBudget <= 0 || activeTileRequests >= 4) break;
            fetchBudget--;
            activeTileRequests++;
            const key = `${tile.cx},${tile.cz}`;
            tileCache.set(key, null);
            const img = new Image();
            img.crossOrigin = "Anonymous";
            img.src = `/api/tile?cx=${tile.cx}&cz=${tile.cz}&t=${Date.now()}`;
            img.onload = () => { tileCache.set(key, img); activeTileRequests--; };
            img.onerror = () => { tileCache.set(key, false); activeTileRequests--; };
        }"""
content = content.replace(old_tile_fetch, new_tile_fetch)

# 2. Add selected tracking
content = content.replace("let hoveredNode = null;", "let hoveredNode = null;\nlet selectedNode = null;\nlet selectedEdge = null;")

# Update click handler
old_click = """canvas.addEventListener('click', e => {
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
});"""

new_click = """canvas.addEventListener('click', e => {
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
});"""
content = content.replace(old_click, new_click)

# Update fetchNetworkData to refresh details
old_fetch = """        if (!initialCenterDone && networkData.nodes.length > 0) {
            let sumX = 0, sumZ = 0;
            for (const n of networkData.nodes) {
                sumX += n.x;
                sumZ += n.z;
            }
            const avgX = sumX / networkData.nodes.length;
            const avgZ = sumZ / networkData.nodes.length;
            pan.x = canvas.width / 2 - (avgX * zoom);
            pan.y = canvas.height / 2 - (avgZ * zoom);
            initialCenterDone = true;
        }"""

new_fetch = """        if (!initialCenterDone && networkData.nodes.length > 0) {
            let sumX = 0, sumZ = 0;
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
        
        // Refresh selected info panel if something is selected
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
        }"""
content = content.replace(old_fetch, new_fetch)

with open("web-dashboard/main.js", "w") as f:
    f.write(content)


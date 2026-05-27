import sys

with open("web-dashboard/main.js") as f:
    content = f.read()

old_loop = """    const centerCx = Math.floor((minCx + maxCx) / 2);
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
    }"""

new_loop = """    const centerCx = Math.floor((minCx + maxCx) / 2);
    const centerCz = Math.floor((minCz + maxCz) / 2);
    
    let fetchQueue = [];
    
    for (let cx = minCx; cx <= maxCx; cx++) {
        for (let cz = minCz; cz <= maxCz; cz++) {
            const key = `${cx},${cz}`;
            if (!tileCache.has(key)) {
                fetchQueue.push({cx, cz, dist: (cx - centerCx)**2 + (cz - centerCz)**2});
            } else {
                const img = tileCache.get(key);
                if (img && img !== false) {
                    ctx.drawImage(img, cx * 16 * zoom + pan.x, cz * 16 * zoom + pan.y, 16.2 * zoom, 16.2 * zoom);
                }
            }
        }
    }
    
    if (fetchQueue.length > 0) {
        // Sort by distance to center so we load visible tiles first
        fetchQueue.sort((a, b) => a.dist - b.dist);
        for (const tile of fetchQueue) {
            if (fetchBudget <= 0) break;
            fetchBudget--;
            const key = `${tile.cx},${tile.cz}`;
            tileCache.set(key, null);
            const img = new Image();
            img.crossOrigin = "Anonymous";
            img.src = `/api/tile?cx=${tile.cx}&cz=${tile.cz}&t=${Date.now()}`;
            img.onload = () => tileCache.set(key, img);
            img.onerror = () => tileCache.set(key, false);
        }
    }"""

content = content.replace(old_loop, new_loop)

with open("web-dashboard/main.js", "w") as f:
    f.write(content)


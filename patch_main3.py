import sys

with open("web-dashboard/main.js") as f:
    content = f.read()

# Replace activeLayers logic
content = content.replace("""const activeLayers = new Set();
document.querySelectorAll('.layer-checkbox').forEach(cb => {
    cb.addEventListener('change', (e) => {
        const tech = e.target.dataset.tech;
        if (e.target.checked) {
            activeLayers.add(tech);
            if (!coverageData[tech]) {
                fetchCoverageData(tech);
            }
        } else {
            activeLayers.delete(tech);
        }
        draw();
    });
});""", """let showNperf = true;
document.querySelectorAll('.layer-checkbox').forEach(cb => {
    cb.addEventListener('change', (e) => {
        showNperf = e.target.checked;
        draw();
    });
});""")

# Replace data declaration
content = content.replace("let coverageData = {};", "let nperfData = [];")
content = content.replace("let fetchBudget = 10;", "")

# Replace fetchCoverageData
old_fetch = """async function fetchCoverageData(tech) {
    try {
        const response = await fetch(`/api/coverage_map?tech=${tech}`);
        if (!response.ok) return;
        const data = await response.json();
        coverageData[tech] = data;
        draw();
    } catch (e) {
        console.error(e);
    }
}"""
new_fetch = """async function fetchNperfData() {
    try {
        const response = await fetch('/api/nperf_map');
        if (!response.ok) return;
        nperfData = await response.json();
        draw();
    } catch (e) {
        console.error(e);
    }
}
setInterval(fetchNperfData, 2000);
fetchNperfData();"""
content = content.replace(old_fetch, new_fetch)

# Replace drawCoverage
old_draw = """const techColors = {
        '2G': ['rgba(239, 68, 68, 0.1)', 'rgba(239, 68, 68, 0.3)', 'rgba(239, 68, 68, 0.6)'],
        '3G': ['rgba(249, 115, 22, 0.1)', 'rgba(249, 115, 22, 0.3)', 'rgba(249, 115, 22, 0.6)'],
        '4G': ['rgba(34, 197, 94, 0.1)', 'rgba(34, 197, 94, 0.3)', 'rgba(34, 197, 94, 0.6)'],
        '5G': ['rgba(59, 130, 246, 0.1)', 'rgba(59, 130, 246, 0.3)', 'rgba(59, 130, 246, 0.6)']
    };

function drawCoverage() {
    if (activeLayers.size === 0) return;

    for (const tech of activeLayers) {
        if (!coverageData[tech]) continue;

        const data = coverageData[tech];
        const colors = techColors[tech];

        for (const point of data) {
            const sx = (point.x * zoom) + pan.x + canvas.width / 2;
            const sy = (point.z * zoom) + pan.y + canvas.height / 2;
            const size = Math.max(1, zoom); // Scale blocks with zoom

            // Only draw if on screen
            if (sx + size < 0 || sx > canvas.width || sy + size < 0 || sy > canvas.height) continue;

            const colorIndex = point.s - 1; // 1, 2, 3 -> 0, 1, 2
            if (colors && colors[colorIndex]) {
                ctx.fillStyle = colors[colorIndex];
                ctx.fillRect(sx, sy, size, size);
            }
        }
    }
}"""
new_draw = """const nperfColors = {
    1: ['rgba(59, 130, 246, 0.25)', 'rgba(59, 130, 246, 0.5)', 'rgba(59, 130, 246, 0.75)', 'rgba(59, 130, 246, 1)'], // Blue (2G)
    2: ['rgba(34, 197, 94, 0.25)', 'rgba(34, 197, 94, 0.5)', 'rgba(34, 197, 94, 0.75)', 'rgba(34, 197, 94, 1)'],   // Green (3G)
    3: ['rgba(249, 115, 22, 0.25)', 'rgba(249, 115, 22, 0.5)', 'rgba(249, 115, 22, 0.75)', 'rgba(249, 115, 22, 1)'], // Orange (4G)
    4: ['rgba(239, 68, 68, 0.25)', 'rgba(239, 68, 68, 0.5)', 'rgba(239, 68, 68, 0.75)', 'rgba(239, 68, 68, 1)'],   // Red (4G+)
    5: ['rgba(168, 85, 247, 0.25)', 'rgba(168, 85, 247, 0.5)', 'rgba(168, 85, 247, 0.75)', 'rgba(168, 85, 247, 1)']  // Purple (5G)
};

function drawCoverage() {
    if (!showNperf || !nperfData) return;

    for (const point of nperfData) {
        const sx = (point.x * zoom) + pan.x + canvas.width / 2;
        const sy = (point.z * zoom) + pan.y + canvas.height / 2;
        const size = Math.max(1, zoom);

        // Only draw if on screen
        if (sx + size < 0 || sx > canvas.width || sy + size < 0 || sy > canvas.height) continue;

        const colors = nperfColors[point.t];
        if (colors) {
            ctx.fillStyle = colors[point.s - 1] || colors[0];
            ctx.fillRect(sx, sy, size, size);
        }
    }
}"""
content = content.replace(old_draw, new_draw)

# Clean up any leftover tile fetching logic
content = content.replace("""    // Fetch tiles
    const cx = Math.floor(wx / 16);
    const cz = Math.floor(wz / 16);
    const key = `${cx},${cz}`;

    if (!tileCache.has(key)) {
        if (fetchBudget > 0) {
            fetchBudget--;
            tileCache.set(key, null); // mark as fetching
            const img = new Image();
            img.crossOrigin = "Anonymous";
            img.src = `/api/tile?cx=${cx}&cz=${cz}`;
            img.onload = () => {
                tileCache.set(key, img);
                draw(); // Redraw when tile loads
            };
            img.onerror = () => {
                // Keep as null to not refetch
            };
        }
    }""", "")
content = content.replace("""    fetchBudget = Math.min(fetchBudget + 1, 10);
""", "")

with open("web-dashboard/main.js", "w") as f:
    f.write(content)


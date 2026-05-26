import sys

with open("web-dashboard/main.js") as f:
    content = f.read()

# Replace activeLayers logic
start_idx = content.find("const activeLayers = new Set();")
end_idx = content.find("draw();", start_idx) + 7
new_layers = """let showNperf = true;

document.querySelectorAll('.layer-checkbox').forEach(cb => {
    cb.addEventListener('change', (e) => {
        showNperf = e.target.checked;
        draw();
    });
});
"""
content = content[:start_idx] + new_layers + content[end_idx:]

# Replace fetchBudget and tileCache since we don't use tiles
start_idx = content.find("let coverageData = {};")
end_idx = content.find("const COLORS = {", start_idx)
new_cov = """let nperfData = [];
"""
content = content[:start_idx] + new_cov + content[end_idx:]

# Replace fetchCoverageData
start_idx = content.find("async function fetchCoverageData(tech) {")
end_idx = content.find("async function fetchNetworkData() {", start_idx)
new_fetch = """async function fetchNperfData() {
    try {
        const response = await fetch('/api/nperf_map');
        nperfData = await response.json();
    } catch (e) {
        console.error(e);
    }
}

// Fetch Nperf periodically
setInterval(fetchNperfData, 5000);
fetchNperfData();

"""
content = content[:start_idx] + new_fetch + content[end_idx:]

# Replace drawCoverage
start_idx = content.find("function drawCoverage() {")
end_idx = content.find("function drawNetwork() {", start_idx)
new_draw = """const nperfColors = {
    1: ['rgba(59, 130, 246, 0.25)', 'rgba(59, 130, 246, 0.5)', 'rgba(59, 130, 246, 0.75)', 'rgba(59, 130, 246, 1)'], // Blue
    2: ['rgba(34, 197, 94, 0.25)', 'rgba(34, 197, 94, 0.5)', 'rgba(34, 197, 94, 0.75)', 'rgba(34, 197, 94, 1)'],   // Green
    3: ['rgba(249, 115, 22, 0.25)', 'rgba(249, 115, 22, 0.5)', 'rgba(249, 115, 22, 0.75)', 'rgba(249, 115, 22, 1)'], // Orange
    4: ['rgba(239, 68, 68, 0.25)', 'rgba(239, 68, 68, 0.5)', 'rgba(239, 68, 68, 0.75)', 'rgba(239, 68, 68, 1)'],   // Red
    5: ['rgba(168, 85, 247, 0.25)', 'rgba(168, 85, 247, 0.5)', 'rgba(168, 85, 247, 0.75)', 'rgba(168, 85, 247, 1)']  // Purple
};

function drawCoverage() {
    if (!showNperf) return;

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
}

"""
content = content[:start_idx] + new_draw + content[end_idx:]

with open("web-dashboard/main.js", "w") as f:
    f.write(content)


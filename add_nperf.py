import sys

with open("web-dashboard/main.js") as f:
    content = f.read()

nperf_logic = """
const nperfColors = {
    1: ['rgba(59, 130, 246, 0.25)', 'rgba(59, 130, 246, 0.5)', 'rgba(59, 130, 246, 0.75)', 'rgba(59, 130, 246, 1)'], // Blue (2G)
    2: ['rgba(34, 197, 94, 0.25)', 'rgba(34, 197, 94, 0.5)', 'rgba(34, 197, 94, 0.75)', 'rgba(34, 197, 94, 1)'],   // Green (3G)
    3: ['rgba(249, 115, 22, 0.25)', 'rgba(249, 115, 22, 0.5)', 'rgba(249, 115, 22, 0.75)', 'rgba(249, 115, 22, 1)'], // Orange (4G)
    4: ['rgba(239, 68, 68, 0.25)', 'rgba(239, 68, 68, 0.5)', 'rgba(239, 68, 68, 0.75)', 'rgba(239, 68, 68, 1)'],   // Red (4G+)
    5: ['rgba(168, 85, 247, 0.25)', 'rgba(168, 85, 247, 0.5)', 'rgba(168, 85, 247, 0.75)', 'rgba(168, 85, 247, 1)']  // Purple (5G)
};

async function fetchNperfData() {
    try {
        const response = await fetch('/api/nperf_map');
        if (!response.ok) return;
        nperfData = await response.json();
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

"""

# Add nperf_logic right before `function draw() {`
content = content.replace("function draw() {", nperf_logic + "\nfunction draw() {")

# Call drawCoverage() inside draw(), before node drawing
call_hook = """    for (const node of networkData.nodes) {"""
call_insert = """    drawCoverage();

    for (const node of networkData.nodes) {"""
content = content.replace(call_hook, call_insert)

with open("web-dashboard/main.js", "w") as f:
    f.write(content)

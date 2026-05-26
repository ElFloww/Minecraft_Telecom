import sys

with open("web-dashboard/main.js") as f:
    content = f.read()

old_code = """function drawCoverage() {
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
}"""

new_code = """function drawCoverage() {
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
}"""

content = content.replace(old_code, new_code)

with open("web-dashboard/main.js", "w") as f:
    f.write(content)


import sys

with open("web-dashboard/main.js") as f:
    content = f.read()

bad = """    const techColors = {
        '2G': ['rgba(239, 68, 68, 0.1)', 'rgba(239, 68, 68, 0.3)', 'rgba(239, 68, 68, 0.6)'],
        '3G': ['rgba(249, 115, 22, 0.1)', 'rgba(249, 115, 22, 0.3)', 'rgba(249, 115, 22, 0.6)'],
        '4G': ['rgba(34, 197, 94, 0.1)', 'rgba(34, 197, 94, 0.3)', 'rgba(34, 197, 94, 0.6)'],
        '5G': ['rgba(59, 130, 246, 0.1)', 'rgba(59, 130, 246, 0.3)', 'rgba(59, 130, 246, 0.6)']
    };

    const checkboxes = document.querySelectorAll('.layer-checkbox');
    for (const checkbox of checkboxes) {
        if (checkbox.checked) {
            const tech = checkbox.dataset.tech;
            
            if (!coverageData[tech] && !checkbox.fetching) {
                checkbox.fetching = true;
                fetch(`/api/coverage_map?tech=${encodeURIComponent(tech)}`)
                    .then(r => r.json())
                    .then(data => {
                        coverageData[tech] = data;
                        checkbox.fetching = false;
                    })
                    .catch(() => checkbox.fetching = false);
            }

            if (coverageData[tech]) {
                const colors = techColors[tech];
                for (const tile of coverageData[tech]) {
                    const cx = tile[0];
                    const cz = tile[1];
                    const level = tile[2]; // 1, 2, 3
                    
                    if (cx < minCx || cx > maxCx || cz < minCz || cz > maxCz) continue;

                    ctx.fillStyle = colors[level - 1];
                    ctx.fillRect(cx * 16 * zoom + pan.x, cz * 16 * zoom + pan.y, 16 * zoom, 16 * zoom);
                }
            }
        }
    }"""

content = content.replace(bad, "")

with open("web-dashboard/main.js", "w") as f:
    f.write(content)

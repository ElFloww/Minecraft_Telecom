import sys
import re

with open("web-dashboard/main.js") as f:
    content = f.read()

# Replace coverageTileCache with coverageData
content = re.sub(
    r"let coverageTileCache = \{.*?\};",
    "let coverageData = {};",
    content,
    flags=re.DOTALL
)

# Remove fetchBudget logic if any
content = re.sub(r"let fetchBudget = \d+;\n?", "", content)

# Remove fetchBudget recharge in draw()
content = re.sub(r"fetchBudget = Math.min\(fetchBudget \+ 1, 10\);", "", content)

# Replace the old coverage drawing logic in draw()
start_idx = content.find("const techs = ['2G', '3G', '4G', '5G'];")
if start_idx != -1:
    end_idx = content.find("ctx.strokeStyle = 'rgba(255,255,255,0.05)';", start_idx)
    
    new_draw_logic = """const techColors = {
        '2G': ['rgba(239, 68, 68, 0.1)', 'rgba(239, 68, 68, 0.3)', 'rgba(239, 68, 68, 0.6)'],
        '3G': ['rgba(249, 115, 22, 0.1)', 'rgba(249, 115, 22, 0.3)', 'rgba(249, 115, 22, 0.6)'],
        '4G': ['rgba(34, 197, 94, 0.1)', 'rgba(34, 197, 94, 0.3)', 'rgba(34, 197, 94, 0.6)'],
        '4G+': ['rgba(20, 184, 166, 0.1)', 'rgba(20, 184, 166, 0.3)', 'rgba(20, 184, 166, 0.6)'],
        '5G 700MHz': ['rgba(59, 130, 246, 0.1)', 'rgba(59, 130, 246, 0.3)', 'rgba(59, 130, 246, 0.6)'],
        '5G 2100MHz': ['rgba(99, 102, 241, 0.1)', 'rgba(99, 102, 241, 0.3)', 'rgba(99, 102, 241, 0.6)'],
        '5G 3.5GHz': ['rgba(168, 85, 247, 0.1)', 'rgba(168, 85, 247, 0.3)', 'rgba(168, 85, 247, 0.6)'],
        '5G+': ['rgba(236, 72, 153, 0.1)', 'rgba(236, 72, 153, 0.3)', 'rgba(236, 72, 153, 0.6)']
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
    }

    """
    
    content = content[:start_idx] + new_draw_logic + content[end_idx:]

with open("web-dashboard/main.js", "w") as f:
    f.write(content)

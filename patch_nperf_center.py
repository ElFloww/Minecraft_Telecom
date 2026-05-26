import sys

with open("web-dashboard/main.js") as f:
    content = f.read()

bad = """async function fetchNperfData() {
    try {
        const response = await fetch('/api/nperf_map');
        if (!response.ok) return;
        nperfData = await response.json();
    } catch (e) {
        console.error(e);
    }
}"""

good = """async function fetchNperfData() {
    try {
        const response = await fetch('/api/nperf_map');
        if (!response.ok) return;
        nperfData = await response.json();
        
        if (!initialCenterDone && nperfData.length > 0) {
            let sumX = 0;
            let sumZ = 0;
            for (const p of nperfData) {
                sumX += p.x;
                sumZ += p.z;
            }
            pan.x = canvas.width / 2 - ((sumX / nperfData.length) * zoom);
            pan.y = canvas.height / 2 - ((sumZ / nperfData.length) * zoom);
            initialCenterDone = true;
        }
    } catch (e) {
        console.error(e);
    }
}"""

content = content.replace(bad, good)

with open("web-dashboard/main.js", "w") as f:
    f.write(content)

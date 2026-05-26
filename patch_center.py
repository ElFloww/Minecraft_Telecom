import sys

with open("web-dashboard/main.js") as f:
    content = f.read()

bad = """async function fetchNetworkData() {
    try {
        const res = await fetch('/api/network');
        if (res.ok) {
            networkData = await res.json();
            document.getElementById('stat-nodes').innerText = networkData.nodes.length;
            document.getElementById('stat-edges').innerText = networkData.edges.length;
        }
    } catch (e) {
        console.warn("Could not fetch network data. Is the Minecraft server running?", e);
    }
}

setTimeout(() => {
    pan.x = canvas.width / 2;
    pan.y = canvas.height / 2;
}, 100);"""

good = """let initialCenterDone = false;
async function fetchNetworkData() {
    try {
        const res = await fetch('/api/network');
        if (res.ok) {
            networkData = await res.json();
            document.getElementById('stat-nodes').innerText = networkData.nodes.length;
            document.getElementById('stat-edges').innerText = networkData.edges.length;
            
            if (!initialCenterDone && networkData.nodes.length > 0) {
                let sumX = 0;
                let sumZ = 0;
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
        }
    } catch (e) {
        console.warn("Could not fetch network data. Is the Minecraft server running?", e);
    }
}

setTimeout(() => {
    if (!initialCenterDone) {
        pan.x = canvas.width / 2;
        pan.y = canvas.height / 2;
    }
}, 100);"""

content = content.replace(bad, good)

# Also fix the cable opacity so they don't look like they disappear
content = content.replace("? 'rgba(255, 255, 255, 0.6)' : 'rgba(255, 255, 255, 0.15)'", "? 'rgba(255, 255, 255, 0.8)' : 'rgba(255, 255, 255, 0.5)'")

with open("web-dashboard/main.js", "w") as f:
    f.write(content)


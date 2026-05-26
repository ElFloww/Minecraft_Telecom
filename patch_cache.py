import sys

with open("web-dashboard/main.js") as f:
    content = f.read()

content = content.replace("await fetch('/api/network')", "await fetch('/api/network?t=' + Date.now())")
content = content.replace("await fetch('/api/nperf_map')", "await fetch('/api/nperf_map?t=' + Date.now())")
content = content.replace("await fetch('/api/player')", "await fetch('/api/player?t=' + Date.now())")

with open("web-dashboard/main.js", "w") as f:
    f.write(content)


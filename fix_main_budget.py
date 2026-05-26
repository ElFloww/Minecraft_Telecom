import sys

with open("web-dashboard/main.js") as f:
    content = f.read()

content = content.replace("let nperfData = [];", "let nperfData = [];\\nlet fetchBudget = 10;")
content = content.replace("function drawMap() {", "setInterval(() => { fetchBudget = Math.min(fetchBudget + 1, 10); }, 100);\\nfunction drawMap() {")

with open("web-dashboard/main.js", "w") as f:
    f.write(content)

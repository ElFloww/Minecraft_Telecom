import sys

with open("web-dashboard/main.js") as f:
    content = f.read()

content = content.replace("fetchBudget = Math.min(fetchBudget + 5, 20);", "fetchBudget = Math.min(fetchBudget + 20, 100);")

with open("web-dashboard/main.js", "w") as f:
    f.write(content)


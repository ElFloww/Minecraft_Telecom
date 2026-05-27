import sys

with open("web-dashboard/main.js") as f:
    content = f.read()

# Fix fetchBudget to 50 as requested
content = content.replace("fetchBudget = Math.min(fetchBudget + 20, 100);", "fetchBudget = Math.min(fetchBudget + 10, 50);")

with open("web-dashboard/main.js", "w") as f:
    f.write(content)


import sys

with open("web-dashboard/main.js") as f:
    content = f.read()

content = content.replace("const radius = 150;", "const radius = 20;")

with open("web-dashboard/main.js", "w") as f:
    f.write(content)


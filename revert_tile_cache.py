import sys

with open("web-dashboard/main.js") as f:
    content = f.read()

content = content.replace("img.src = `/api/tile?cx=${cx}&cz=${cz}&t=${Date.now()}`;", "img.src = `/api/tile?cx=${cx}&cz=${cz}`;")

with open("web-dashboard/main.js", "w") as f:
    f.write(content)


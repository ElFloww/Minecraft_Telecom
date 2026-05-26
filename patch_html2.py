import sys

with open("web-dashboard/index.html") as f:
    content = f.read()

start_idx = content.find('<div class="layer-control">\n                    <input type="checkbox" id="cov-2G"')
end_idx = content.find('</div>\n            </div>', start_idx) + 6

new_html = """<div class="layer-control">
                    <input type="checkbox" id="cov-nperf" class="layer-checkbox" data-tech="nperf" checked>
                    <label for="cov-nperf">Couverture Nperf</label>
                </div>
            </div>"""

if start_idx != -1 and end_idx != -1:
    content = content[:start_idx] + new_html + content[end_idx:]

with open("web-dashboard/index.html", "w") as f:
    f.write(content)


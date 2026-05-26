import sys
with open("web-dashboard/index.html") as f:
    content = f.read()

start_idx = content.find('<div class="layer-control">\n                    <input type="checkbox" id="cov-2G"')
end_idx = content.find('</div>\n            </div>', start_idx) + 6

new_html = """<div class="layer-control">
                    <input type="checkbox" id="cov-2G" class="layer-checkbox" data-tech="2G">
                    <label for="cov-2G">Couverture 2G</label>
                </div>
                <div class="layer-control">
                    <input type="checkbox" id="cov-3G" class="layer-checkbox" data-tech="3G">
                    <label for="cov-3G">Couverture 3G</label>
                </div>
                <div class="layer-control">
                    <input type="checkbox" id="cov-4G" class="layer-checkbox" data-tech="4G">
                    <label for="cov-4G">Couverture 4G</label>
                </div>
                <div class="layer-control">
                    <input type="checkbox" id="cov-4G+" class="layer-checkbox" data-tech="4G+">
                    <label for="cov-4G+">Couverture 4G+</label>
                </div>
                <div class="layer-control">
                    <input type="checkbox" id="cov-5G-700" class="layer-checkbox" data-tech="5G 700MHz">
                    <label for="cov-5G-700">Couverture 5G (700MHz)</label>
                </div>
                <div class="layer-control">
                    <input type="checkbox" id="cov-5G-2100" class="layer-checkbox" data-tech="5G 2100MHz">
                    <label for="cov-5G-2100">Couverture 5G (2100MHz)</label>
                </div>
                <div class="layer-control">
                    <input type="checkbox" id="cov-5G-3500" class="layer-checkbox" data-tech="5G 3.5GHz">
                    <label for="cov-5G-3500">Couverture 5G (3.5GHz)</label>
                </div>
                <div class="layer-control">
                    <input type="checkbox" id="cov-5G+" class="layer-checkbox" data-tech="5G+">
                    <label for="cov-5G+">Couverture 5G+ (26GHz)</label>
                </div>
            </div>"""

content = content[:start_idx] + new_html + content[end_idx:]

with open("web-dashboard/index.html", "w") as f:
    f.write(content)

import sys

with open("web-dashboard/index.html") as f:
    content = f.read()

bad = """        <div class="coverage-controls">
          <h3>Couverture Mobile</h3>
          <div class="coverage-toggles">
            <label class="toggle-label"><input type="checkbox" id="cov-2G"> <span class="slider"></span> 2G (GSM)</label>
            <label class="toggle-label"><input type="checkbox" id="cov-3G"> <span class="slider"></span> 3G</label>
            <label class="toggle-label"><input type="checkbox" id="cov-4G"> <span class="slider"></span> 4G (LTE)</label>
            <label class="toggle-label"><input type="checkbox" id="cov-5G"> <span class="slider"></span> 5G (NR)</label>
          </div>
        </div>"""

good = """        <div class="coverage-controls">
          <h3>Couverture Mobile</h3>
          <div class="coverage-toggles">
            <label class="toggle-label"><input type="checkbox" id="cov-nperf" class="layer-checkbox" data-tech="nperf" checked> <span class="slider"></span> Nperf (Global)</label>
          </div>
        </div>"""

content = content.replace(bad, good)

with open("web-dashboard/index.html", "w") as f:
    f.write(content)

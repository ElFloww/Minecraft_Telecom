import sys
with open("web-dashboard/main.js") as f:
    content = f.read()

start_idx = content.find("const techColors = {")
end_idx = content.find("};", start_idx) + 2

new_colors = """const techColors = {
        '2G': ['rgba(239, 68, 68, 0.1)', 'rgba(239, 68, 68, 0.3)', 'rgba(239, 68, 68, 0.6)'],
        '3G': ['rgba(249, 115, 22, 0.1)', 'rgba(249, 115, 22, 0.3)', 'rgba(249, 115, 22, 0.6)'],
        '4G': ['rgba(34, 197, 94, 0.1)', 'rgba(34, 197, 94, 0.3)', 'rgba(34, 197, 94, 0.6)'],
        '5G': ['rgba(59, 130, 246, 0.1)', 'rgba(59, 130, 246, 0.3)', 'rgba(59, 130, 246, 0.6)']
    };"""

content = content[:start_idx] + new_colors + content[end_idx:]

with open("web-dashboard/main.js", "w") as f:
    f.write(content)

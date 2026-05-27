import sys

with open("web-dashboard/main.js") as f:
    content = f.read()

old_node = """            ctx.strokeStyle = '#0f172a';
            ctx.lineWidth = 2;
            ctx.stroke();
        }
    }
    
    requestAnimationFrame(draw);"""

new_node = """            ctx.strokeStyle = '#0f172a';
            ctx.lineWidth = 2;
            ctx.stroke();
            
            // Pulse animation if there is traffic
            let maxUsage = Math.max(node.usageDown, node.usageUp);
            if (maxUsage > 0 && node.capacity > 0) {
                let loadPct = Math.min(100, (maxUsage / node.capacity) * 100);
                let speed = 1 + (loadPct / 100) * 5;
                let pulseTime = (animationTime * speed) % 2; // 0 to 2
                
                if (pulseTime < 1) {
                    ctx.beginPath();
                    ctx.arc(x, y, radius + (pulseTime * radius * 3), 0, Math.PI * 2);
                    ctx.strokeStyle = `rgba(255, 255, 255, ${0.5 * (1 - pulseTime)})`;
                    ctx.lineWidth = 1;
                    ctx.stroke();
                }
            }
        }
    }
    
    requestAnimationFrame(draw);"""

content = content.replace(old_node, new_node)

with open("web-dashboard/main.js", "w") as f:
    f.write(content)


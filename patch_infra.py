import sys

with open("web-dashboard/main.js") as f:
    content = f.read()

# Fix fetchBudget initial value
content = content.replace("let fetchBudget = 10;", "let fetchBudget = 100;")

# Make infrastructure drawing conditional
old_infra = """    // Draw edges
    ctx.lineWidth = Math.max(1, 2 * zoom);
    for (const edge of networkData.edges) {
        const from = networkData.nodes.find(n => n.id === edge.from);
        const to = networkData.nodes.find(n => n.id === edge.to);
        if (!from || !to) continue;

        ctx.beginPath();
        ctx.moveTo(from.x * zoom + pan.x, from.z * zoom + pan.y);
        ctx.lineTo(to.x * zoom + pan.x, to.z * zoom + pan.y);
        ctx.strokeStyle = edge.type === 'FIBER' ? '#0ea5e9' : '#f59e0b';
        ctx.stroke();
    }

    // Draw nodes
    for (const node of networkData.nodes) {
        const x = node.x * zoom + pan.x;
        const y = node.z * zoom + pan.y;
        
        const isHovered = hoveredNode && hoveredNode.id === node.id;
        let baseRadius = node.type === 'SERVER' ? 8 : (node.type === 'ANTENNA' ? 6 : 5);
        const radius = Math.max(3, baseRadius * Math.min(2, Math.max(0.5, zoom))) * (isHovered ? 1.5 : 1);
        
        ctx.beginPath();
        ctx.arc(x, y, radius, 0, Math.PI * 2);
        
        switch(node.type) {
            case 'SERVER': ctx.fillStyle = '#ef4444'; break;
            case 'ROUTER': ctx.fillStyle = '#f59e0b'; break;
            case 'ANTENNA': ctx.fillStyle = '#10b981'; break;
            case 'NRO': ctx.fillStyle = '#8b5cf6'; break;
            case 'NRA': ctx.fillStyle = '#ec4899'; break;
            case 'PM': ctx.fillStyle = '#0ea5e9'; break;
            case 'SR': ctx.fillStyle = '#14b8a6'; break;
            default: ctx.fillStyle = '#9ca3af';
        }
        
        ctx.fill();
        
        if (isHovered) {
            ctx.strokeStyle = '#fff';
            ctx.lineWidth = 2;
            ctx.stroke();
        }
    }"""

new_infra = """    const showInfra = document.getElementById('show-infra');
    if (!showInfra || showInfra.checked) {
        // Draw edges
        ctx.lineWidth = Math.max(1, 2 * zoom);
        for (const edge of networkData.edges) {
            const from = networkData.nodes.find(n => n.id === edge.from);
            const to = networkData.nodes.find(n => n.id === edge.to);
            if (!from || !to) continue;

            ctx.beginPath();
            ctx.moveTo(from.x * zoom + pan.x, from.z * zoom + pan.y);
            ctx.lineTo(to.x * zoom + pan.x, to.z * zoom + pan.y);
            ctx.strokeStyle = edge.type === 'FIBER' ? '#0ea5e9' : '#f59e0b';
            ctx.stroke();
        }

        // Draw nodes
        for (const node of networkData.nodes) {
            const x = node.x * zoom + pan.x;
            const y = node.z * zoom + pan.y;
            
            const isHovered = hoveredNode && hoveredNode.id === node.id;
            let baseRadius = node.type === 'SERVER' ? 8 : (node.type === 'ANTENNA' ? 6 : 5);
            const radius = Math.max(3, baseRadius * Math.min(2, Math.max(0.5, zoom))) * (isHovered ? 1.5 : 1);
            
            ctx.beginPath();
            ctx.arc(x, y, radius, 0, Math.PI * 2);
            
            switch(node.type) {
                case 'SERVER': ctx.fillStyle = '#ef4444'; break;
                case 'ROUTER': ctx.fillStyle = '#f59e0b'; break;
                case 'ANTENNA': ctx.fillStyle = '#10b981'; break;
                case 'NRO': ctx.fillStyle = '#8b5cf6'; break;
                case 'NRA': ctx.fillStyle = '#ec4899'; break;
                case 'PM': ctx.fillStyle = '#0ea5e9'; break;
                case 'SR': ctx.fillStyle = '#14b8a6'; break;
                default: ctx.fillStyle = '#9ca3af';
            }
            
            ctx.fill();
            
            if (isHovered) {
                ctx.strokeStyle = '#fff';
                ctx.lineWidth = 2;
                ctx.stroke();
            }
        }
    }"""

content = content.replace(old_infra, new_infra)

with open("web-dashboard/main.js", "w") as f:
    f.write(content)


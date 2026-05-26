import sys

with open("src/main/java/com/florentdubut/telecom/server/TelecomHttpServer.java") as f:
    content = f.read()

endpoint = """            server.createContext("/api/speedtest", new SpeedtestHandler());
            
            server.createContext("/api/player", exchange -> {
                exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
                if (minecraftServer == null || minecraftServer.overworld() == null || minecraftServer.overworld().players().isEmpty()) {
                    exchange.sendResponseHeaders(404, -1);
                    exchange.close();
                    return;
                }
                net.minecraft.world.entity.player.Player p = minecraftServer.overworld().players().get(0);
                String json = "{\\"x\\":" + p.getBlockX() + ",\\"z\\":" + p.getBlockZ() + "}";
                byte[] bytes = json.getBytes("UTF-8");
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, bytes.length);
                java.io.OutputStream os = exchange.getResponseBody();
                os.write(bytes);
                os.close();
            });"""

content = content.replace("            server.createContext(\"/api/speedtest\", new SpeedtestHandler());", endpoint)

with open("src/main/java/com/florentdubut/telecom/server/TelecomHttpServer.java", "w") as f:
    f.write(content)

with open("web-dashboard/main.js") as f:
    content2 = f.read()

bad_timeout = """setTimeout(() => {
    if (!initialCenterDone) {
        pan.x = canvas.width / 2;
        pan.y = canvas.height / 2;
    }
}, 100);"""

good_timeout = """setTimeout(async () => {
    if (!initialCenterDone) {
        try {
            const res = await fetch('/api/player');
            if (res.ok) {
                const p = await res.json();
                pan.x = canvas.width / 2 - (p.x * zoom);
                pan.y = canvas.height / 2 - (p.z * zoom);
                initialCenterDone = true;
            } else {
                pan.x = canvas.width / 2;
                pan.y = canvas.height / 2;
            }
        } catch (e) {
            pan.x = canvas.width / 2;
            pan.y = canvas.height / 2;
        }
    }
}, 500);"""

content2 = content2.replace(bad_timeout, good_timeout)

with open("web-dashboard/main.js", "w") as f:
    f.write(content2)

import sys

with open("src/main/java/com/florentdubut/telecom/server/TelecomHttpServer.java") as f:
    content = f.read()

bad = """            server.createContext("/api/speedtest", new SpeedtestHandler());
            
            // Handle CORS for local dev"""
good = """            server.createContext("/api/speedtest", new SpeedtestHandler());
            
            server.createContext("/favicon.ico", exchange -> {
                exchange.sendResponseHeaders(204, -1);
                exchange.close();
            });
            
            // Handle CORS for local dev"""

content = content.replace(bad, good)

with open("src/main/java/com/florentdubut/telecom/server/TelecomHttpServer.java", "w") as f:
    f.write(content)

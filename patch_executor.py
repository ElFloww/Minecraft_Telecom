import sys

with open("src/main/java/com/florentdubut/telecom/server/TelecomHttpServer.java") as f:
    content = f.read()

content = content.replace("server = HttpServer.create(new InetSocketAddress(8080), 0);", "server = HttpServer.create(new InetSocketAddress(8080), 0);\n            server.setExecutor(java.util.concurrent.Executors.newFixedThreadPool(16));")

with open("src/main/java/com/florentdubut/telecom/server/TelecomHttpServer.java", "w") as f:
    f.write(content)


import sys

with open("src/main/java/com/florentdubut/telecom/server/TelecomHttpServer.java") as f:
    content = f.read()

content = content.replace('exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");', 'exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");\n            exchange.getResponseHeaders().add("Cache-Control", "no-store, no-cache, must-revalidate, max-age=0");')

with open("src/main/java/com/florentdubut/telecom/server/TelecomHttpServer.java", "w") as f:
    f.write(content)


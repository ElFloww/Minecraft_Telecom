import sys
with open("src/main/java/com/florentdubut/telecom/server/TelecomHttpServer.java") as f:
    target = f.read()

target = target.replace(
"""                NetworkNode node = graph.getNode(pos);
                if (node == null || node.getType() != NetworkNode.NodeType.ROUTER) {
                    sendEmptyResponse(exchange, 404);
                    return;
                }""",
"""                NetworkNode node = graph.getNode(pos);
                if (node == null || node.getType() != NetworkNode.NodeType.ROUTER) {
                    System.out.println("Node is null or not router: " + pos + " node=" + node);
                    sendEmptyResponse(exchange, 404);
                    return;
                }""")

with open("src/main/java/com/florentdubut/telecom/server/TelecomHttpServer.java", "w") as f:
    f.write(target)

import sys
with open("src/main/java/com/florentdubut/telecom/block/entity/RouterBlockEntity.java") as f:
    content = f.read()

target = "NetworkNode node = new NetworkNode(worldPosition, NetworkNode.NodeType.ROUTER);"
replace = target + """
            node.setCapacityDown(getConfiguredMaxDown());
            node.setCapacityUp(getConfiguredMaxUp());"""

content = content.replace(target, replace)

with open("src/main/java/com/florentdubut/telecom/block/entity/RouterBlockEntity.java", "w") as f:
    f.write(content)

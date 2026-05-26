import sys
with open("src/main/java/com/florentdubut/telecom/block/entity/AntennaBlockEntity.java") as f:
    content = f.read()

target = "NetworkNode node = new NetworkNode(worldPosition, NetworkNode.NodeType.ANTENNA);"
replace = target + """
            node.setFrequenciesMask(enabledFrequenciesMask);"""

content = content.replace(target, replace)

with open("src/main/java/com/florentdubut/telecom/block/entity/AntennaBlockEntity.java", "w") as f:
    f.write(content)

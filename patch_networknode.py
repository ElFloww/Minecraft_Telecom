import sys
with open("src/main/java/com/florentdubut/telecom/network/TelecomNetworkGraph.java") as f:
    content = f.read()

# In load()
load_target = """            if (nodeTag.contains("CIDR")) {
                node.setNetworkCidr(nodeTag.getString("CIDR"));
            }
"""
load_replace = load_target + """            if (nodeTag.contains("FreqMask")) {
                node.setFrequenciesMask(nodeTag.getInt("FreqMask"));
            }
            if (nodeTag.contains("CapDown")) {
                node.setCapacityDown(nodeTag.getInt("CapDown"));
            }
            if (nodeTag.contains("CapUp")) {
                node.setCapacityUp(nodeTag.getInt("CapUp"));
            }
"""

# In save()
save_target = """            if (node.getNetworkCidr() != null) {
                nodeTag.putString("CIDR", node.getNetworkCidr());
            }
"""
save_replace = save_target + """            nodeTag.putInt("FreqMask", node.getFrequenciesMask());
            nodeTag.putInt("CapDown", node.getCapacityDown());
            nodeTag.putInt("CapUp", node.getCapacityUp());
"""

content = content.replace(load_target, load_replace)
content = content.replace(save_target, save_replace)

with open("src/main/java/com/florentdubut/telecom/network/TelecomNetworkGraph.java", "w") as f:
    f.write(content)

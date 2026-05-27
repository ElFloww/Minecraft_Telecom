import sys

with open("src/main/java/com/florentdubut/telecom/network/NetworkTracer.java") as f:
    tracer = f.read()

old_str = "        // 4. Assign IPs hierarchically (CIDR tree)"
new_str = "        graph.setEdges(newEdges);\n\n        // 4. Assign IPs hierarchically (CIDR tree)"

tracer = tracer.replace(old_str, new_str)

with open("src/main/java/com/florentdubut/telecom/network/NetworkTracer.java", "w") as f:
    f.write(tracer)


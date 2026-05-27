import sys

with open("src/main/java/com/florentdubut/telecom/network/TelecomNetworkGraph.java") as f:
    content = f.read()

# Add setEdges
old_clear = """    public void clearEdges() {
        edges.clear();
        pathCache.clear();
        setDirty();
    }"""

new_clear = """    public void clearEdges() {
        synchronized(edges) {
            edges.clear();
        }
        pathCache.clear();
        setDirty();
    }
    
    public void setEdges(java.util.List<com.florentdubut.telecom.network.NetworkEdge> newEdges) {
        synchronized(edges) {
            edges.clear();
            edges.addAll(newEdges);
        }
        pathCache.clear();
        setDirty();
    }"""
content = content.replace(old_clear, new_clear)

with open("src/main/java/com/florentdubut/telecom/network/TelecomNetworkGraph.java", "w") as f:
    f.write(content)


with open("src/main/java/com/florentdubut/telecom/network/NetworkTracer.java") as f:
    tracer = f.read()

# Replace graph.clearEdges() and graph.addEdge() with local list
old_tracer_start = """    public static void recalculateNetwork(ServerLevel level) {
        TelecomNetworkGraph graph = TelecomNetworkGraph.get(level);

        // 1. Clear all current edges
        graph.clearEdges();"""

new_tracer_start = """    public static void recalculateNetwork(ServerLevel level) {
        TelecomNetworkGraph graph = TelecomNetworkGraph.get(level);

        // 1. Prepare new edges list
        java.util.List<NetworkEdge> newEdges = new java.util.ArrayList<>();"""
tracer = tracer.replace(old_tracer_start, new_tracer_start)

# Replace graph.addEdge(edge); with newEdges.add(edge);
tracer = tracer.replace("graph.addEdge(edge);", "newEdges.add(edge);")

# At the end of recalculateNetwork, set the edges
old_tracer_end = """        }

        // 4. Assign IPs and CIDRs
"""
new_tracer_end = """        }
        
        graph.setEdges(newEdges);

        // 4. Assign IPs and CIDRs
"""
tracer = tracer.replace(old_tracer_end, new_tracer_end)

with open("src/main/java/com/florentdubut/telecom/network/NetworkTracer.java", "w") as f:
    f.write(tracer)


with open("src/main/java/com/florentdubut/telecom/server/TelecomHttpServer.java") as f:
    server = f.read()

old_server_edges = """            // Edges
            JsonArray edgesArray = new JsonArray();
            for (NetworkEdge edge : graph.getEdges()) {
                JsonObject edgeObj = new JsonObject();"""

new_server_edges = """            // Edges
            JsonArray edgesArray = new JsonArray();
            synchronized(graph.getEdges()) {
                for (NetworkEdge edge : graph.getEdges()) {
                    JsonObject edgeObj = new JsonObject();"""
server = server.replace(old_server_edges, new_server_edges)

old_server_edges_end = """                edgeObj.addProperty("length", edge.getLength());
                edgesArray.add(edgeObj);
            }
            response.add("edges", edgesArray);"""

new_server_edges_end = """                edgeObj.addProperty("length", edge.getLength());
                edgesArray.add(edgeObj);
                }
            }
            response.add("edges", edgesArray);"""
server = server.replace(old_server_edges_end, new_server_edges_end)

with open("src/main/java/com/florentdubut/telecom/server/TelecomHttpServer.java", "w") as f:
    f.write(server)


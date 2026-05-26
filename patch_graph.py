import sys

with open("src/main/java/com/florentdubut/telecom/network/TelecomNetworkGraph.java") as f:
    content = f.read()

# Add recordedCoverage to the class fields
field_insert = "private final List<NetworkEdge> edges = new ArrayList<>();\n    private final Map<Long, Integer> recordedCoverage = new java.util.concurrent.ConcurrentHashMap<>();\n"
content = content.replace("private final List<NetworkEdge> edges = new ArrayList<>();\n", field_insert)

# Add recordedCoverage to load()
load_insert = """
        if (tag.contains("CoverageKeys") && tag.contains("CoverageValues")) {
            long[] keys = tag.getLongArray("CoverageKeys");
            int[] values = tag.getIntArray("CoverageValues");
            if (keys.length == values.length) {
                for (int j = 0; j < keys.length; j++) {
                    graph.recordedCoverage.put(keys[j], values[j]);
                }
            }
        }
"""
load_marker = "return graph;\n    }\n"
content = content.replace(load_marker, load_insert + load_marker)

# Add recordedCoverage to save()
save_insert = """
        long[] covKeys = new long[recordedCoverage.size()];
        int[] covValues = new int[recordedCoverage.size()];
        int idx = 0;
        for (Map.Entry<Long, Integer> entry : recordedCoverage.entrySet()) {
            covKeys[idx] = entry.getKey();
            covValues[idx] = entry.getValue();
            idx++;
        }
        tag.putLongArray("CoverageKeys", covKeys);
        tag.putIntArray("CoverageValues", covValues);
"""
save_marker = "tag.put(\"Edges\", edgesTag);\n"
content = content.replace(save_marker, save_marker + save_insert)

# Add getter and setter
methods = """
    public Map<Long, Integer> getRecordedCoverage() {
        return recordedCoverage;
    }

    public void addCoverageRecord(BlockPos pos, int techId, int signalLevel) {
        long key = pos.asLong();
        int value = (techId << 8) | signalLevel;
        Integer existing = recordedCoverage.get(key);
        if (existing == null || existing < value) { // Basic update rule
            recordedCoverage.put(key, value);
            setDirty();
        }
    }
"""
content = content.replace("public void addNode(NetworkNode node) {", methods + "\n    public void addNode(NetworkNode node) {")


with open("src/main/java/com/florentdubut/telecom/network/TelecomNetworkGraph.java", "w") as f:
    f.write(content)


import sys

with open("src/main/java/com/florentdubut/telecom/server/TelecomHttpServer.java") as f:
    content = f.read()

old_chunk_code = """            net.minecraft.world.level.chunk.ChunkAccess chunk = level.getChunkSource().getChunk(cx, cz, net.minecraft.world.level.chunk.status.ChunkStatus.FULL, true);
            if (chunk == null) {
                // Chunk not generated or loaded, return 404
                sendEmptyResponse(exchange, 404);
                return;
            }"""

new_chunk_code = """            net.minecraft.world.level.chunk.ChunkAccess chunk = level.getChunkSource().getChunk(cx, cz, net.minecraft.world.level.chunk.status.ChunkStatus.EMPTY, true);
            if (chunk == null || !chunk.getStatus().isOrAfter(net.minecraft.world.level.chunk.status.ChunkStatus.FULL)) {
                // Chunk not generated or loaded, return 404
                sendEmptyResponse(exchange, 404);
                return;
            }"""

content = content.replace(old_chunk_code, new_chunk_code)

with open("src/main/java/com/florentdubut/telecom/server/TelecomHttpServer.java", "w") as f:
    f.write(content)


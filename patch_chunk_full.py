import sys

with open("src/main/java/com/florentdubut/telecom/server/TelecomHttpServer.java") as f:
    content = f.read()

old_code = """            net.minecraft.world.level.chunk.ChunkAccess chunk = level.getChunkSource().getChunk(cx, cz, net.minecraft.world.level.chunk.status.ChunkStatus.EMPTY, true);
            if (chunk == null || !chunk.getStatus().isOrAfter(net.minecraft.world.level.chunk.status.ChunkStatus.FULL)) {"""

new_code = """            net.minecraft.world.level.chunk.ChunkAccess chunk = level.getChunkSource().getChunk(cx, cz, net.minecraft.world.level.chunk.status.ChunkStatus.FULL, true);
            if (chunk == null) {"""

content = content.replace(old_code, new_code)

with open("src/main/java/com/florentdubut/telecom/server/TelecomHttpServer.java", "w") as f:
    f.write(content)


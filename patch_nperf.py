import sys

with open("src/main/java/com/florentdubut/telecom/server/TelecomHttpServer.java") as f:
    content = f.read()

old_code = """                long pos = entry.getKey();
                int x = (int)(pos >> 32);
                int z = (int)pos;"""

new_code = """                long pos = entry.getKey();
                net.minecraft.core.BlockPos bp = net.minecraft.core.BlockPos.of(pos);
                int x = bp.getX();
                int z = bp.getZ();"""

content = content.replace(old_code, new_code)

with open("src/main/java/com/florentdubut/telecom/server/TelecomHttpServer.java", "w") as f:
    f.write(content)


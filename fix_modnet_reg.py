import sys

with open("src/main/java/com/florentdubut/telecom/network/ModNetworking.java") as f:
    content = f.read()

bad_reg = """public static void register(final RegisterPayloadHandlersEvent event) {
        final PayloadRegistrar registrar = event.registrar(TelecomMod.MODID);

        registrar.playToServer(
            com.florentdubut.telecom.network.packet.ToggleNperfPayload.TYPE,
            com.florentdubut.telecom.network.packet.ToggleNperfPayload.STREAM_CODEC,
            ModNetworking::handleToggleNperf
        );"""

# The registrar is ALREADY declared later in the original file. Let's just remove the bad one and insert properly.
content = content.replace(bad_reg, "public static void register(final RegisterPayloadHandlersEvent event) {")

correct_insert = """        registrar.playToServer(
            com.florentdubut.telecom.network.packet.ToggleNperfPayload.TYPE,
            com.florentdubut.telecom.network.packet.ToggleNperfPayload.STREAM_CODEC,
            ModNetworking::handleToggleNperf
        );
"""

content = content.replace("        final PayloadRegistrar registrar = event.registrar(TelecomMod.MODID);", "        final PayloadRegistrar registrar = event.registrar(TelecomMod.MODID);\n" + correct_insert)

with open("src/main/java/com/florentdubut/telecom/network/ModNetworking.java", "w") as f:
    f.write(content)


with open("src/main/java/com/florentdubut/telecom/server/TelecomHttpServer.java") as f:
    content2 = f.read()

# Fix NperfMapHandler position. I'll move it out to a top-level inner static class
bad_pos = """        private static class NperfMapHandler implements HttpHandler {"""
content2 = content2.replace(bad_pos, "    private static class NperfMapHandler implements HttpHandler {")
# It's currently stuck inside start() method probably? Let's check where it is

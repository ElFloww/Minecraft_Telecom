import sys

with open("src/main/java/com/florentdubut/telecom/network/ModNetworking.java") as f:
    content = f.read()

bad = """    public static void register(final RegisterPayloadHandlersEvent event) {

        registrar.playToServer(
            com.florentdubut.telecom.network.packet.ToggleNperfPayload.TYPE,
            com.florentdubut.telecom.network.packet.ToggleNperfPayload.STREAM_CODEC,
            ModNetworking::handleToggleNperf
        );

        final PayloadRegistrar registrar = event.registrar("1.0");"""
good = """    public static void register(final RegisterPayloadHandlersEvent event) {
        final PayloadRegistrar registrar = event.registrar("1.0");

        registrar.playToServer(
            com.florentdubut.telecom.network.packet.ToggleNperfPayload.TYPE,
            com.florentdubut.telecom.network.packet.ToggleNperfPayload.STREAM_CODEC,
            ModNetworking::handleToggleNperf
        );"""
content = content.replace(bad, good)
with open("src/main/java/com/florentdubut/telecom/network/ModNetworking.java", "w") as f:
    f.write(content)

with open("src/main/java/com/florentdubut/telecom/server/TelecomHttpServer.java") as f:
    content2 = f.read()

content2 = content2.replace("sendEmptyResponse(exchange, 500);", "exchange.sendResponseHeaders(500, -1); exchange.close();")
with open("src/main/java/com/florentdubut/telecom/server/TelecomHttpServer.java", "w") as f:
    f.write(content2)


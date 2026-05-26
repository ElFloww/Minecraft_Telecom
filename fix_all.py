import sys

with open("src/main/java/com/florentdubut/telecom/network/ModNetworking.java") as f:
    content = f.read()

# Fix registrar placement
bad_reg = """public static void register(final RegisterPayloadHandlersEvent event) {
        registrar.playToServer(
            com.florentdubut.telecom.network.packet.ToggleNperfPayload.TYPE,
            com.florentdubut.telecom.network.packet.ToggleNperfPayload.STREAM_CODEC,
            ModNetworking::handleToggleNperf
        );

        final PayloadRegistrar registrar = event.registrar(TelecomMod.MODID);"""
good_reg = """public static void register(final RegisterPayloadHandlersEvent event) {
        final PayloadRegistrar registrar = event.registrar(TelecomMod.MODID);

        registrar.playToServer(
            com.florentdubut.telecom.network.packet.ToggleNperfPayload.TYPE,
            com.florentdubut.telecom.network.packet.ToggleNperfPayload.STREAM_CODEC,
            ModNetworking::handleToggleNperf
        );"""
content = content.replace(bad_reg, good_reg)

# Fix NBT usages in ModNetworking
content = content.replace("""if (stack.hasTag() && stack.getTag().getBoolean("nperfActive")) {""", """if (stack.has(net.minecraft.core.component.DataComponents.CUSTOM_DATA)) {
                    net.minecraft.nbt.CompoundTag tag = stack.get(net.minecraft.core.component.DataComponents.CUSTOM_DATA).copyTag();
                    if (tag.getBoolean("nperfActive")) {""")
content = content.replace("""                    nperfActive = true;
                    break;
                }
            }""", """                    nperfActive = true;
                    break;
                }
                }
            }""")

content = content.replace("""stack.getOrCreateTag().putBoolean("nperfActive", payload.enabled());""", """net.minecraft.nbt.CompoundTag tag = stack.has(net.minecraft.core.component.DataComponents.CUSTOM_DATA) ? stack.get(net.minecraft.core.component.DataComponents.CUSTOM_DATA).copyTag() : new net.minecraft.nbt.CompoundTag();
                    tag.putBoolean("nperfActive", payload.enabled());
                    stack.set(net.minecraft.core.component.DataComponents.CUSTOM_DATA, net.minecraft.world.item.component.CustomData.of(tag));""")

with open("src/main/java/com/florentdubut/telecom/network/ModNetworking.java", "w") as f:
    f.write(content)


with open("src/main/java/com/florentdubut/telecom/client/gui/SmartphoneScreen.java") as f:
    content2 = f.read()

content2 = content2.replace("""currentState = stack.hasTag() && stack.getTag().getBoolean("nperfActive");""", """if (stack.has(net.minecraft.core.component.DataComponents.CUSTOM_DATA)) {
                            net.minecraft.nbt.CompoundTag tag = stack.get(net.minecraft.core.component.DataComponents.CUSTOM_DATA).copyTag();
                            currentState = tag.getBoolean("nperfActive");
                        }""")

with open("src/main/java/com/florentdubut/telecom/client/gui/SmartphoneScreen.java", "w") as f:
    f.write(content2)

with open("src/main/java/com/florentdubut/telecom/server/TelecomHttpServer.java") as f:
    content3 = f.read()

# Fix NperfMapHandler not found. It must be outside the Start Method or correctly scoped.
content3 = content3.replace("class NperfMapHandler implements HttpHandler {", "private static class NperfMapHandler implements HttpHandler {")

with open("src/main/java/com/florentdubut/telecom/server/TelecomHttpServer.java", "w") as f:
    f.write(content3)

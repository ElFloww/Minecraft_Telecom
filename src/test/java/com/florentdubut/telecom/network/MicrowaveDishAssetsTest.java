package com.florentdubut.telecom.network;

import com.florentdubut.telecom.block.MicrowaveDishBlock;
import com.florentdubut.telecom.registry.ModBlockEntities;
import com.florentdubut.telecom.registry.ModBlocks;
import com.florentdubut.telecom.registry.ModItems;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import net.minecraft.core.Direction;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.crafting.Recipe;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class MicrowaveDishAssetsTest {
    @Test
    void registrationsAndAllFacingVariantsReferenceDistinctVanillaTexturedGeometry() throws Exception {
        assertEquals("telecom:microwave_dish", BuiltInRegistries.BLOCK.getKey(ModBlocks.MICROWAVE_DISH.get()).toString());
        assertSame(ModBlocks.MICROWAVE_DISH.get(), ModItems.MICROWAVE_DISH.get().getBlock());
        assertTrue(ModBlockEntities.MICROWAVE_DISH_BE.get().isValid(ModBlocks.MICROWAVE_DISH.get().defaultBlockState()));
        var variants = json("assets/telecom/blockstates/microwave_dish.json").getAsJsonObject("variants");
        assertEquals(Set.of("facing=south", "facing=west", "facing=north", "facing=east"), variants.keySet());
        for (String facing : new String[]{"south", "west", "north", "east"}) {
            assertEquals("telecom:block/microwave_dish", variants.getAsJsonObject("facing=" + facing).get("model").getAsString());
        }
        var model = json("assets/telecom/models/block/microwave_dish.json");
        assertTrue(model.getAsJsonArray("elements").size() >= 5);
        Set<String> vanillaTextures = Set.of("minecraft:block/iron_block", "minecraft:block/polished_deepslate", "minecraft:block/copper_block");
        for (var texture : model.getAsJsonObject("textures").asMap().values()) {
            assertTrue(vanillaTextures.contains(texture.getAsString()));
        }
        assertEquals("telecom:block/microwave_dish", json("assets/telecom/models/item/microwave_dish.json").get("parent").getAsString());
        assertEquals("telecom:item/microwave_dish", json("assets/telecom/items/microwave_dish.json").getAsJsonObject("model").get("model").getAsString());
        assertEquals(Direction.SOUTH, MicrowaveDishBlock.facingForAzimuth(0));
        assertEquals(Direction.WEST, MicrowaveDishBlock.facingForAzimuth(90));
        assertEquals(Direction.NORTH, MicrowaveDishBlock.facingForAzimuth(180));
        assertEquals(Direction.EAST, MicrowaveDishBlock.facingForAzimuth(270));
        assertEquals(Direction.SOUTH, MicrowaveDishBlock.facingForAzimuth(359));
        assertEquals(Direction.SOUTH, MicrowaveDishBlock.facingForAzimuth(44));
        assertEquals(Direction.WEST, MicrowaveDishBlock.facingForAzimuth(45));
    }

    @Test
    void recipeUsesModernIngredientStringsAndDecodesWithMinecraftCodecAndLootDropsSelf() throws Exception {
        var recipe = json("data/telecom/recipe/microwave_dish.json");
        for (var ingredient : recipe.getAsJsonObject("key").asMap().values()) assertTrue(ingredient.isJsonPrimitive());
        var ops = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY).createSerializationContext(JsonOps.INSTANCE);
        assertTrue(Recipe.CODEC.parse(ops, recipe).isSuccess());
        assertEquals("telecom:microwave_dish", recipe.getAsJsonObject("result").get("id").getAsString());
        var pool = json("data/telecom/loot_table/blocks/microwave_dish.json").getAsJsonArray("pools").get(0).getAsJsonObject();
        assertEquals("telecom:microwave_dish", pool.getAsJsonArray("entries").get(0).getAsJsonObject().get("name").getAsString());
        assertEquals("minecraft:survives_explosion", pool.getAsJsonArray("conditions").get(0).getAsJsonObject().get("condition").getAsString());
    }

    @Test
    void englishAndFrenchHaveEveryDishStateAndMatchingParameters() throws Exception {
        var english = json("assets/telecom/lang/en_us.json");
        var french = json("assets/telecom/lang/fr_fr.json");
        for (String key : english.keySet()) {
            if (!key.startsWith("gui.telecom.microwave.") && !key.equals("block.telecom.microwave_dish")) continue;
            assertTrue(french.has(key), key);
            assertFalse(french.get(key).getAsString().isBlank(), key);
            assertEquals(english.get(key).getAsString().chars().filter(c -> c == '%').count(),
                    french.get(key).getAsString().chars().filter(c -> c == '%').count(), key);
        }
        for (String state : com.florentdubut.telecom.network.packet.MicrowaveGuiSyncPayload.STATES) {
            assertTrue(english.has("gui.telecom.microwave.state." + state), state);
            assertTrue(french.has("gui.telecom.microwave.state." + state), state);
        }
    }

    private static JsonObject json(String path) throws Exception {
        try (var stream = MicrowaveDishAssetsTest.class.getResourceAsStream("/" + path)) {
            assertNotNull(stream, path);
            try (var reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
                return JsonParser.parseReader(reader).getAsJsonObject();
            }
        }
    }
}

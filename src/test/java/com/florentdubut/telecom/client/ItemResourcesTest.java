package com.florentdubut.telecom.client;

import com.florentdubut.telecom.registry.ModItems;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class ItemResourcesTest {
    static Stream<Identifier> registeredItems() {
        assertFalse(ModItems.ITEMS.getEntries().isEmpty());
        return ModItems.ITEMS.getEntries().stream().map(item -> item.getId());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("registeredItems")
    void itemDescriptionReferencesExistingModelAndTextures(Identifier itemId) throws IOException {
        JsonObject description = readJson("/assets/" + itemId.getNamespace() + "/items/" + itemId.getPath() + ".json");
        assertTrue(description.has("model"), "Missing model for " + itemId);
        JsonObject model = description.getAsJsonObject("model");
        assertEquals("minecraft:model", model.get("type").getAsString());
        String modelId = itemId.getNamespace() + ":item/" + itemId.getPath();
        assertEquals(modelId, model.get("model").getAsString(), "Preserve the existing item model");

        Set<String> visited = new HashSet<>();
        while (modelId.startsWith("telecom:")) {
            assertTrue(visited.add(modelId), "Cyclic model parent: " + modelId);
            JsonObject geometry = readJson("/assets/telecom/models/" + modelId.substring("telecom:".length()) + ".json");
            assertFalse(geometry.has("overrides"), "Legacy item overrides in " + modelId);
            if (geometry.has("textures")) {
                for (var texture : geometry.getAsJsonObject("textures").entrySet()) {
                    String textureId = texture.getValue().getAsString();
                    if (textureId.startsWith("telecom:")) {
                        String path = "/assets/telecom/textures/" + textureId.substring("telecom:".length()) + ".png";
                        assertNotNull(ItemResourcesTest.class.getResource(path), "Missing texture: " + path);
                    }
                }
            }
            if (!geometry.has("parent")) break;
            modelId = Identifier.parse(geometry.get("parent").getAsString()).toString();
        }
    }

    private static JsonObject readJson(String path) throws IOException {
        try (var stream = ItemResourcesTest.class.getResourceAsStream(path)) {
            assertNotNull(stream, "Missing resource: " + path);
            try (var reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
                var json = JsonParser.parseReader(reader);
                assertTrue(json.isJsonObject(), "Expected JSON object: " + path);
                return json.getAsJsonObject();
            }
        }
    }
}

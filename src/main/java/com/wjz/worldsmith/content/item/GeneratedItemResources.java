package com.wjz.worldsmith.content.item;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.wjz.worldsmith.core.content.ContentAsset;
import com.wjz.worldsmith.core.content.ContentAssetValidation;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.resources.Identifier;

/** Each stack's item-model id includes its immutable world hash, preventing cross-world model reinterpretation. */
public final class GeneratedItemResources {
    private static final Gson GSON = new Gson();
    private GeneratedItemResources() {}

    public static Identifier modelId(String bundleHash, String itemId) {
        new WorldItemIdentity(bundleHash, itemId);
        return Identifier.fromNamespaceAndPath("worldsmith", "content/items/" + bundleHash + "/" + definitionKey(itemId));
    }

    public static Map<String, byte[]> clientResources(CustomItemRuntime.Snapshot snapshot, Map<String, byte[]> assets) {
        Map<String, byte[]> files = new LinkedHashMap<>();
        for (var definition : snapshot.definitions().values()) {
            String hash = definition.getTextureAsset();
            byte[] bytes = assets.get(hash);
            if (bytes == null) throw new IllegalArgumentException("Missing PNG asset for custom item: " + definition.getId());
            bytes = bytes.clone();
            validateTexture(hash, bytes);
            files.put("assets/worldsmith/textures/item/content/" + hash + ".png", bytes);

            String geometryPath = "item/content/" + snapshot.bundleHash() + "/" + definitionKey(definition.getId());
            JsonObject geometry = new JsonObject(); geometry.addProperty("parent", "minecraft:item/generated");
            JsonObject textures = new JsonObject(); textures.addProperty("layer0", "worldsmith:item/content/" + hash); geometry.add("textures", textures);
            files.put("assets/worldsmith/models/" + geometryPath + ".json", json(geometry));

            JsonObject model = new JsonObject(); model.addProperty("type", "minecraft:model"); model.addProperty("model", "worldsmith:" + geometryPath);
            JsonObject item = new JsonObject(); item.add("model", model);
            files.put("assets/worldsmith/items/" + modelId(snapshot.bundleHash(), definition.getId()).getPath() + ".json", json(item));
        }
        return Collections.unmodifiableMap(files);
    }

    public static void validateTexture(String hash, byte[] bytes) {
        if (bytes.length > 1024 * 1024) throw new IllegalArgumentException("Custom item PNG exceeds 1 MiB");
        var descriptor = new ContentAsset(hash, hash, "image/png", (long)bytes.length, ContentAssetValidation.INSTANCE.path(hash));
        var size = ContentAssetValidation.INSTANCE.verify(descriptor, bytes);
        if (size.getWidth() != size.getHeight() || size.getWidth() < 16 || size.getWidth() > 256 || (size.getWidth() & (size.getWidth() - 1)) != 0)
            throw new IllegalArgumentException("Custom item textures require a square power-of-two PNG from 16 through 256 pixels");
    }

    private static byte[] json(JsonObject value) { return GSON.toJson(value).getBytes(StandardCharsets.UTF_8); }
    // Addressed leaves keep logical item names separate from native filesystem paths.
    private static String definitionKey(String id) { return ContentAssetValidation.INSTANCE.hash(id.getBytes(StandardCharsets.UTF_8)); }
}

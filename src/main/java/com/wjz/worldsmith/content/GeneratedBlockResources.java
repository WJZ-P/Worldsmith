package com.wjz.worldsmith.content;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.wjz.worldsmith.core.content.CustomBlockBinding;
import com.wjz.worldsmith.core.content.CustomBlockBindings;
import com.wjz.worldsmith.core.content.CustomBlockBindingSnapshot;
import com.wjz.worldsmith.core.content.CustomBlockDefinition;
import com.wjz.worldsmith.core.content.CustomBlockLibrary;
import com.wjz.worldsmith.core.content.CustomBlockProfile;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

/** Generates a scoped pack, not files in the user's global resourcepacks folder. */
public final class GeneratedBlockResources {
    private static final Gson GSON = new Gson();
    private GeneratedBlockResources() {}

    public static Map<String, byte[]> clientResources(CustomBlockBindingSnapshot snapshot, CustomBlockLibrary library, Map<String, byte[]> assets) {
        requireMatching(snapshot, library);
        Map<String, CustomBlockDefinition> definitions = definitions(library);
        Map<String, byte[]> files = new LinkedHashMap<>();
        JsonObject names = new JsonObject();
        for (CustomBlockBinding binding : snapshot.getBindings()) {
            CustomBlockDefinition definition = definitions.get(binding.getId());
            byte[] texture = assets.get(definition.getTextureAsset());
            if (texture == null) throw new IllegalArgumentException("Missing texture asset for custom block " + definition.getId());
            texture = texture.clone();
            validateTexture(definition.getTextureAsset(), texture);
            String nativePath = binding.nativeId().substring("worldsmith:".length());
            String texturePath = "block/content/" + definition.getTextureAsset();
            files.put("assets/worldsmith/textures/" + texturePath + ".png", texture);

            JsonObject model = new JsonObject();
            model.addProperty("parent", "minecraft:block/cube_all");
            JsonObject textures = new JsonObject();
            if (definition.getProfile() == CustomBlockProfile.GLASS) {
                // 26.2 chooses render layers from sprite material transparency, not BlockRenderLayerMap.
                JsonObject material = new JsonObject();
                material.addProperty("sprite", "worldsmith:" + texturePath);
                material.addProperty("force_translucent", true);
                textures.add("all", material);
            } else textures.addProperty("all", "worldsmith:" + texturePath);
            model.add("textures", textures);
            files.put("assets/worldsmith/models/block/" + nativePath + ".json", bytes(model));

            JsonObject variant = new JsonObject();
            variant.addProperty("model", "worldsmith:block/" + nativePath);
            JsonObject variants = new JsonObject();
            variants.add("", variant);
            JsonObject blockstate = new JsonObject();
            blockstate.add("variants", variants);
            files.put("assets/worldsmith/blockstates/" + nativePath + ".json", bytes(blockstate));

            JsonObject itemModel = new JsonObject();
            itemModel.addProperty("type", "minecraft:model");
            itemModel.addProperty("model", "worldsmith:block/" + nativePath);
            JsonObject item = new JsonObject();
            item.add("model", itemModel);
            files.put("assets/worldsmith/items/" + nativePath + ".json", bytes(item));
            names.addProperty("block.worldsmith." + nativePath.replace('/', '.'), definition.getDisplayName());
        }
        if (!snapshot.getBindings().isEmpty()) {
            files.put("assets/worldsmith/lang/en_us.json", bytes(names));
            files.put("assets/worldsmith/lang/zh_cn.json", bytes(names));
        }
        return Collections.unmodifiableMap(files);
    }

    /** Loot and tool tags belong in the server data pack, alongside compiled biome/structure output. */
    public static Map<String, byte[]> serverResources(CustomBlockBindingSnapshot snapshot, CustomBlockLibrary library) {
        requireMatching(snapshot, library);
        Map<String, byte[]> files = new LinkedHashMap<>();
        JsonArray pickaxe = new JsonArray();
        JsonArray axe = new JsonArray();
        JsonArray baseStone = new JsonArray();
        for (CustomBlockBinding binding : snapshot.getBindings()) {
            String nativeId = binding.nativeId();
            String nativePath = nativeId.substring("worldsmith:".length());
            JsonObject entry = new JsonObject();
            entry.addProperty("type", "minecraft:item");
            entry.addProperty("name", nativeId);
            JsonArray entries = new JsonArray(); entries.add(entry);
            JsonObject explosion = new JsonObject(); explosion.addProperty("condition", "minecraft:survives_explosion");
            JsonArray conditions = new JsonArray(); conditions.add(explosion);
            JsonObject pool = new JsonObject(); pool.addProperty("rolls", 1); pool.add("entries", entries); pool.add("conditions", conditions);
            JsonArray pools = new JsonArray(); pools.add(pool);
            JsonObject loot = new JsonObject(); loot.addProperty("type", "minecraft:block"); loot.add("pools", pools);
            files.put("data/worldsmith/loot_table/blocks/" + nativePath + ".json", bytes(loot));
            switch (binding.getProfile()) {
                case STONE -> { pickaxe.add(nativeId); baseStone.add(nativeId); }
                case METAL -> pickaxe.add(nativeId);
                case WOOD -> axe.add(nativeId);
                case GLASS -> { }
            }
        }
        if (!pickaxe.isEmpty()) files.put("data/minecraft/tags/block/mineable/pickaxe.json", tag(pickaxe));
        if (!axe.isEmpty()) files.put("data/minecraft/tags/block/mineable/axe.json", tag(axe));
        if (!baseStone.isEmpty()) {
            files.put("data/minecraft/tags/block/base_stone_overworld.json", tag(baseStone));
            files.put("data/minecraft/tags/block/stone_ore_replaceables.json", tag(baseStone));
        }
        return Collections.unmodifiableMap(files);
    }

    public static void validateTexture(String expectedHash, byte[] png) {
        if (png.length > 1024 * 1024 || png.length < 24 || !sha256(png).equals(expectedHash))
            throw new IllegalArgumentException("Block texture hash or byte budget mismatch");
        byte[] signature = {(byte)137, 80, 78, 71, 13, 10, 26, 10};
        for (int i = 0; i < signature.length; i++) if (png[i] != signature[i]) throw new IllegalArgumentException("Block textures require PNG bytes");
        try (ImageInputStream stream = ImageIO.createImageInputStream(new ByteArrayInputStream(png))) {
            var readers = ImageIO.getImageReaders(stream);
            if (!readers.hasNext()) throw new IllegalArgumentException("Block texture has no PNG decoder");
            ImageReader reader = readers.next();
            try {
                reader.setInput(stream, true, true);
                int width = reader.getWidth(0), height = reader.getHeight(0);
                if (width != height || width < 16 || width > 256 || (width & (width - 1)) != 0)
                    throw new IllegalArgumentException("Block textures must be square power-of-two PNG images, 16 through 256 pixels");
                if (reader.read(0) == null) throw new IllegalArgumentException("Block PNG has no image");
            } finally { reader.dispose(); }
        } catch (IOException exception) { throw new IllegalArgumentException("Malformed block PNG", exception); }
    }

    public static String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }

    private static void requireMatching(CustomBlockBindingSnapshot snapshot, CustomBlockLibrary library) {
        CustomBlockBindingSnapshot planned = CustomBlockBindings.plan(snapshot.getScope(), library, snapshot);
        if (!planned.equals(snapshot)) throw new IllegalArgumentException("Block resources require the exact prepared binding snapshot");
    }
    private static Map<String, CustomBlockDefinition> definitions(CustomBlockLibrary library) {
        Map<String, CustomBlockDefinition> definitions = new LinkedHashMap<>();
        library.getBlocks().forEach(block -> definitions.put(block.getId(), block));
        return definitions;
    }
    private static byte[] tag(JsonArray ids) { JsonObject tag = new JsonObject(); tag.addProperty("replace", false); tag.add("values", ids); return bytes(tag); }
    private static byte[] bytes(JsonObject json) { return GSON.toJson(json).getBytes(StandardCharsets.UTF_8); }
}

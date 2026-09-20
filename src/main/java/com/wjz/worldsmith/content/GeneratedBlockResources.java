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
import com.wjz.worldsmith.core.content.BlockOrientation;

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
            String nativePath = binding.nativeId().substring("worldsmith:".length());
            var appearance = definition.getAppearance();
            for (String hash : appearance.assetIds()) {
                byte[] texture = assets.get(hash);
                if (texture == null) throw new IllegalArgumentException("Missing texture asset for custom block " + definition.getId() + ": " + hash);
                texture = texture.clone(); validateTexture(hash, texture);
                files.put("assets/worldsmith/textures/block/content/" + hash + ".png", texture);
            }

            JsonObject model = new JsonObject();
            model.addProperty("parent", "minecraft:block/block");
            JsonObject textures = new JsonObject();
            addTexture(textures, "particle", appearance.getParticle(), definition.getProfile());
            JsonObject faces = new JsonObject();
            appearance.faces().forEach((direction, texture) -> {
                addTexture(textures, direction, texture.getTextureAsset(), definition.getProfile());
                JsonObject face = new JsonObject(); face.addProperty("texture", "#" + direction);
                face.addProperty("cullface", direction); face.add("uv", ints(0, 0, 16, 16));
                face.addProperty("rotation", texture.getQuarterTurns() * 90); faces.add(direction, face);
            });
            model.add("textures", textures);
            JsonObject element = new JsonObject(); element.add("from", ints(0, 0, 0)); element.add("to", ints(16, 16, 16)); element.add("faces", faces);
            JsonArray elements = new JsonArray(); elements.add(element); model.add("elements", elements);
            files.put("assets/worldsmith/models/block/" + nativePath + ".json", bytes(model));

            JsonObject variants = new JsonObject();
            String[] directions = {"north", "east", "south", "west"};
            for (int turn = 0; turn < directions.length; turn++) {
                JsonObject variant = new JsonObject(); variant.addProperty("model", "worldsmith:block/" + nativePath);
                variant.addProperty("y", appearance.getOrientation() == BlockOrientation.HORIZONTAL ? turn * 90 : 0);
                // Do not lock UVs: rotating a directional sign rotates its painted front with the block.
                variant.addProperty("uvlock", false); variants.add("facing=" + directions[turn], variant);
            }
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

    private static void addTexture(JsonObject textures, String name, String hash, CustomBlockProfile profile) {
        String sprite = "worldsmith:block/content/" + hash;
        if (profile == CustomBlockProfile.GLASS) {
            // Native sprite material transparency applies consistently to every face and particle.
            JsonObject material = new JsonObject(); material.addProperty("sprite", sprite); material.addProperty("force_translucent", true);
            textures.add(name, material);
        } else textures.addProperty(name, sprite);
    }
    private static JsonArray ints(int... values) { JsonArray array = new JsonArray(); for (int value : values) array.add(value); return array; }

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

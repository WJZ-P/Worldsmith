package com.wjz.worldsmith.core.content;

import com.wjz.worldsmith.core.serialization.WorldsmithJson;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import kotlinx.serialization.json.*;

/** Explicit offline artifact generation; not a game launch, screenshot capture or regression test. */
public final class CreaturePreviewCli {
    private CreaturePreviewCli() {}

    public static void main(String[] args) throws IOException {
        if (args.length != 3) throw new IllegalArgumentException("Usage: CreaturePreviewCli <creature.json> <actual-texture.png> <output-directory>");
        Path definitionPath = Path.of(args[0]).toAbsolutePath().normalize();
        Path texturePath = Path.of(args[1]).toAbsolutePath().normalize();
        Path output = Path.of(args[2]).toAbsolutePath().normalize();
        if (!Files.isRegularFile(definitionPath) || Files.size(definitionPath) > 2 * 1024 * 1024) throw new IllegalArgumentException("Use one bounded creature definition JSON");
        if (!Files.isRegularFile(texturePath) || Files.size(texturePath) > ContentAssetValidation.MAX_ASSET_BYTES) throw new IllegalArgumentException("Use one bounded PNG texture asset");
        CreatureDefinition definition = WorldsmithJson.INSTANCE.getFormat().decodeFromString(CreatureDefinition.Companion.serializer(), Files.readString(definitionPath));
        byte[] texture = Files.readAllBytes(texturePath);
        CreaturePreview.Result hero = CreaturePreview.render(definition, texture, CreaturePreview.Options.defaults("isometric", "idle"));
        Map<String, byte[]> images = new LinkedHashMap<>();
        images.put("hero.png", hero.png());
        images.put("sheet.png", CreaturePreview.sheet(definition, texture));
        images.put("uv-debug.png", CreaturePreview.uvDebug(definition, texture));
        for (String pose : CreaturePreview.POSES) images.put("pose-" + pose + ".png", pose.equals("idle") ? hero.png() : CreaturePreview.png(definition, texture, "isometric", pose));
        Files.createDirectories(output);
        Map<String, Object> written = new LinkedHashMap<>();
        for (var image : images.entrySet()) {
            Path file = output.resolve(image.getKey()); Files.write(file, image.getValue());
            if (Files.size(file) != image.getValue().length) throw new IOException("Preview artifact write was incomplete: " + file);
            written.put(image.getKey(), Map.of("sha256", ContentAssetValidation.INSTANCE.hash(image.getValue()), "byteLength", image.getValue().length));
        }
        Map<String, Object> metadata = new LinkedHashMap<>(hero.metadata());
        metadata.put("files", written); metadata.put("sourceDefinition", definitionPath.toString()); metadata.put("sourceTexture", texturePath.toString());
        JsonObject json = (JsonObject)toJson(metadata);
        Files.writeString(output.resolve("metadata.json"), WorldsmithJson.INSTANCE.getFormat().encodeToString(JsonObject.Companion.serializer(), json), StandardCharsets.UTF_8);
        System.out.println("Offline creature preview: " + output);
        System.out.println(definition.getDisplayName() + " | " + images.size() + " PNG files | " + definition.getModel().getTexture());
        System.out.println("Fixed-camera textured model and shared runtime poses only; no Minecraft screenshot or combat verification.");
    }

    private static JsonElement toJson(Object value) {
        if (value == null) return JsonNull.INSTANCE;
        if (value instanceof String text) return JsonElementKt.JsonPrimitive(text);
        if (value instanceof Boolean bool) return JsonElementKt.JsonPrimitive(bool);
        if (value instanceof Number number) return JsonElementKt.JsonPrimitive(number);
        if (value instanceof Map<?, ?> map) {
            Map<String, JsonElement> converted = new LinkedHashMap<>(); map.forEach((key, entry) -> converted.put(key.toString(), toJson(entry)));
            return new JsonObject(converted);
        }
        if (value instanceof Collection<?> list) return new JsonArray(list.stream().map(CreaturePreviewCli::toJson).toList());
        throw new IllegalArgumentException("Unsupported preview metadata value: " + value.getClass().getName());
    }
}

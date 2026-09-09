package com.wjz.worldsmith.content;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.SharedConstants;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.AbstractPackResources;
import net.minecraft.server.packs.PackLocationInfo;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.resources.IoSupplier;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Immutable bounded virtual resource pack. Each open stream owns its cursor, and readers own no file handles. */
public final class GeneratedWorldResourcePack {
    public static final String SENTINEL_PATH = "assets/worldsmith/world_content/activation.json";
    public static final Identifier SENTINEL = Identifier.fromNamespaceAndPath("worldsmith", "world_content/activation.json");
    private static final int MAX_FILES = 4096;
    private static final long MAX_BYTES = 64L * 1024 * 1024;
    private final String scope;
    private final Map<String, byte[]> files;
    private final String contentHash;

    public GeneratedWorldResourcePack(String scope, Map<String, byte[]> input) {
        if (scope == null || scope.isBlank() || scope.length() > 256) throw new IllegalArgumentException("Invalid resource scope");
        if (input.size() > MAX_FILES) throw new IllegalArgumentException("Too many generated resource files");
        this.scope = scope;
        Map<String, byte[]> copied = new TreeMap<>();
        long bytes = 0;
        for (var entry : input.entrySet()) {
            String path = entry.getKey();
            if (path == null || path.length() > 320 || !path.matches("assets/worldsmith/[a-z0-9_./-]+\\.(json|png|mcmeta)")
                || path.contains("..") || path.contains("//") || path.equals(SENTINEL_PATH))
                throw new IllegalArgumentException("Invalid or reserved generated resource path: " + path);
            byte[] data = entry.getValue();
            if (data == null || data.length > 8 * 1024 * 1024 || (bytes += data.length) > MAX_BYTES)
                throw new IllegalArgumentException("Generated resource byte budget exceeded");
            if (path.endsWith(".json") || path.endsWith(".mcmeta")) {
                try {
                    var json = JsonParser.parseString(new String(data, StandardCharsets.UTF_8));
                    if (!json.isJsonObject()) throw new IllegalArgumentException("Generated JSON resource must be an object: " + path);
                } catch (RuntimeException malformed) { throw new IllegalArgumentException("Malformed generated JSON resource: " + path, malformed); }
            }
            copied.put(path, data.clone());
        }
        JsonObject manifest = new JsonObject();
        copied.forEach((path, data) -> manifest.addProperty(path, GeneratedBlockResources.sha256(data)));
        contentHash = GeneratedBlockResources.sha256(new Gson().toJson(manifest).getBytes(StandardCharsets.UTF_8));
        JsonObject sentinel = new JsonObject(); sentinel.addProperty("scope", scope); sentinel.addProperty("sha256", contentHash);
        copied.put(SENTINEL_PATH, new Gson().toJson(sentinel).getBytes(StandardCharsets.UTF_8));
        files = Collections.unmodifiableMap(copied);
    }

    public String scope() { return scope; }
    public String contentHash() { return contentHash; }
    public byte[] sentinelBytes() { return files.get(SENTINEL_PATH).clone(); }
    public Map<String, String> hashes() {
        Map<String, String> result = new LinkedHashMap<>();
        files.forEach((path, bytes) -> result.put(path, GeneratedBlockResources.sha256(bytes)));
        return Collections.unmodifiableMap(result);
    }

    public Pack.ResourcesSupplier supplier() {
        var format = SharedConstants.getCurrentVersion().packVersion(PackType.CLIENT_RESOURCES);
        JsonArray version = new JsonArray(); version.add(format.major()); version.add(format.minor());
        JsonObject pack = new JsonObject(); pack.addProperty("description", "Worldsmith world content: " + scope); pack.add("min_format", version); pack.add("max_format", version.deepCopy());
        JsonObject metadata = new JsonObject(); metadata.add("pack", pack);
        byte[] rootMetadata = new Gson().toJson(metadata).getBytes(StandardCharsets.UTF_8);
        return new Pack.ResourcesSupplier() {
            @Override public PackResources openPrimary(PackLocationInfo location) { return new Resources(location, rootMetadata); }
            @Override public PackResources openFull(PackLocationInfo location, Pack.Metadata ignored) { return openPrimary(location); }
        };
    }

    private final class Resources extends AbstractPackResources {
        private final byte[] metadata;
        private Resources(PackLocationInfo location, byte[] metadata) { super(location); this.metadata = metadata; }
        @Override public IoSupplier<InputStream> getRootResource(String... path) {
            return path.length == 1 && path[0].equals("pack.mcmeta") ? () -> new ByteArrayInputStream(metadata) : null;
        }
        @Override public IoSupplier<InputStream> getResource(PackType type, Identifier location) {
            if (type != PackType.CLIENT_RESOURCES || !location.getNamespace().equals("worldsmith")) return null;
            byte[] data = files.get("assets/worldsmith/" + location.getPath());
            return data == null ? null : () -> new ByteArrayInputStream(data);
        }
        @Override public void listResources(PackType type, String namespace, String directory, ResourceOutput output) {
            if (type != PackType.CLIENT_RESOURCES || !namespace.equals("worldsmith")) return;
            String prefix = "assets/worldsmith/" + directory + (directory.isEmpty() ? "" : "/");
            files.forEach((path, data) -> {
                if (path.startsWith(prefix)) output.accept(Identifier.fromNamespaceAndPath(namespace, path.substring("assets/worldsmith/".length())), () -> new ByteArrayInputStream(data));
            });
        }
        @Override public Set<String> getNamespaces(PackType type) { return type == PackType.CLIENT_RESOURCES ? Set.of("worldsmith") : Set.of(); }
        @Override public void close() { }
    }
}

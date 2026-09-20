package com.wjz.worldsmith.ability;

import com.wjz.worldsmith.Worldsmith;
import com.wjz.worldsmith.core.ability.AbilityCapabilityRegistry;
import com.wjz.worldsmith.core.ability.AbilityCapabilitySpec;
import com.wjz.worldsmith.core.ability.AbilityPrograms;
import com.wjz.worldsmith.core.ability.extension.AbilityExtension;
import com.wjz.worldsmith.core.ability.extension.AbilityExtensionArtifacts;
import com.wjz.worldsmith.core.ability.extension.AbilityExtensionInstallReceipt;
import com.wjz.worldsmith.core.serialization.WorldsmithJson;
import java.io.IOException;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import net.fabricmc.loader.api.FabricLoader;

/** Next-start, approved JAR loading only. No world/pack reload path calls this class. */
public final class NativeAbilityExtensions {
    public static final int MAX_EXTENSIONS = 32, MAX_PROVIDERS = 128;
    private static boolean started;
    private static LoadSession loaded;
    private NativeAbilityExtensions() {}

    public record LoadReport(String id, String status, String sourceHash, String artifactHash,
                             List<String> capabilities, boolean initializationAttempted, String message) {}
    @FunctionalInterface interface Registrar { void register(Map<AbilityCapabilitySpec, AbilityExtension> providers); }

    public static Path directory() { return FabricLoader.getInstance().getConfigDir().resolve("worldsmith/ability-extensions"); }

    public static synchronized void load() {
        if (started) return;
        started = true;
        loaded = loadApproved(directory(), WorldAbilityRuntime.capabilities(), NativeAbilityExtensions::registerNative);
        for (var report : loaded.reports()) {
            if (report.status().equals("LOADED")) Worldsmith.LOGGER.info("Loaded approved ability extension {} SHA-256 {}: {}", report.id(), report.artifactHash(), report.capabilities());
            else Worldsmith.LOGGER.warn("Skipped ability extension {}: {}", report.id(), report.message());
        }
    }

    public static synchronized List<LoadReport> reports() { return loaded == null ? List.of() : loaded.reports(); }
    public static synchronized String summary() {
        long count = reports().stream().filter(report -> report.status().equals("LOADED")).count();
        return "loaded=" + count + "; rejected=" + (reports().size() - count) + "; next-start loading only";
    }

    static void registerNative(Map<AbilityCapabilitySpec, AbilityExtension> providers) {
        // Preflight and insertion share the same native registry monitor, preventing partial
        // registration caused by another initializer claiming a name between these steps.
        synchronized (WorldAbilityRuntime.class) {
            var registry = WorldAbilityRuntime.capabilities();
            for (var spec : providers.keySet()) registry = registry.extend(spec);
            providers.forEach((spec, extension) -> WorldAbilityRuntime.registerCapability(spec, extension::invoke));
        }
    }

    /** Package-private test seam. It never replaces the application's already loaded session. */
    static LoadSession loadApproved(Path directory, AbilityCapabilityRegistry base, Registrar registrar) {
        var reports = new ArrayList<LoadReport>();
        var loaders = new ArrayList<URLClassLoader>();
        try {
            Path configured = directory.toAbsolutePath().normalize();
            if (!Files.exists(configured, LinkOption.NOFOLLOW_LINKS)) return new LoadSession(reports, loaders);
            require(!Files.isSymbolicLink(configured) && Files.isDirectory(configured, LinkOption.NOFOLLOW_LINKS), "Extension directory must be a non-symbolic directory");
            Path root = configured.toRealPath();
            List<Path> files;
            try (var stream = Files.list(root)) { files = stream.filter(path -> path.getFileName().toString().endsWith(".jar")).limit(MAX_EXTENSIONS + 1L).sorted().toList(); }
            require(files.size() <= MAX_EXTENSIONS, "Installed extension count exceeds " + MAX_EXTENSIONS);
            var registry = base;
            int providersLoaded = 0;
            for (Path candidate : files) {
                String file = candidate.getFileName().toString();
                String id = file.substring(0, file.length() - 4);
                String sourceHash = "", artifactHash = "";
                boolean initializationAttempted = false;
                URLClassLoader loader = null;
                try {
                    require(AbilityPrograms.validId(id), "Invalid installed extension filename");
                    require(regular(root, candidate, AbilityExtensionArtifacts.MAX_ARTIFACT_BYTES), "Invalid installed JAR path/size");
                    Path receiptPath = root.resolve(id + ".approved.json");
                    require(regular(root, receiptPath, 65536), "No bounded independent installation approval receipt");
                    var receipt = WorldsmithJson.INSTANCE.getFormat().decodeFromString(AbilityExtensionInstallReceipt.Companion.serializer(), Files.readString(receiptPath));
                    require(receipt.getApiVersion() == 1 && receipt.getId().equals(id) && receipt.getApprovedAtMillis() > 0
                        && receipt.getSourceHash().matches("[a-f0-9]{64}") && receipt.getArtifactHash().matches("[a-f0-9]{64}"), "Invalid approval receipt identity");
                    artifactHash = AbilityExtensionArtifacts.hash(Files.readAllBytes(candidate));
                    // Crucially, no plugin class is loaded or initialized before the complete SHA matches.
                    require(receipt.getArtifactHash().equals(artifactHash), "Installed JAR differs from the explicitly approved artifact hash");
                    var artifact = AbilityExtensionArtifacts.inspect(candidate);
                    var manifest = artifact.getManifest(); sourceHash = manifest.getSourceHash();
                    require(manifest.getId().equals(id) && sourceHash.equals(receipt.getSourceHash()) && artifact.getArtifactHash().equals(artifactHash), "Approved source/manifest identity mismatch");
                    require(providersLoaded + manifest.getEntryClasses().size() <= MAX_PROVIDERS, "Installed provider count exceeds " + MAX_PROVIDERS);
                    var proposed = registry;
                    for (String entry : manifest.getEntryClasses()) proposed = proposed.extend(manifest.getDeclaredSpecs().get(entry));
                    // Keep an immutable startup image open, not <id>.jar: Windows must still
                    // permit a newly approved replacement to be staged for the NEXT startup.
                    Path image = startupImage(root, candidate, artifactHash);
                    loader = new URLClassLoader(new java.net.URL[] {image.toUri().toURL()}, AbilityExtension.class.getClassLoader());
                    final URLClassLoader candidateLoader = loader;
                    var providers = ServiceLoader.load(AbilityExtension.class, loader).stream()
                        .filter(provider -> provider.type().getClassLoader() == candidateLoader).limit(17).toList();
                    require(providers.size() == manifest.getEntryClasses().size()
                        && providers.stream().map(provider -> provider.type().getName()).toList().equals(manifest.getEntryClasses()), "ServiceLoader provider order/identity differs from the approved manifest");
                    var verified = new LinkedHashMap<AbilityCapabilitySpec, AbilityExtension>();
                    for (var provider : providers) {
                        String entry = provider.type().getName();
                        initializationAttempted = true;
                        var extension = provider.get();
                        var expected = manifest.getDeclaredSpecs().get(entry);
                        require(expected.equals(extension.spec()), "Actual spec() differs from declaredSpec for " + entry);
                        verified.put(expected, extension);
                    }
                    registrar.register(Collections.unmodifiableMap(verified));
                    registry = proposed;
                    providersLoaded += verified.size();
                    loaders.add(loader); loader = null;
                    reports.add(new LoadReport(id, "LOADED", sourceHash, artifactHash,
                        verified.keySet().stream().map(AbilityCapabilitySpec::getName).toList(), true, "Approved providers loaded at startup; no live replacement"));
                } catch (Exception | LinkageError | ServiceConfigurationError failure) {
                    reports.add(new LoadReport(id, "REJECTED", sourceHash, artifactHash, List.of(), initializationAttempted, bounded(failure)));
                } finally { if (loader != null) try { loader.close(); } catch (IOException ignored) { } }
            }
        } catch (Exception failure) { reports.add(new LoadReport("", "REJECTED", "", "", List.of(), false, bounded(failure))); }
        return new LoadSession(reports, loaders);
    }

    private static boolean regular(Path root, Path file, long maximum) throws IOException {
        return Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(file)
            && file.toRealPath().startsWith(root) && Files.size(file) > 0 && Files.size(file) <= maximum;
    }
    private static Path startupImage(Path root, Path candidate, String hash) throws IOException {
        Path cache = root.resolve(".loaded");
        require(!Files.isSymbolicLink(cache), "Startup image cache is a symbolic link");
        Files.createDirectories(cache);
        require(cache.toRealPath().startsWith(root), "Startup image cache escaped the extension directory");
        Path image = cache.resolve(hash + ".jar");
        if (Files.exists(image, LinkOption.NOFOLLOW_LINKS)) {
            require(regular(root, image, AbilityExtensionArtifacts.MAX_ARTIFACT_BYTES)
                && AbilityExtensionArtifacts.hash(Files.readAllBytes(image)).equals(hash), "Startup image hash mismatch; existing cache was preserved");
            return image;
        }
        try (var entries = Files.list(cache)) { require(entries.filter(path -> path.getFileName().toString().matches("[a-f0-9]{64}\\.jar")).limit(129).count() < 128,
            "Startup image cache reached 128 artifacts; preserve active/rollback JARs and prune unused startup images before adding more"); }
        byte[] bytes = Files.readAllBytes(candidate);
        require(AbilityExtensionArtifacts.hash(bytes).equals(hash), "Approved JAR changed before its startup snapshot");
        Path temporary = Files.createTempFile(cache, ".load-", ".tmp");
        try {
            Files.write(temporary, bytes);
            Files.move(temporary, image, StandardCopyOption.ATOMIC_MOVE);
        } finally { Files.deleteIfExists(temporary); }
        return image;
    }
    private static void require(boolean condition, String message) { if (!condition) throw new IllegalArgumentException(message); }
    private static String bounded(Throwable failure) {
        String value = failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
        return value.substring(0, Math.min(512, value.length()));
    }

    static final class LoadSession implements AutoCloseable {
        private final List<LoadReport> reports;
        private final List<URLClassLoader> loaders;
        LoadSession(List<LoadReport> reports, List<URLClassLoader> loaders) { this.reports = List.copyOf(reports); this.loaders = List.copyOf(loaders); }
        List<LoadReport> reports() { return reports; }
        @Override public void close() { for (var loader : loaders) try { loader.close(); } catch (IOException ignored) { } }
    }
}

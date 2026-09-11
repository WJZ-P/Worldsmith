package com.wjz.worldsmith.content;

import com.wjz.worldsmith.content.creature.CreatureRuntime;
import com.wjz.worldsmith.core.content.*;
import com.wjz.worldsmith.core.model.WorldsmithPack;
import com.wjz.worldsmith.core.pack.WorldContentBundleIO;
import com.wjz.worldsmith.core.pack.WorldsmithPackLoader;
import com.wjz.worldsmith.core.serialization.WorldsmithJson;
import java.awt.image.BufferedImage;
import java.io.*;
import java.lang.reflect.Proxy;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import javax.imageio.ImageIO;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.resources.IoSupplier;
import net.minecraft.server.packs.resources.ResourceManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class WorldContentRuntimeTest {
    @BeforeAll static void bootstrapNativeAttributes() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    @TempDir Path temp;
    private WorldContentRuntime.ClientLease client;

    @AfterEach void clear() {
        if (WorldContentRuntime.activeScope() != null) {
            WorldContentRuntime.beginClientTransition(null, client).commit(); client = null;
        }
        CreatureRuntime.clearClient();
    }

    private static WorldsmithPack bundle(String name) throws IOException {
        var base = WorldsmithPackLoader.loadClasspath("worldsmith/packs/ashlands");
        var image = new BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 32; y++) for (int x = 0; x < 32; x++) image.setRGB(x, y, 0xff9174be);
        var output = new ByteArrayOutputStream(); ImageIO.write(image, "png", output);
        byte[] png = output.toByteArray(); String hash = GeneratedBlockResources.sha256(png);
        var blocks = WorldsmithJson.INSTANCE.getFormat().decodeFromString(CustomBlockLibrary.Companion.serializer(),
            "{\"blocks\":[{\"id\":\"moonstone\",\"displayName\":\"Moonstone\",\"textureAsset\":\"" + hash + "\"}]}");
        var creatures = WorldsmithJson.INSTANCE.getFormat().decodeFromString(CreatureLibrary.Companion.serializer(), """
            {"creatures":[{"id":"guardian","displayName":"%s Guardian","category":"HOSTILE","model":{
            "texture":"%s","textureWidth":32,"textureHeight":32,"bones":[{"id":"body","cubes":[{"origin":{},"size":{"x":4,"y":8,"z":4}}]}]}}]}
            """.formatted(name, hash));
        return WorldContentBundleIO.create(name, "A complete world-content lifecycle fixture", base.getTerrain(), base.getBiomes(), base.getFeatures(),
            base.getStructures(), base.getTheme(), blocks, creatures, Map.of(hash, png));
    }

    private static WorldContentRuntime.Prepared prepare(WorldsmithPack pack) {
        Map<String, String> biomes = new LinkedHashMap<>();
        pack.getBiomes().getBiomes().forEach(b -> biomes.put(b.getId(), "worldsmith:generated/" + pack.getManifest().getId() + "/" + b.getId()));
        return WorldContentRuntime.prepare(pack, biomes);
    }

    @Test void preparationStagesBothResourceDomainsWithoutPublishingAnything() throws Exception {
        var prepared = prepare(bundle("Moon Realm"));
        assertNull(WorldContentRuntime.activeScope()); assertNull(WorldBlockBindings.active()); assertNull(CreatureRuntime.clientSnapshot());
        assertTrue(prepared.clientResources().keySet().stream().anyMatch(path -> path.startsWith("assets/worldsmith/textures/content/")));
        assertTrue(prepared.clientResources().keySet().stream().anyMatch(path -> path.startsWith("assets/worldsmith/blockstates/")));
        assertTrue(prepared.serverResources().containsKey(WorldContentRuntime.EMBEDDED_MANIFEST));
        assertTrue(prepared.serverResources().containsKey(WorldContentRuntime.BINDINGS_PATH));
        var first = prepared.clientResources(); String key = first.keySet().iterator().next(); byte original = first.get(key)[0];
        first.get(key)[0] ^= 127;
        assertEquals(original, prepared.clientResources().get(key)[0]);
        assertThrows(UnsupportedOperationException.class, first::clear);
    }

    @Test void stagedClientReservationIsCancelableAndPreventsOverlappingPublication() throws Exception {
        var prepared = prepare(bundle("Moon Realm"));
        var transition = WorldContentRuntime.beginClientTransition(prepared, null);
        assertNull(WorldContentRuntime.activeScope());
        assertThrows(IllegalStateException.class, () -> WorldContentRuntime.beginClientTransition(prepared, null));
        transition.cancel(); transition.cancel();
        assertNull(WorldBlockBindings.active());
    }

    @Test void sameScopeClientReplacementReusesNativeSnapshotWithoutSecondCommit() throws Exception {
        var pack = bundle("Moon Realm"); var prepared = prepare(pack);
        client = WorldContentRuntime.beginClientTransition(prepared, null).commit();
        var firstMapping = WorldBlockBindings.active();
        client = WorldContentRuntime.beginClientTransition(prepare(pack), client).commit();
        assertSame(firstMapping, WorldBlockBindings.active());
        assertEquals(prepared.scope(), CreatureRuntime.clientSnapshot().bundleHash());
    }

    @Test void worldSwitchInvalidatesOldClientLeaseAndClearRemovesDefinitions() throws Exception {
        var first = prepare(bundle("First Moon")); var second = prepare(bundle("Second Moon"));
        client = WorldContentRuntime.beginClientTransition(first, null).commit();
        var stale = client;
        client = WorldContentRuntime.beginClientTransition(second, client).commit();
        assertEquals(second.scope(), WorldContentRuntime.activeScope());
        assertThrows(IllegalStateException.class, () -> WorldContentRuntime.beginClientTransition(null, stale));
        WorldContentRuntime.beginClientTransition(null, client).commit(); client = null;
        assertNull(WorldContentRuntime.activeScope()); assertNull(WorldBlockBindings.active()); assertNull(CreatureRuntime.clientSnapshot());
    }

    @Test void directoryAndZipDatapacksRestorePortableBundleAndExactNativeSlots() throws Exception {
        var prepared = prepare(bundle("Portable Moon"));
        for (var entry : prepared.serverResources().entrySet()) {
            Path file = temp.resolve("directory").resolve(entry.getKey()); Files.createDirectories(file.getParent()); Files.write(file, entry.getValue());
        }
        Path zipFile = temp.resolve("portable.zip");
        try (var zip = new ZipOutputStream(Files.newOutputStream(zipFile))) {
            for (var entry : prepared.serverResources().entrySet()) { zip.putNextEntry(new ZipEntry(entry.getKey())); zip.write(entry.getValue()); zip.closeEntry(); }
        }
        for (Path path : List.of(temp.resolve("directory"), zipFile)) {
            var loaded = WorldContentRuntime.loadEmbedded(path).orElseThrow();
            assertEquals(prepared.scope(), loaded.pack().getComputedId()); assertEquals(prepared.blockBindings(), loaded.blockBindings());
            var restored = WorldContentRuntime.prepare(loaded);
            assertEquals(prepared.scope(), restored.scope()); assertEquals(prepared.creatures().biomeBindings(), restored.creatures().biomeBindings());
            assertEquals(prepared.blockBindings(), restored.blockBindings());
        }
    }

    @Test void selectedPackReaderRejectsMultipleWorldScopesInsteadOfUsingPackOrder() throws Exception {
        var first = prepare(bundle("First Moon")); var second = prepare(bundle("Second Moon"));
        assertEquals(first.scope(), WorldContentRuntime.loadSelected(resources(pack(first.serverResources()), pack(first.serverResources()))).orElseThrow().pack().getComputedId());
        assertThrows(IllegalStateException.class, () -> WorldContentRuntime.loadSelected(resources(pack(first.serverResources()), pack(second.serverResources()))));
    }

    @Test void incompleteAndTamperedEmbeddedWorldsAreRejected() throws Exception {
        var prepared = prepare(bundle("Moon Realm"));
        var missing = new LinkedHashMap<>(prepared.serverResources()); missing.remove(WorldContentRuntime.BINDINGS_PATH);
        assertThrows(IllegalArgumentException.class, () -> WorldContentRuntime.loadEmbedded(pack(missing)));
        var changed = new LinkedHashMap<>(prepared.serverResources());
        String texture = changed.keySet().stream().filter(path -> path.startsWith(WorldContentRuntime.EMBEDDED_ROOT + "assets/")).findFirst().orElseThrow();
        changed.get(texture)[0] = 0;
        assertThrows(RuntimeException.class, () -> WorldContentRuntime.loadEmbedded(pack(changed)));
        assertTrue(WorldContentRuntime.loadEmbedded(pack(Map.of())).isEmpty());
    }

    @Test void mismatchedOrUnscopedBiomeMappingsAreRejected() throws Exception {
        var pack = bundle("Moon Realm");
        assertThrows(IllegalArgumentException.class, () -> WorldContentRuntime.prepare(pack, Map.of()));
        Map<String, String> map = new LinkedHashMap<>(); pack.getBiomes().getBiomes().forEach(b -> map.put(b.getId(), "worldsmith:" + b.getId()));
        assertThrows(IllegalArgumentException.class, () -> WorldContentRuntime.prepare(pack, map));
    }

    private static PackResources pack(Map<String, byte[]> files) {
        return (PackResources) Proxy.newProxyInstance(PackResources.class.getClassLoader(), new Class<?>[]{PackResources.class}, (proxy, method, args) -> {
            if (method.getName().equals("getRootResource")) {
                byte[] bytes = files.get(String.join("/", (String[])args[0]));
                return bytes == null ? null : (IoSupplier<InputStream>) () -> new ByteArrayInputStream(bytes);
            }
            if (method.getName().equals("packId")) return "fixture";
            if (method.getName().equals("toString")) return "Selected fixture pack";
            throw new UnsupportedOperationException(method.getName());
        });
    }
    private static ResourceManager resources(PackResources... packs) {
        return (ResourceManager) Proxy.newProxyInstance(ResourceManager.class.getClassLoader(), new Class<?>[]{ResourceManager.class}, (proxy, method, args) -> {
            if (method.getName().equals("listPacks")) return Stream.of(packs);
            throw new UnsupportedOperationException(method.getName());
        });
    }
}

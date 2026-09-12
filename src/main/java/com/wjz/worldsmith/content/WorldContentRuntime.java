package com.wjz.worldsmith.content;

import com.wjz.worldsmith.content.creature.CreatureRuntime;
import com.wjz.worldsmith.content.item.CustomItemRuntime;
import com.wjz.worldsmith.content.item.GeneratedItemResources;
import com.wjz.worldsmith.content.quest.server.QuestRuntime;
import com.wjz.worldsmith.content.quest.WorldArrivalPresentation;
import com.wjz.worldsmith.core.content.*;
import com.wjz.worldsmith.core.draw.DrawSnapshotCodec;
import com.wjz.worldsmith.core.model.WorldsmithPack;
import com.wjz.worldsmith.core.model.WorldsmithPackManifest;
import com.wjz.worldsmith.core.pack.WorldContentBundleIO;
import com.wjz.worldsmith.core.pack.WorldsmithPackLoader;
import com.wjz.worldsmith.core.pack.WorldsmithPackSource;
import com.wjz.worldsmith.core.serialization.WorldsmithJson;
import com.wjz.worldsmith.core.validation.DiagnosticSeverity;
import com.wjz.worldsmith.core.validation.WorldsmithPackValidator;
import com.wjz.worldsmith.worldgen.CompiledPack;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.resources.ResourceManager;

/**
 * Publication boundary for one local save, shared by integrated client and all server dimensions.
 * Preparing/compiling never changes live bindings. Only explicitly owned activation does so.
 */
public final class WorldContentRuntime {
    public static final String EMBEDDED_ROOT = "worldsmith-content/";
    public static final String EMBEDDED_MANIFEST = EMBEDDED_ROOT + "worldsmith.json";
    // Outside the bundle root: no valid user module path can collide with this runtime sidecar.
    public static final String BINDINGS_PATH = "worldsmith-runtime/block-bindings.json";
    private static final int MAX_BINDINGS_BYTES = 64 * 1024;
    private static Active active;
    private static ClientTransition pendingClient;

    private WorldContentRuntime() {}

    public static Prepared prepare(WorldsmithPack pack, Map<String, String> biomeBindings, CustomBlockBindingSnapshot persisted) {
        validate(pack);
        var blocks = new CustomBlockLibrary(pack.getBlocks().getSchemaVersion(), List.copyOf(pack.getBlocks().getBlocks()));
        var bindings = CustomBlockBindings.plan(pack.getManifest().getId(), blocks, persisted);
        if (!biomeBindings.keySet().equals(new HashSet<>(pack.getBiomes().getBiomes().stream().map(b -> b.getId()).toList())))
            throw new IllegalArgumentException("Native biome bindings must exactly cover the bundle's logical biomes");
        if (new HashSet<>(biomeBindings.values()).size() != biomeBindings.size())
            throw new IllegalArgumentException("Distinct logical biomes need distinct native registry bindings");
        biomeBindings.forEach((logical, nativeId) -> {
            if (!nativeId.equals("worldsmith:generated/" + pack.getManifest().getId() + "/" + logical))
                throw new IllegalArgumentException("Portable embedded worlds require hash-scoped native biome identities");
        });
        var items = CustomItemRuntime.prepare(pack.getManifest().getId(), pack.getItems());
        items.definitions().values().forEach(item -> items.stack(CustomItemRuntime.LOGICAL_PREFIX + item.getId(), 1));
        var creatures = CreatureRuntime.prepare(pack.getManifest().getId(), pack.getCreatures(), biomeBindings, items, WorldBlockBindings.resolver(bindings));
        var quests = QuestRuntime.prepare(pack, creatures, items, WorldBlockBindings.resolver(bindings));
        var assets = pack.getAssets();
        Map<String, byte[]> client = new LinkedHashMap<>(GeneratedBlockResources.clientResources(bindings, blocks, assets));
        GeneratedItemResources.clientResources(items, assets).forEach((path, bytes) -> putUnique(client, path, bytes));
        for (var definition : creatures.definitions().values()) {
            String asset = definition.getModel().getTexture();
            byte[] png = Objects.requireNonNull(assets.get(asset), "Missing creature PNG asset " + asset);
            putUnique(client, "assets/worldsmith/textures/content/" + asset + ".png", png);
        }
        // Check the combined (possibly shared/duplicated-at-different-path) resource budget before exporting.
        new GeneratedWorldResourcePack(pack.getManifest().getId(), client);
        Map<String, byte[]> server = new LinkedHashMap<>(GeneratedBlockResources.serverResources(bindings, blocks));
        com.wjz.worldsmith.content.quest.GeneratedQuestAdvancements.serverResources(pack, quests.worldTitle())
            .forEach((path, bytes) -> putUnique(server, path, bytes));
        var bundle = WorldContentBundleIO.encode(pack);
        putUnique(server, EMBEDDED_MANIFEST, WorldsmithJson.INSTANCE.getFormat().encodeToString(WorldsmithPackManifest.Companion.serializer(), bundle.getManifest()).getBytes(StandardCharsets.UTF_8));
        bundle.getTexts().forEach((path, text) -> putUnique(server, EMBEDDED_ROOT + path, text.getBytes(StandardCharsets.UTF_8)));
        bundle.getBinaries().forEach((path, bytes) -> putUnique(server, EMBEDDED_ROOT + path, bytes));
        putUnique(server, BINDINGS_PATH, CustomBlockBindings.encode(bindings).getBytes(StandardCharsets.UTF_8));
        return new Prepared(pack.getManifest().getId(), blocks, bindings, creatures, items, quests, WorldArrivalPresentation.from(pack), client, server);
    }

    public static Prepared prepare(WorldsmithPack pack, Map<String, String> biomeBindings) { return prepare(pack, biomeBindings, null); }

    /** Reuses compilation's exact slot assignment rather than separately allocating native block hosts. */
    public static Prepared prepare(CompiledPack compiled) { return prepare(compiled, compiled.blockBindings()); }

    public static Prepared prepare(CompiledPack compiled, CustomBlockBindingSnapshot persisted) {
        if (!compiled.scoped()) throw new IllegalArgumentException("Built-in unscoped datagen does not publish embedded custom-world content");
        if (persisted != null && !persisted.equals(compiled.blockBindings()))
            throw new IllegalArgumentException("Compiled native block mapping differs from persisted save bindings; compile with that snapshot first");
        Map<String, String> biomes = new LinkedHashMap<>();
        compiled.definitions().forEach(b -> biomes.put(b.getId(), compiled.biomeKey(b.getId()).identifier().toString()));
        Prepared prepared = prepare(compiled.pack(), biomes, compiled.blockBindings());
        if (!prepared.blockBindings.equals(compiled.blockBindings())) throw new IllegalStateException("Native block mapping changed after compilation");
        return prepared;
    }

    private static void validate(WorldsmithPack pack) {
        var errors = WorldsmithPackValidator.INSTANCE.validate(pack).stream().filter(d -> d.getSeverity() == DiagnosticSeverity.ERROR).toList();
        if (!errors.isEmpty()) throw new IllegalArgumentException("World content validation failed: " + errors.stream().limit(12).toList());
    }

    /** Bind on the server lifecycle thread, before chunks/entities of this level start ticking. */
    public static synchronized void bindLevel(ServerLevel level, Prepared prepared) {
        Objects.requireNonNull(level); Objects.requireNonNull(prepared);
        if (pendingClient != null) throw new IllegalStateException("Await client content activation before binding a server level");
        Active previous = active;
        Active owner = acquire(prepared, false);
        if (owner.levels.stream().anyMatch(existing -> existing.getServer() != level.getServer()))
            throw new IllegalStateException("Native block hosts already belong to another live server");
        if (owner.levels.contains(level)) return;
        var previousItems = CustomItemRuntime.snapshot(level);
        var previousCreatures = CreatureRuntime.snapshot(level);
        var previousQuests = QuestRuntime.snapshot(level);
        try {
            CustomItemRuntime.bind(level, owner.prepared.items);
            CreatureRuntime.bind(level, owner.prepared.creatures);
            QuestRuntime.bind(level, owner.prepared.quests);
            owner.levels.add(level);
        } catch (RuntimeException failure) {
            if (previousItems == null) CustomItemRuntime.unbind(level);
            if (previousCreatures == null) CreatureRuntime.unbind(level);
            if (previousQuests == null) QuestRuntime.unbind(level);
            if (previous == null && owner.client == null && owner.levels.isEmpty()) {
                owner.activation.rollback(); active = null;
            }
            throw failure;
        }
    }

    public static synchronized void unbindLevel(ServerLevel level) {
        QuestRuntime.unbind(level);
        CustomItemRuntime.unbind(level);
        CreatureRuntime.unbind(level);
        if (active != null) active.levels.remove(level);
        releaseIfUnowned();
    }

    /** A server-stop event owns only its server, never a later client's world. */
    public static synchronized void clearServer(MinecraftServer server) {
        if (active == null) return;
        var owned = active.levels.stream().filter(level -> level.getServer() == server).toList();
        for (var level : owned) { QuestRuntime.unbind(level); CreatureRuntime.unbind(level); CustomItemRuntime.unbind(level); active.levels.remove(level); }
        releaseIfUnowned();
    }

    public static synchronized String activeScope() { return active == null ? null : active.prepared.scope; }
    public static synchronized int boundLevelCount() { return active == null ? 0 : active.levels.size(); }

    /** Reserve a client change while its asynchronous resource reload is in flight. No live mutation yet. */
    public static synchronized ClientTransition beginClientTransition(Prepared target, ClientLease expectedClient) {
        if (pendingClient != null) throw new IllegalStateException("Another client content transition is already in progress");
        ClientLease current = active == null ? null : active.client;
        if (current != expectedClient || current != null && current.closed)
            throw new IllegalStateException("The caller no longer owns the active client content");
        if (active != null) {
            assertOwnedBindings(active);
            if (target != null && !equivalent(active.prepared, target) && !active.levels.isEmpty())
                throw new IllegalStateException("Stop the active server before replacing its immutable world content");
        } else if (WorldBlockBindings.active() != null) {
            throw new IllegalStateException("An unmanaged block binding is active; close its lifecycle owner before publication");
        }
        pendingClient = new ClientTransition(target, active, current);
        return pendingClient;
    }

    private static Active acquire(Prepared target, boolean replacingClient) {
        if (active != null) {
            assertOwnedBindings(active);
            if (equivalent(active.prepared, target)) return active;
            if (!replacingClient || !active.levels.isEmpty()) throw new IllegalStateException("Another immutable world owns the native content hosts");
        }
        var current = WorldBlockBindings.active();
        if (active == null && current != null) throw new IllegalStateException("Unmanaged native block bindings are active");
        // Create the mutation transaction at commit time, not when potentially long-running authoring started.
        var activation = WorldBlockBindings.prepare(target.scope, target.blocks, target.blockBindings);
        if (current == null) activation.commit(); else activation.commitForNewWorld(current);
        active = new Active(target, activation);
        return active;
    }

    private static boolean equivalent(Prepared a, Prepared b) {
        return a.scope.equals(b.scope) && a.blockBindings.equals(b.blockBindings)
            && a.creatures.biomeBindings().equals(b.creatures.biomeBindings()) && a.items.definitions().equals(b.items.definitions())
            && a.quests.definitions().equals(b.quests.definitions());
    }

    private static void assertOwnedBindings(Active owner) {
        if (WorldBlockBindings.active() != owner.activation.snapshot())
            throw new IllegalStateException("Native block mapping was changed outside its world lifecycle owner");
    }

    private static void releaseIfUnowned() {
        if (active != null && active.levels.isEmpty() && active.client == null && pendingClient == null) {
            assertOwnedBindings(active);
            WorldBlockBindings.clear(active.prepared.scope);
            active = null;
        }
    }

    private static final class Active {
        final Prepared prepared;
        final WorldBlockBindings.Prepared activation;
        final Set<ServerLevel> levels = Collections.newSetFromMap(new IdentityHashMap<>());
        ClientLease client;
        Active(Prepared prepared, WorldBlockBindings.Prepared activation) { this.prepared = prepared; this.activation = activation; }
    }

    public static final class ClientLease {
        private final Prepared prepared;
        private boolean closed;
        private ClientLease(Prepared prepared) { this.prepared = prepared; }
        public String scope() { return prepared.scope; }
    }

    public static final class ClientTransition {
        private final Prepared target;
        private final Active previous;
        private final ClientLease previousClient;
        private boolean finished;
        private ClientTransition(Prepared target, Active previous, ClientLease previousClient) {
            this.target = target; this.previous = previous; this.previousClient = previousClient;
        }

        /** Called after the matching resources were verified loaded. Null target commits a client clear. */
        public ClientLease commit() {
            synchronized (WorldContentRuntime.class) {
                if (finished || pendingClient != this || active != previous) throw new IllegalStateException("Client content transition is stale");
                try {
                    ClientLease next = null;
                    if (target == null) {
                        if (active != null) active.client = null;
                        CreatureRuntime.clearClient();
                        CustomItemRuntime.clearClient();
                    } else {
                        Active owner = acquire(target, true);
                        next = new ClientLease(target); owner.client = next;
                        CreatureRuntime.activateClient(target.creatures);
                        CustomItemRuntime.activateClient(target.items);
                    }
                    if (previousClient != null) previousClient.closed = true;
                    finished = true; pendingClient = null;
                    releaseIfUnowned();
                    return next;
                } catch (RuntimeException failure) {
                    finished = true; pendingClient = null; releaseIfUnowned();
                    throw failure;
                }
            }
        }

        public void cancel() {
            synchronized (WorldContentRuntime.class) {
                if (finished) return;
                if (pendingClient != this) throw new IllegalStateException("Another transition owns client publication");
                finished = true; pendingClient = null; releaseIfUnowned();
            }
        }
    }

    public static final class Prepared {
        private final String scope;
        private final CustomBlockLibrary blocks;
        private final CustomBlockBindingSnapshot blockBindings;
        private final CreatureRuntime.Snapshot creatures;
        private final CustomItemRuntime.Snapshot items;
        private final QuestRuntime.Snapshot quests;
        private final WorldArrivalPresentation presentation;
        private final Map<String, byte[]> clientResources;
        private final Map<String, byte[]> serverResources;
        private Prepared(String scope, CustomBlockLibrary blocks, CustomBlockBindingSnapshot bindings, CreatureRuntime.Snapshot creatures, CustomItemRuntime.Snapshot items, QuestRuntime.Snapshot quests, WorldArrivalPresentation presentation,
                         Map<String, byte[]> client, Map<String, byte[]> server) {
            this.scope = scope; this.blocks = blocks; this.blockBindings = bindings; this.creatures = creatures; this.items = items; this.quests = quests;
            this.presentation = presentation;
            this.clientResources = freezeBytes(client); this.serverResources = freezeBytes(server);
        }
        public String scope() { return scope; }
        public CustomBlockBindingSnapshot blockBindings() { return blockBindings; }
        public WorldBlockBindings.Resolver blockResolver() { return WorldBlockBindings.resolver(blockBindings); }
        public CreatureRuntime.Snapshot creatures() { return creatures; }
        public CustomItemRuntime.Snapshot items() { return items; }
        public QuestRuntime.Snapshot quests() { return quests; }
        public WorldArrivalPresentation presentation() { return presentation; }
        public Map<String, byte[]> clientResources() { return freezeBytes(clientResources); }
        /** Includes the complete immutable bundle and exact slot mapping for storage inside the save's datapack. */
        public Map<String, byte[]> serverResources() { return freezeBytes(serverResources); }
    }

    public record EmbeddedWorld(WorldsmithPack pack, CustomBlockBindingSnapshot blockBindings) {}

    /** Restoring persisted local-world content does not recompile any structure or regenerate assets. */
    public static Prepared prepare(EmbeddedWorld embedded) {
        String hash = embedded.pack.getManifest().getId();
        Map<String, String> biomes = new LinkedHashMap<>();
        embedded.pack.getBiomes().getBiomes().forEach(b -> biomes.put(b.getId(), "worldsmith:generated/" + hash + "/" + b.getId()));
        return prepare(embedded.pack, biomes, embedded.blockBindings);
    }

    /** Read only currently selected data packs. Never discover definitions in unrelated config folders. */
    public static Optional<EmbeddedWorld> loadSelected(ResourceManager resources) {
        EmbeddedWorld selected = null;
        try (var packs = resources.listPacks()) {
            for (PackResources pack : packs.toList()) {
                Optional<EmbeddedWorld> candidate = loadEmbedded(pack);
                if (candidate.isEmpty()) continue;
                EmbeddedWorld next = candidate.get();
                if (selected != null && (!selected.pack.getManifest().getId().equals(next.pack.getManifest().getId()) || !selected.blockBindings.equals(next.blockBindings)))
                    throw new IllegalStateException("Multiple different Worldsmith world-content bundles are selected; choose exactly one immutable world bundle");
                selected = next;
            }
        }
        return Optional.ofNullable(selected);
    }

    public static Optional<EmbeddedWorld> loadEmbedded(PackResources pack) {
        boolean manifest = pack.getRootResource(EMBEDDED_MANIFEST.split("/")) != null;
        boolean bindings = pack.getRootResource(BINDINGS_PATH.split("/")) != null;
        if (!manifest && !bindings) return Optional.empty();
        if (!manifest || !bindings) throw new IllegalArgumentException("Incomplete embedded world content in selected pack " + pack.packId());
        return Optional.of(readEmbedded((path, limit) -> {
            var supplier = pack.getRootResource(path.split("/"));
            if (supplier == null) throw new IOException("Missing embedded content file: " + path);
            try (var stream = supplier.get()) { return readBounded(stream, limit); }
        }));
    }

    /** Creation-screen restore helper for a selected directory or ZIP datapack. No extraction or writes. */
    public static Optional<EmbeddedWorld> loadEmbedded(Path datapack) throws IOException {
        Path absolute = datapack.toAbsolutePath().normalize();
        if (Files.isDirectory(absolute)) return readEmbeddedDirectory(absolute);
        if (!Files.isRegularFile(absolute)) throw new IOException("Datapack does not exist: " + absolute);
        try (FileSystem zip = FileSystems.newFileSystem(absolute, Map.of())) { return readEmbeddedDirectory(zip.getPath("/")); }
    }

    private static Optional<EmbeddedWorld> readEmbeddedDirectory(Path root) throws IOException {
        boolean manifest = Files.exists(root.resolve(EMBEDDED_MANIFEST));
        boolean bindings = Files.exists(root.resolve(BINDINGS_PATH));
        if (!manifest && !bindings) return Optional.empty();
        if (!manifest || !bindings) throw new IOException("Incomplete embedded world content in datapack " + root);
        Path realRoot = root.toRealPath();
        return Optional.of(readEmbedded((path, limit) -> {
            Path file = root.resolve(path).normalize();
            if (!file.startsWith(root) || Files.isSymbolicLink(file) || !file.toRealPath().startsWith(realRoot) || !Files.isRegularFile(file))
                throw new IOException("Embedded content path escapes its datapack");
            if (Files.size(file) > limit) throw new IOException("Embedded content file exceeds byte budget");
            try (var stream = Files.newInputStream(file)) { return readBounded(stream, limit); }
        }));
    }

    private static EmbeddedWorld readEmbedded(RootReader reader) {
        WorldsmithPack loaded = WorldsmithPackLoader.INSTANCE.load(new WorldsmithPackSource() {
            @Override public String readText(String relative) { return new String(read(relative, WorldContentBundleIO.MAX_TEXT_BYTES), StandardCharsets.UTF_8); }
            @Override public byte[] readBytes(String relative) { return read(relative, DrawSnapshotCodec.MAX_BYTES); }
            private byte[] read(String relative, int limit) {
                if (!WorldContentRegistry.Companion.validRelativePath(relative)) throw new IllegalArgumentException("Invalid embedded bundle path");
                try { return reader.read(EMBEDDED_ROOT + relative, limit); }
                catch (IOException failure) { throw new UncheckedIOException(failure); }
            }
        });
        validate(loaded);
        try {
            var bindings = CustomBlockBindings.decode(new String(reader.read(BINDINGS_PATH, MAX_BINDINGS_BYTES), StandardCharsets.UTF_8));
            var expected = CustomBlockBindings.plan(loaded.getManifest().getId(), loaded.getBlocks(), bindings);
            if (!expected.equals(bindings)) throw new IllegalArgumentException("Embedded block mapping does not match the world content bundle");
            return new EmbeddedWorld(loaded, bindings);
        } catch (IOException failure) { throw new UncheckedIOException(failure); }
    }

    @FunctionalInterface private interface RootReader { byte[] read(String path, int limit) throws IOException; }
    private static byte[] readBounded(InputStream stream, int limit) throws IOException {
        byte[] bytes = stream.readNBytes(limit + 1);
        if (bytes.length > limit) throw new IOException("Embedded content file exceeds byte budget");
        return bytes;
    }
    private static Map<String, byte[]> freezeBytes(Map<String, byte[]> files) {
        Map<String, byte[]> copied = new LinkedHashMap<>(); files.forEach((path, bytes) -> copied.put(path, bytes.clone()));
        return Collections.unmodifiableMap(copied);
    }
    private static void putUnique(Map<String, byte[]> files, String path, byte[] bytes) {
        byte[] previous = files.putIfAbsent(path, bytes.clone());
        if (previous != null && !Arrays.equals(previous, bytes)) throw new IllegalArgumentException("Conflicting generated world content resource " + path);
    }
}

package com.wjz.worldsmith.client.content;

import com.wjz.worldsmith.content.GeneratedWorldResourcePack;
import com.wjz.worldsmith.mixin.client.PackRepositorySourcesAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.server.packs.PackLocationInfo;
import net.minecraft.server.packs.PackSelectionConfig;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.server.packs.repository.PackSource;
import net.minecraft.server.packs.repository.RepositorySource;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Transactional client-only resources. No files in resourcepacks, no persisted options changes.
 * Callers must await activation before publishing a world; never join these futures on the client thread.
 */
public final class WorldContentResources {
    private static final String PACK_ID = "worldsmith/world_content";
    private static volatile GeneratedWorldResourcePack active;
    private static final RepositorySource SOURCE = output -> {
        GeneratedWorldResourcePack snapshot = active;
        if (snapshot != null) {
            PackLocationInfo location = new PackLocationInfo(PACK_ID, Component.literal("Worldsmith: " + snapshot.scope()), PackSource.WORLD, Optional.empty());
            Pack pack = Pack.readMetaAndCreate(location, snapshot.supplier(), PackType.CLIENT_RESOURCES, new PackSelectionConfig(true, Pack.Position.TOP, true));
            if (pack == null) throw new IllegalStateException("Generated world resource metadata was rejected");
            output.accept(pack);
        }
    };
    private static long revision;
    private static boolean changing;

    private WorldContentResources() {}

    /** Pure staging: copies and hashes all bytes, with no client repository/global mutation. */
    public static Prepared prepare(String scope, Map<String, byte[]> resources) {
        GeneratedWorldResourcePack pack = new GeneratedWorldResourcePack(scope, resources);
        synchronized (WorldContentResources.class) { return new Prepared(pack, revision); }
    }

    public static String activeScope() { var snapshot = active; return snapshot == null ? null : snapshot.scope(); }

    public static CompletableFuture<Void> clear(String expectedScope) {
        CompletableFuture<Void> result = new CompletableFuture<>();
        Minecraft client = Minecraft.getInstance();
        client.execute(() -> {
            long expected;
            synchronized (WorldContentResources.class) {
                if (changing) { result.completeExceptionally(new IllegalStateException("A world resource reload is already in progress")); return; }
                if (active == null) { result.complete(null); return; }
                if (!active.scope().equals(expectedScope)) { result.completeExceptionally(new IllegalStateException("Resource clear belongs to another world")); return; }
                expected = revision;
            }
            change(client, null, expected, null).whenComplete((ignored, failure) -> complete(result, failure));
        });
        return result;
    }

    private static CompletableFuture<Long> change(Minecraft client, GeneratedWorldResourcePack target, long expected, Prepared owner) {
        CompletableFuture<Long> result = new CompletableFuture<>();
        GeneratedWorldResourcePack previous;
        long committed;
        synchronized (WorldContentResources.class) {
            if (changing || expected != revision) return CompletableFuture.failedFuture(new IllegalStateException("Stale or overlapping world resource activation"));
            if (target != null && active != null && !active.scope().equals(target.scope()) && client.level != null)
                return CompletableFuture.failedFuture(new IllegalStateException("Unload the active world before changing its resource scope"));
            changing = true;
            previous = active;
            active = target;
            committed = ++revision;
        }
        PackRepository repository = client.getResourcePackRepository();
        List<String> selectionBefore = new ArrayList<>(repository.getSelectedIds());
        try {
            configureRepository(repository, target != null, selectionBefore);
            client.reloadResourcePacks().whenCompleteAsync((ignored, failure) -> {
                Throwable rejected = failure;
                if (rejected == null) {
                    try { verifyLoaded(client, target); }
                    catch (Throwable verification) { rejected = verification; }
                }
                if (rejected == null) {
                    synchronized (WorldContentResources.class) {
                        changing = false;
                        if (owner != null) { owner.previous = previous; owner.committedRevision = committed; }
                    }
                    result.complete(committed);
                } else restoreAfterFailure(client, previous, selectionBefore, rejected, result);
            }, client);
        } catch (Throwable failure) { restoreAfterFailure(client, previous, selectionBefore, failure, result); }
        return result;
    }

    private static void restoreAfterFailure(Minecraft client, GeneratedWorldResourcePack previous, List<String> selectionBefore, Throwable failure, CompletableFuture<Long> result) {
        synchronized (WorldContentResources.class) { active = previous; revision++; }
        try {
            // Preserve unrelated packs selected since staging; recover originals if vanilla recovery cleared them.
            LinkedHashSet<String> recover = new LinkedHashSet<>(selectionBefore);
            recover.addAll(client.getResourcePackRepository().getSelectedIds());
            configureRepository(client.getResourcePackRepository(), previous != null, new ArrayList<>(recover));
            client.reloadResourcePacks().whenCompleteAsync((ignored, rollbackFailure) -> {
                if (rollbackFailure != null) failure.addSuppressed(rollbackFailure);
                else try { verifyLoaded(client, previous); } catch (Throwable verification) { failure.addSuppressed(verification); }
                synchronized (WorldContentResources.class) { changing = false; }
                result.completeExceptionally(failure);
            }, client);
        } catch (Throwable rollbackFailure) {
            failure.addSuppressed(rollbackFailure);
            synchronized (WorldContentResources.class) { changing = false; }
            result.completeExceptionally(failure);
        }
    }

    private static void configureRepository(PackRepository repository, boolean include, List<String> selected) {
        PackRepositorySourcesAccessor accessor = (PackRepositorySourcesAccessor) (Object) repository;
        Set<RepositorySource> sources = new LinkedHashSet<>(accessor.worldsmith$getSources());
        if (include) sources.add(SOURCE); else sources.remove(SOURCE);
        accessor.worldsmith$setSources(Set.copyOf(sources));
        repository.reload();
        List<String> updated = new ArrayList<>();
        for (String id : selected) if (!id.equals(PACK_ID) && repository.isAvailable(id)) updated.add(id);
        if (include) updated.add(PACK_ID);
        repository.setSelected(updated);
    }

    private static void verifyLoaded(Minecraft client, GeneratedWorldResourcePack target) throws IOException {
        var sentinel = client.getResourceManager().getResource(GeneratedWorldResourcePack.SENTINEL);
        if (target == null) {
            if (client.getResourcePackRepository().getSelectedIds().contains(PACK_ID) || sentinel.isPresent()) throw new IOException("Previous world resources remained loaded after clear");
            return;
        }
        if (!client.getResourcePackRepository().getSelectedIds().contains(PACK_ID) || sentinel.isEmpty()) throw new IOException("World resource pack was removed or rejected during reload");
        try (var stream = sentinel.get().open()) {
            if (!Arrays.equals(target.sentinelBytes(), stream.readNBytes(4097))) throw new IOException("Loaded world resources do not match prepared content hash");
        }
    }

    private static void complete(CompletableFuture<Void> result, Throwable failure) {
        if (failure == null) result.complete(null); else result.completeExceptionally(failure);
    }

    public static final class Prepared {
        private final GeneratedWorldResourcePack pack;
        private final long preparedRevision;
        private GeneratedWorldResourcePack previous;
        private long committedRevision = -1;
        private boolean consumed;
        private boolean rolledBack;

        private Prepared(GeneratedWorldResourcePack pack, long revision) { this.pack = pack; preparedRevision = revision; }
        public String scope() { return pack.scope(); }
        public String contentHash() { return pack.contentHash(); }
        public Map<String, String> hashes() { return pack.hashes(); }

        public CompletableFuture<Void> activate() {
            CompletableFuture<Void> result = new CompletableFuture<>();
            Minecraft client = Minecraft.getInstance();
            client.execute(() -> {
                synchronized (WorldContentResources.class) {
                    if (consumed) { result.completeExceptionally(new IllegalStateException("Resource preparation already consumed")); return; }
                    consumed = true;
                }
                change(client, pack, preparedRevision, this).whenComplete((ignored, failure) -> complete(result, failure));
            });
            return result;
        }

        public CompletableFuture<Void> rollback() {
            CompletableFuture<Void> result = new CompletableFuture<>();
            Minecraft client = Minecraft.getInstance();
            client.execute(() -> {
                synchronized (WorldContentResources.class) {
                    if (rolledBack) { result.complete(null); return; }
                    if (changing) { result.completeExceptionally(new IllegalStateException("Await resource activation before rollback")); return; }
                    if (committedRevision < 0) { consumed = true; rolledBack = true; result.complete(null); return; }
                    if (revision != committedRevision || active != pack) { result.completeExceptionally(new IllegalStateException("A later world resource activation owns this scope")); return; }
                }
                change(client, previous, committedRevision, null).whenComplete((ignored, failure) -> {
                    if (failure == null) synchronized (WorldContentResources.class) { rolledBack = true; }
                    complete(result, failure);
                });
            });
            return result;
        }
    }
}

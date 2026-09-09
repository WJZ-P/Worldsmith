package com.wjz.worldsmith.content;

import com.wjz.worldsmith.core.content.CustomBlockBinding;
import com.wjz.worldsmith.core.content.CustomBlockBindings;
import com.wjz.worldsmith.core.content.CustomBlockBindingSnapshot;
import com.wjz.worldsmith.core.content.CustomBlockLibrary;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** One active save across all dimensions. Authoring uses Prepared.resolve and never this global mapping. */
public final class WorldBlockBindings {
    private static CustomBlockBindingSnapshot active;
    private static long revision;
    private WorldBlockBindings() {}

    public static Prepared prepare(String scope, CustomBlockLibrary library, CustomBlockBindingSnapshot previous) {
        CustomBlockBindingSnapshot planned = CustomBlockBindings.plan(scope, library, previous);
        synchronized (WorldBlockBindings.class) { return new Prepared(planned, active, revision); }
    }

    public static synchronized CustomBlockBindingSnapshot active() { return active; }

    /** Compilation context has no activation token and never reads the active-world mapping. */
    public static Resolver resolver(CustomBlockBindingSnapshot snapshot) { return new Resolver(snapshot); }

    public static final class Resolver {
        private final CustomBlockBindingSnapshot snapshot;
        private final Map<String, CustomBlockBinding> logical;
        private Resolver(CustomBlockBindingSnapshot snapshot) {
            this.snapshot = CustomBlockBindings.decode(CustomBlockBindings.encode(snapshot));
            Map<String, CustomBlockBinding> bindings = new LinkedHashMap<>();
            for (CustomBlockBinding binding : this.snapshot.getBindings()) bindings.put(binding.logicalId(), binding);
            logical = Map.copyOf(bindings);
        }
        public CustomBlockBindingSnapshot snapshot() { return snapshot; }
        public Map<String, String> nativeIds() {
            Map<String, String> ids = new LinkedHashMap<>(); logical.forEach((id, binding) -> ids.put(id, binding.nativeId()));
            return Map.copyOf(ids);
        }
        public BlockState resolve(String identifier) {
            if (WorldsmithCustomBlocks.isReservedNativeId(identifier)) throw new IllegalArgumentException("Use a logical custom block alias, not a reserved native host: " + identifier);
            CustomBlockBinding binding = logical.get(identifier);
            if (binding == null) throw new IllegalArgumentException("Unknown or state-overridden custom block in world " + snapshot.getScope() + ": " + identifier);
            return WorldsmithCustomBlocks.host(binding.nativeId()).defaultBlockState().setValue(WorldsmithCustomBlocks.LIGHT, binding.getLight());
        }
        public Item resolveItem(String logicalId) {
            var block = resolve(logicalId).getBlock();
            Item item = block.asItem();
            if (!(item instanceof BlockItem blockItem) || blockItem.getBlock() != block)
                throw new IllegalStateException("Custom block has no registered matching BlockItem: " + logicalId);
            return item;
        }
    }

    public static synchronized void clear(String expectedScope) {
        if (active != null && !active.getScope().equals(expectedScope)) throw new IllegalStateException("Clearing another world's block bindings");
        active = null;
        revision++;
    }

    static synchronized Integer activeLight(String nativeId) {
        if (active == null) return null;
        return active.getBindings().stream().filter(binding -> binding.nativeId().equals(nativeId)).map(CustomBlockBinding::getLight).findFirst().orElse(null);
    }

    public static final class Prepared {
        private final CustomBlockBindingSnapshot snapshot;
        private final CustomBlockBindingSnapshot prior;
        private final long expectedRevision;
        private final Map<String, CustomBlockBinding> logical;
        private long commitRevision = -1;
        private boolean finished;

        private Prepared(CustomBlockBindingSnapshot snapshot, CustomBlockBindingSnapshot prior, long expectedRevision) {
            this.snapshot = snapshot;
            this.prior = prior;
            this.expectedRevision = expectedRevision;
            Map<String, CustomBlockBinding> bindings = new LinkedHashMap<>();
            for (CustomBlockBinding binding : snapshot.getBindings()) bindings.put(binding.logicalId(), binding);
            this.logical = Collections.unmodifiableMap(bindings);
        }

        public CustomBlockBindingSnapshot snapshot() { return snapshot; }
        public Map<String, String> nativeIds() {
            Map<String, String> ids = new LinkedHashMap<>();
            logical.forEach((id, binding) -> ids.put(id, binding.nativeId()));
            return Collections.unmodifiableMap(ids);
        }

        public BlockState resolve(String identifier) {
            CustomBlockBinding binding = logical.get(identifier);
            if (binding == null) throw new IllegalArgumentException("Unbound custom block in world " + snapshot.getScope() + ": " + identifier);
            return WorldsmithCustomBlocks.host(binding.nativeId()).defaultBlockState().setValue(WorldsmithCustomBlocks.LIGHT, binding.getLight());
        }

        public void commit() {
            commitInternal(false, prior);
        }

        /** Explicit world-switch transaction. Caller must first stop the previous server. */
        public void commitForNewWorld(CustomBlockBindingSnapshot expectedPreviousSnapshot) {
            commitInternal(true, expectedPreviousSnapshot);
        }

        public CustomBlockBindingSnapshot previousSnapshot() { return prior; }

        private void commitInternal(boolean switchingWorld, CustomBlockBindingSnapshot expectedPreviousSnapshot) {
            synchronized (WorldBlockBindings.class) {
                if (finished || commitRevision >= 0) throw new IllegalStateException("Block binding preparation has already been consumed");
                if (revision != expectedRevision) throw new IllegalStateException("Stale block binding preparation; prepare again");
                if (active != expectedPreviousSnapshot || prior != expectedPreviousSnapshot) throw new IllegalStateException("The expected prior block snapshot no longer owns the runtime");
                boolean sameScope = active != null && active.getScope().equals(snapshot.getScope());
                if (active != null && !sameScope && !switchingWorld) throw new IllegalStateException("Use an explicit stopped-server world-switch transaction for another block mapping");
                if (sameScope) {
                    // A same-scope activation cannot replace the identity of already saved native states.
                    for (CustomBlockBinding binding : active.getBindings()) {
                        if (!Objects.equals(logical.get(binding.logicalId()), binding)) throw new IllegalStateException("Live block binding mutation requires a save migration");
                    }
                }
                active = snapshot;
                commitRevision = ++revision;
            }
        }

        public void rollback() {
            synchronized (WorldBlockBindings.class) {
                if (finished) return;
                if (commitRevision >= 0) {
                    if (revision != commitRevision || active != snapshot) throw new IllegalStateException("A later activation owns the block mapping; rollback rejected");
                    active = prior;
                    revision++;
                }
                finished = true;
            }
        }
    }
}

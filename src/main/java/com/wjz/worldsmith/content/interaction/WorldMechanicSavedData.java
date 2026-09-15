package com.wjz.worldsmith.content.interaction;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.DynamicOps;
import com.mojang.datafixers.util.Pair;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.wjz.worldsmith.core.content.WorldMechanicDefinition;
import com.wjz.worldsmith.core.content.WorldMechanicRule;
import com.wjz.worldsmith.core.content.WorldMechanicValidation;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import net.minecraft.resources.Identifier;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import net.minecraft.world.level.storage.SavedDataStorage;

/** One non-ticking ledger per dimension. Breaking/replacing an anchor never resets its spent state. */
public final class WorldMechanicSavedData extends SavedData {
    public static final int MAX_INSTANCES = 16384;
    private static final Codec<String> LOCAL_ID = Codec.STRING.validate(id -> WorldMechanicValidation.validId(id)
        ? DataResult.success(id) : DataResult.error(() -> "Invalid mechanic state id"));
    private static final Codec<Long> NONNEGATIVE = Codec.LONG.validate(value -> value >= 0
        ? DataResult.success(value) : DataResult.error(() -> "Negative mechanic counter"));

    public record Progress(String state, long readyAt, long activations) {
        public static final Codec<Progress> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            LOCAL_ID.fieldOf("state").forGetter(Progress::state),
            NONNEGATIVE.fieldOf("readyAt").forGetter(Progress::readyAt),
            NONNEGATIVE.fieldOf("activations").forGetter(Progress::activations)
        ).apply(instance, Progress::new));
        public Progress {
            if (!WorldMechanicValidation.validId(state) || readyAt < 0 || activations < 1)
                throw new IllegalArgumentException("Invalid stored mechanic progress");
        }
    }

    public record State(int schemaVersion, String scope, long revision, Map<String, Progress> instances, boolean quarantined) {
        private static final Codec<String> KEY = Codec.STRING.validate(key -> validKey(key)
            ? DataResult.success(key) : DataResult.error(() -> "Invalid mechanic instance key"));
        public static final Codec<State> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.intRange(1, 1).fieldOf("schemaVersion").forGetter(State::schemaVersion),
            Codec.STRING.fieldOf("scope").forGetter(State::scope),
            NONNEGATIVE.fieldOf("revision").forGetter(State::revision),
            Codec.unboundedMap(KEY, Progress.CODEC).validate(entries -> entries.size() <= MAX_INSTANCES
                ? DataResult.success(entries) : DataResult.error(() -> "Mechanic instance budget exceeded"))
                .fieldOf("instances").forGetter(State::instances),
            Codec.BOOL.fieldOf("quarantined").forGetter(State::quarantined)
        ).apply(instance, State::new));
        public State(int schemaVersion, String scope, long revision, Map<String, Progress> instances) {
            this(schemaVersion, scope, revision, instances, false);
        }
        public State {
            if (schemaVersion != 1 || scope == null || !(scope.matches("[0-9a-f]{64}") || scope.isEmpty())
                || revision < 0 || instances == null || instances.size() > MAX_INSTANCES
                || scope.isEmpty() && (!instances.isEmpty() || revision != 0 || quarantined))
                throw new IllegalArgumentException("Invalid mechanic ledger header");
            instances.forEach((key, value) -> {
                if (!validKey(key) || value == null) throw new IllegalArgumentException("Invalid mechanic ledger entry");
            });
            instances = Collections.unmodifiableMap(new LinkedHashMap<>(instances));
        }
    }

    private static final Codec<WorldMechanicSavedData> STATE_CODEC = State.CODEC.xmap(WorldMechanicSavedData::new, WorldMechanicSavedData::state);
    /** Native storage accepts partial decode results; a one-shot ledger must be all-or-nothing. */
    public static final Codec<WorldMechanicSavedData> CODEC = new Codec<>() {
        @Override public <T> DataResult<Pair<WorldMechanicSavedData, T>> decode(DynamicOps<T> ops, T input) {
            try {
                var decoded = STATE_CODEC.decode(ops, input);
                if (decoded.error().isPresent()) return DataResult.error(() -> decoded.error().orElseThrow().message());
                return decoded;
            } catch (IllegalArgumentException malformed) {
                return DataResult.error(() -> "Invalid mechanic history: " + malformed.getMessage());
            }
        }
        @Override public <T> DataResult<T> encode(WorldMechanicSavedData value, DynamicOps<T> ops, T prefix) {
            return STATE_CODEC.encode(value, ops, prefix);
        }
    };
    public static final SavedDataType<WorldMechanicSavedData> TYPE = new SavedDataType<>(
        Identifier.fromNamespaceAndPath("worldsmith", "mechanic_instances"), WorldMechanicSavedData::new, CODEC,
        DataFixTypes.SAVED_DATA_COMMAND_STORAGE);
    private State state;

    public WorldMechanicSavedData() { this(new State(1, "", 0, Map.of())); }
    public WorldMechanicSavedData(State state) { this.state = state; }
    public State state() { return state; }

    /** Preserve existing corrupt/unknown history instead of accepting native storage's missing fallback. */
    static WorldMechanicSavedData load(SavedDataStorage storage, Path dataRoot, String scope,
                                      Map<String, WorldMechanicDefinition> definitions) {
        Path path = TYPE.id().withSuffix(".dat").resolveAgainst(dataRoot);
        boolean definitelyAbsent = Files.notExists(path, LinkOption.NOFOLLOW_LINKS);
        WorldMechanicSavedData ledger = storage.get(TYPE);
        if (ledger == null) {
            if (!definitelyAbsent) throw new IllegalStateException("Existing mechanic history failed to load; original ledger is preserved: " + path);
            ledger = new WorldMechanicSavedData();
            ledger.bind(scope, definitions);
            storage.set(TYPE, ledger);
        } else ledger.bind(scope, definitions);
        return ledger;
    }

    public void bind(String scope, Map<String, WorldMechanicDefinition> definitions) {
        if (scope == null || !scope.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Invalid mechanic world scope");
        if (state.quarantined) throw new IllegalStateException("Mechanic ledger is quarantined after an incomplete rollback; inspect the recorded failure before restoring interactions");
        if (!state.scope.isEmpty() && !state.scope.equals(scope)) throw new IllegalStateException("Mechanic history belongs to another world bundle");
        for (var entry : state.instances.entrySet()) {
            var definition = definitions.get(mechanicId(entry.getKey()));
            if (definition == null || !definition.getStates().contains(entry.getValue().state))
                throw new IllegalStateException("Stored mechanic instance has no matching definition/state");
        }
        if (state.scope.isEmpty()) { state = new State(1, scope, 0, Map.of()); setDirty(); }
    }

    public Progress progress(String key) { return state.instances.get(key); }

    public boolean eligible(String key, WorldMechanicDefinition definition, WorldMechanicRule rule, long now) {
        Progress current = progress(key);
        return !state.quarantined && now >= 0 && (current == null ? definition.getInitialState() : current.state).equals(rule.getFromState())
            && (current == null || now >= current.readyAt)
            && (current != null || state.instances.size() < MAX_INSTANCES);
    }

    /** A failed compensation must never make a half-applied reward available for immediate replay. */
    public void quarantine() {
        state = new State(1, state.scope, state.revision, state.instances, true);
        setDirty();
    }

    /** Allocate/validate the complete replacement before any world or inventory changes. */
    public Update plan(String key, WorldMechanicDefinition definition, WorldMechanicRule rule, long now) {
        if (state.scope.isEmpty() || !validKey(key) || !mechanicId(key).equals(definition.getId())
            || !definition.getRules().contains(rule) || !eligible(key, definition, rule, now))
            throw new IllegalStateException("Mechanic transition is no longer eligible");
        Progress old = progress(key);
        var next = new LinkedHashMap<>(state.instances);
        next.put(key, new Progress(rule.getToState(), Math.addExact(now, rule.getCooldownTicks()),
            old == null ? 1 : Math.incrementExact(old.activations)));
        return new Update(this, state, new State(1, state.scope, Math.incrementExact(state.revision), next));
    }

    public static String key(String mechanic, long anchor) {
        if (!WorldMechanicValidation.validId(mechanic)) throw new IllegalArgumentException("Invalid mechanic id");
        return mechanic + "@" + anchor;
    }
    private static String mechanicId(String key) { return key.substring(0, key.indexOf('@')); }
    private static boolean validKey(String key) {
        if (key == null || key.length() > 86) return false;
        int at = key.indexOf('@');
        if (at < 1 || !WorldMechanicValidation.validId(key.substring(0, at))) return false;
        try { return Long.toString(Long.parseLong(key.substring(at + 1))).equals(key.substring(at + 1)); }
        catch (NumberFormatException ignored) { return false; }
    }

    public static final class Update {
        private final WorldMechanicSavedData owner;
        private final State before, after;
        private boolean committed;
        private Update(WorldMechanicSavedData owner, State before, State after) { this.owner = owner; this.before = before; this.after = after; }
        public void assertUnchanged() {
            if (committed || owner.state != before) throw new IllegalStateException("Mechanic ledger changed during action planning");
        }
        public void commit() { assertUnchanged(); owner.state = after; owner.setDirty(); committed = true; }
    }
}

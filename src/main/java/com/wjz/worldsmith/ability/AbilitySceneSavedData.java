package com.wjz.worldsmith.ability;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.wjz.worldsmith.core.ability.AbilityValue;
import java.nio.file.*;
import java.util.*;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.saveddata.*;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.storage.LevelResource;

/** One bounded shared-state ledger per dimension; empty scenes are removed, never evict spent state. */
final class AbilitySceneSavedData extends SavedData {
    record State(String scope, Map<String,AbilityData.Values> scenes) {
        static final Codec<State> CODEC = AbilityData.strict(RecordCodecBuilder.create(i -> i.group(
            Codec.STRING.fieldOf("scope").forGetter(State::scope), Codec.unboundedMap(Codec.STRING,AbilityData.Values.CODEC).fieldOf("scenes").forGetter(State::scenes)
        ).apply(i,State::new)));
        State {
            AbilityData.scope(scope);
            if (scenes.size() > 2048) throw new IllegalArgumentException("Scene count exceeds 2048");
            int bytes = 0;
            for (var entry : scenes.entrySet()) {
                if (!validKey(entry.getKey())) throw new IllegalArgumentException("Invalid scene key");
                bytes = Math.addExact(bytes,entry.getValue().bytes()+entry.getKey().length());
            }
            if (bytes > 2*1024*1024) throw new IllegalArgumentException("Scene state exceeds 2 MiB");
            scenes = Collections.unmodifiableMap(new LinkedHashMap<>(scenes));
        }
    }
    static final SavedDataType<AbilitySceneSavedData> TYPE = new SavedDataType<>(Identifier.fromNamespaceAndPath("worldsmith","ability_scenes"),
        () -> { throw new IllegalStateException("Ability scenes require explicit scoped creation"); },
        State.CODEC.xmap(AbilitySceneSavedData::new, s -> s.state),DataFixTypes.SAVED_DATA_COMMAND_STORAGE);
    private State state;
    AbilitySceneSavedData(State state) { this.state = state; }
    static boolean validKey(String key) {
        if (key == null || key.length() > 86) return false;
        int at = key.indexOf('@'); if (at <= 0) return false;
        try { AbilityData.id(key.substring(0,at)); return Long.toString(Long.parseLong(key.substring(at+1))).equals(key.substring(at+1)); }
        catch (RuntimeException ignored) { return false; }
    }
    static AbilitySceneSavedData load(ServerLevel level,String scope) {
        var root = DimensionType.getStorageFolder(level.dimension(),level.getServer().getWorldPath(LevelResource.ROOT)).resolve("data");
        var path = TYPE.id().withSuffix(".dat").resolveAgainst(root);
        boolean absent = Files.notExists(path,LinkOption.NOFOLLOW_LINKS);
        var data = level.getDataStorage().get(TYPE);
        if (data == null) {
            if (!absent) throw new IllegalStateException("Existing ability scene data failed to load: " + path);
            data = new AbilitySceneSavedData(new State(scope,Map.of())); level.getDataStorage().set(TYPE,data);
        }
        if (!data.state.scope.equals(scope)) throw new IllegalStateException("Scene data belongs to another world bundle");
        return data;
    }
    AbilityValue get(String scene,String key) { return state.scenes.getOrDefault(scene,AbilityData.Values.EMPTY).get(key); }
    boolean put(String scene,String key,AbilityValue value) {
        if (!validKey(scene)) throw new IllegalArgumentException("Invalid scene key");
        var old = state.scenes.getOrDefault(scene,AbilityData.Values.EMPTY); var next = old.with(key,value);
        if (old == next) return true;
        var updated = new LinkedHashMap<>(state.scenes);
        if (next.empty()) updated.remove(scene); else updated.put(scene,next);
        state = new State(state.scope,updated); setDirty(); return true;
    }
}

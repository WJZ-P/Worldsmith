package com.wjz.worldsmith.ability;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.nio.file.*;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.saveddata.*;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.CollisionContext;

/** Persistent compensation journal; reads loaded chunks only and never overwrites a differing later edit. */
final class AbilityWorldSavedData extends SavedData {
    record Edit(String token,String owner,long until,BlockPos position,BlockState before,BlockState after) {
        static final Codec<Edit> CODEC = AbilityData.strict(RecordCodecBuilder.create(i -> i.group(
            Codec.STRING.fieldOf("token").forGetter(Edit::token), Codec.STRING.fieldOf("owner").forGetter(Edit::owner),
            Codec.LONG.fieldOf("until").forGetter(Edit::until), BlockPos.CODEC.fieldOf("position").forGetter(Edit::position),
            BlockState.CODEC.fieldOf("before").forGetter(Edit::before), BlockState.CODEC.fieldOf("after").forGetter(Edit::after)
        ).apply(i,Edit::new)));
        Edit {
            if (!UUID.fromString(token).toString().equals(token) || !UUID.fromString(owner).toString().equals(owner) || until < 0
                || before.hasBlockEntity() || after.hasBlockEntity()) throw new IllegalArgumentException("Invalid temporary world edit");
            position=position.immutable();
        }
        Edit retired() { return new Edit(token,owner,0,position,before,after); }
    }
    record State(String scope,Map<String,Edit> edits) {
        static final Codec<State> CODEC = AbilityData.strict(RecordCodecBuilder.create(i -> i.group(
            Codec.STRING.fieldOf("scope").forGetter(State::scope), Codec.unboundedMap(Codec.STRING,Edit.CODEC).fieldOf("edits").forGetter(State::edits)
        ).apply(i,State::new)));
        State {
            AbilityData.scope(scope); if (edits.size()>4096) throw new IllegalArgumentException("Pending temporary edits exceed 4096");
            Set<BlockPos> positions=new HashSet<>();
            edits.forEach((key,edit) -> { if (!key.equals(edit.token) || !positions.add(edit.position)) throw new IllegalArgumentException("Duplicate or invalid journal entry"); });
            edits=Collections.unmodifiableMap(new LinkedHashMap<>(edits));
        }
    }
    static final SavedDataType<AbilityWorldSavedData> TYPE = new SavedDataType<>(Identifier.fromNamespaceAndPath("worldsmith","ability_world_edits"),
        () -> { throw new IllegalStateException("World edit journal requires scoped creation"); }, State.CODEC.xmap(AbilityWorldSavedData::new,d -> d.state),DataFixTypes.SAVED_DATA_COMMAND_STORAGE);
    private State state;
    AbilityWorldSavedData(State state) { this.state=state; }
    static AbilityWorldSavedData load(ServerLevel level,String scope) {
        var root=DimensionType.getStorageFolder(level.dimension(),level.getServer().getWorldPath(LevelResource.ROOT)).resolve("data");
        var path=TYPE.id().withSuffix(".dat").resolveAgainst(root); boolean absent=Files.notExists(path,LinkOption.NOFOLLOW_LINKS);
        var data=level.getDataStorage().get(TYPE);
        if(data==null) {
            if(!absent) throw new IllegalStateException("Existing world edit journal failed to load: "+path);
            data=new AbilityWorldSavedData(new State(scope,Map.of())); level.getDataStorage().set(TYPE,data);
        }
        if(!data.state.scope.equals(scope)) {
            if(!data.state.edits.isEmpty()) throw new IllegalStateException("Pending world edits belong to another bundle");
            data.state=new State(scope,Map.of()); data.setDirty(); // An empty journal holds no foreign restoration obligations.
        }
        return data;
    }
    boolean pending(BlockPos pos) { return state.edits.values().stream().anyMatch(e -> e.position.equals(pos)); }
    void add(Edit edit) {
        var next=new LinkedHashMap<>(state.edits); if(next.putIfAbsent(edit.token,edit)!=null) throw new IllegalArgumentException("Duplicate world lease");
        state=new State(state.scope,next); setDirty();
    }
    void restore(ServerLevel level,String token) {
        Edit edit=state.edits.get(token); if(edit==null) return;
        var next=new LinkedHashMap<>(state.edits); next.put(token,edit.retired()); state=new State(state.scope,next); setDirty();
        attempt(level,edit.retired());
    }
    void tick(ServerLevel level) {
        int examined=0,mutations=0;
        for(Edit edit:List.copyOf(state.edits.values())) {
            int before=state.edits.size();
            if(edit.until<=level.getGameTime() || !WorldAbilityRuntime.invocationActive(level,UUID.fromString(edit.owner))) attempt(level,edit);
            if(state.edits.size()<before && ++mutations>=128) break;
            // The journal is capped at 4096. All records are inspected; at most 128 loaded writes per tick.
            if(++examined>=4096) break;
        }
    }
    private void attempt(ServerLevel level,Edit edit) {
        BlockPos pos=edit.position;
        if(!WorldAbilityRuntime.loaded(level,new AABB(pos).inflate(1))) return;
        BlockState current=level.getBlockState(pos);
        if(current==edit.after) {
            var chunk=level.getChunkSource().getChunkNow(pos.getX()>>4,pos.getZ()>>4);
            if(chunk==null || chunk.getBlockEntities().containsKey(pos) || chunk.getBlockEntityNbt(pos)!=null) return;
            if(!level.isUnobstructed(null,edit.before.getCollisionShape(level,pos,CollisionContext.empty()).move(pos))) return;
            if(!level.setBlock(pos,edit.before,Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE) && level.getBlockState(pos)!=edit.before) return;
        }
        var next=new LinkedHashMap<>(state.edits); next.remove(edit.token); state=new State(state.scope,next); setDirty();
    }
}

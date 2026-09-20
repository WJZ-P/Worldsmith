package com.wjz.worldsmith.content.story;

import com.wjz.worldsmith.Worldsmith;
import com.wjz.worldsmith.content.WorldBlockBindings;
import com.wjz.worldsmith.content.WorldsmithCustomBlocks;
import com.wjz.worldsmith.core.story.*;
import java.util.*;
import java.util.function.IntConsumer;
import java.util.function.IntPredicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.shapes.CollisionContext;

/**
 * Durable facts project into real, loaded place instances without borrowing a player's lifetime.
 * A receipt is final: later player edits are never repaired behind the player's back.
 * Missing receipts permit expected/desired mixtures, including a partially saved interrupted batch.
 * This is not a cross-file filesystem transaction between SavedData and native chunk storage.
 */
public final class StoryProjections {
    private static final Rotation[] ROTATIONS={Rotation.NONE,Rotation.CLOCKWISE_90,Rotation.CLOCKWISE_180,Rotation.COUNTERCLOCKWISE_90};
    private static final int PAIRS_PER_TICK=64,ATTEMPTS_PER_TICK=2,FLAGS=Block.UPDATE_SKIP_ALL_SIDEEFFECTS;
    /** Native construction blocks with no placement/survival/tick state machine; logical hosts share this contract. */
    private static final Set<Class<?>> STATIC_CONSTRUCTION=Set.of(Block.class,RotatedPillarBlock.class,AmethystBlock.class,
        HalfTransparentBlock.class,TransparentBlock.class,StainedGlassBlock.class,TintedGlassBlock.class);
    public enum Status { WAITING, UNLOADED, CONFLICT, PROTECTED, OBSTRUCTED, READY, APPLIED, ALREADY_APPLIED, INVALID }
    public record Report(Status status,UUID place,String projection,BlockPos position,String detail) {}
    record Change(BlockPos offset,BlockState expected,BlockState desired) {}
    record Prepared(StoryProjection definition,List<Change> blocks) {}
    private record Work(StorySavedData.Place place,Prepared projection) {}
    static final class Schedule {
        int placeCount=-1,cursor;
        List<Work> work=List.of();
        final Map<StorySavedData.ProjectionReceipt,Status> lastStatus=new HashMap<>();
    }
    private record Plan(WorldStoryRuntime.PreparedChanges facts,Map<BlockPos,BlockState> before,Map<BlockPos,BlockState> after) {}
    private static final class Deferred extends RuntimeException {
        final Status status;final BlockPos position;
        Deferred(Status status,BlockPos position,String detail){super(detail);this.status=status;this.position=position;}
    }
    private StoryProjections() {}

    static Prepared prepare(StoryProjection definition,WorldBlockBindings.Resolver blocks) {
        var changes=new ArrayList<Change>();
        for(var change:definition.getBlocks()) {
            var before=resolve(change.getExpected(),blocks);var after=resolve(change.getDesired(),blocks);
            if(before==after)throw new IllegalArgumentException("Projection before/after resolve to the same exact native state");
            ordinary(before);ordinary(after);stableDesired(after);var offset=change.getOffset();
            changes.add(new Change(new BlockPos(offset.getX(),offset.getY(),offset.getZ()),before,after));
        }
        return new Prepared(definition,List.copyOf(changes));
    }
    static BlockState resolve(StoryBlockState value,WorldBlockBindings.Resolver blocks) {
        String reference=value.getBlock();BlockState state;
        if(WorldsmithCustomBlocks.isReservedNativeId(reference))throw new IllegalArgumentException("Story projections use logical aliases, not native hosts");
        if(reference.startsWith("worldsmith:content/")) {
            return blocks.resolve(reference,value.getProperties());
        } else state=BuiltInRegistries.BLOCK.getOptional(Identifier.parse(reference)).orElseThrow(() -> new IllegalArgumentException("Unknown projection block: "+reference)).defaultBlockState();
        for(var entry:value.getProperties().entrySet()) {
            var property=state.getBlock().getStateDefinition().getProperty(entry.getKey());
            if(property==null)throw new IllegalArgumentException("Unknown projection block property: "+reference+"."+entry.getKey());
            state=property(state,property,entry.getValue());
        }
        return state;
    }
    private static <T extends Comparable<T>> BlockState property(BlockState state,Property<T> property,String value) {
        return state.setValue(property,property.getValue(value).orElseThrow(() -> new IllegalArgumentException("Invalid projection property value: "+property.getName()+"="+value)));
    }
    private static void ordinary(BlockState state) {
        if(state.hasBlockEntity()||!state.getFluidState().isEmpty()||state.getBlock() instanceof FallingBlock)
            throw new IllegalArgumentException("Story projections require data-free, dry, non-falling block states");
    }
    private static void stableDesired(BlockState state) {
        if(state.isAir())return;
        var block=state.getBlock();var id=BuiltInRegistries.BLOCK.getKey(block).toString();
        if(block.hasDynamicShape()||state.isRandomlyTicking()||!state.isCollisionShapeFullBlock(EmptyBlockGetter.INSTANCE,BlockPos.ZERO)
            ||!STATIC_CONSTRUCTION.contains(block.getClass())&&!WorldsmithCustomBlocks.isReservedNativeId(id))
            throw new IllegalArgumentException("Projection desired states require air or static full-cell construction blocks; support/placement/tick state machines belong to ordinary gameplay");
    }
    static BlockPos position(StorySavedData.Place place,BlockPos offset){return offset.rotate(ROTATIONS[place.quarterTurns()]).offset(place.position());}
    private static StorySavedData.ProjectionReceipt receipt(Work work){return new StorySavedData.ProjectionReceipt(work.place.instance(),work.projection.definition.getId());}
    private static WorldStoryRuntime.Context context(WorldStoryRuntime.Bound bound,Work work){return new WorldStoryRuntime.Context(bound,null,null,null,work.place);}

    static void tick(MinecraftServer server) {
        for(var bound:WorldStoryRuntime.WORLDS.values())if(bound.level.getServer()==server&&!bound.snapshot.projections.isEmpty()) {
            var schedule=bound.projectionSchedule;var places=bound.store().state().places();
            if(schedule.placeCount!=places.size()) {
                var work=new ArrayList<Work>();
                for(var place:places.values())if(place.dimension().equals(bound.level.dimension().identifier().toString()))
                    for(var projection:bound.snapshot.projections.values())if(projection.definition.getPlace().equals(place.definition()))work.add(new Work(place,projection));
                // Actual place registration is append-only. Keep the existing ring position when new markers arrive.
                schedule.work=List.copyOf(work);schedule.placeCount=places.size();schedule.cursor=work.isEmpty()?0:Math.min(schedule.cursor,work.size()-1);
            }
            schedule.cursor=scan(schedule.work.size(),schedule.cursor,index -> {
                var work=schedule.work.get(index);
                return !bound.store().state().projections().contains(receipt(work))&&loadedCandidate(bound.level,work)
                    &&WorldStoryRuntime.test(context(bound,work),work.projection.definition.getCondition());
            },index -> {
                var work=schedule.work.get(index);var report=apply(bound,work);
                var old=schedule.lastStatus.put(receipt(work),report.status);
                if(report.status!=old&&Set.of(Status.CONFLICT,Status.PROTECTED,Status.INVALID).contains(report.status))
                    Worldsmith.LOGGER.warn("Story projection {} at actual place {} deferred: {} at {} ({})",report.projection,report.place,report.status,report.position,report.detail);
            });
        }
    }
    /** Fair cursor: skipped/unloaded pairs cost a scan, never one of the two expensive loaded attempts. */
    static int scan(int size,int cursor,IntPredicate eligible,IntConsumer attempt) {
        if(size==0)return 0;
        if(size<0||cursor<0||cursor>=size)throw new IllegalArgumentException("Invalid projection schedule cursor");
        int attempts=0;
        for(int scanned=0;scanned<Math.min(PAIRS_PER_TICK,size)&&attempts<ATTEMPTS_PER_TICK;scanned++) {
            int index=cursor;cursor=(cursor+1)%size;
            if(eligible.test(index)){attempts++;attempt.accept(index);}
        }
        return cursor;
    }
    private static boolean loadedCandidate(ServerLevel level,Work work) {
        var marker=work.place.position();
        if(level.getChunkSource().getChunkNow(marker.getX()>>4,marker.getZ()>>4)==null)return false;
        for(var change:work.projection.blocks) {
            var pos=position(work.place,change.offset);
            for(int x=(pos.getX()-1)>>4;x<=(pos.getX()+1)>>4;x++)for(int z=(pos.getZ()-1)>>4;z<=(pos.getZ()+1)>>4;z++)
                if(level.getChunkSource().getChunkNow(x,z)==null)return false;
        }
        return true;
    }
    /** Read-only current evidence for an actual registered place; never starts an invocation or loads a chunk. */
    public static Report inspect(ServerLevel level,UUID place,String projection) {
        WorldStoryRuntime.requireThread(level);var bound=WorldStoryRuntime.WORLDS.get(level);
        var actual=bound==null?null:bound.store().state().places().get(place);var prepared=bound==null?null:bound.snapshot.projections.get(projection);
        if(actual==null||prepared==null||!actual.dimension().equals(level.dimension().identifier().toString())||!actual.definition().equals(prepared.definition.getPlace()))
            return new Report(Status.INVALID,place,projection,null,"Projection requires a matching actual place in this level");
        var work=new Work(actual,prepared);
        try {preflight(bound,work);return report(work,Status.READY,null,"");}
        catch(Deferred reason){return report(work,reason.status,reason.position,reason.getMessage());}
        catch(RuntimeException failure){return report(work,Status.INVALID,null,failure.getMessage());}
    }
    private static Report report(Work work,Status status,BlockPos position,String detail){return new Report(status,work.place.instance(),work.projection.definition.getId(),position,detail);}
    private static Plan preflight(WorldStoryRuntime.Bound bound,Work work) {
        if(bound.store().state().projections().contains(receipt(work)))throw new Deferred(Status.ALREADY_APPLIED,null,"Receipt is final; later edits are retained");
        var context=context(bound,work);
        if(!WorldStoryRuntime.test(context,work.projection.definition.getCondition()))throw new Deferred(Status.WAITING,null,"Source facts are not satisfied");
        var level=bound.level;var before=new LinkedHashMap<BlockPos,BlockState>();var after=new LinkedHashMap<BlockPos,BlockState>();
        for(var change:work.projection.blocks) {
            var pos=position(work.place,change.offset);var expected=change.expected.rotate(ROTATIONS[work.place.quarterTurns()]);var desired=change.desired.rotate(ROTATIONS[work.place.quarterTurns()]);
            if(!level.isInValidBounds(pos)||!level.getWorldBorder().isWithinBounds(pos))throw new Deferred(Status.PROTECTED,pos,"Outside native bounds or world border");
            var chunk=level.getChunkSource().getChunkNow(pos.getX()>>4,pos.getZ()>>4);
            if(chunk==null)throw new Deferred(Status.UNLOADED,pos,"Target chunk is not loaded");
            var actual=chunk.getBlockState(pos);
            if(actual!=expected&&actual!=desired)throw new Deferred(Status.CONFLICT,pos,"Existing state is neither the expected nor desired exact state");
            if(actual.hasBlockEntity()||desired.hasBlockEntity()||!actual.getFluidState().isEmpty()||!desired.getFluidState().isEmpty()
                ||actual.getDestroySpeed(level,pos)<0||desired.getDestroySpeed(level,pos)<0||chunk.getBlockEntities().containsKey(pos)||chunk.getBlockEntityNbt(pos)!=null)
                throw new Deferred(Status.PROTECTED,pos,"Data-bearing, fluid or indestructible target is protected");
            before.put(pos,actual);after.put(pos,desired);
        }
        var proposed=new Overlay(level,after);
        for(var entry:after.entrySet()) {
            var pos=entry.getKey();
            // Include neighbouring chunks before shape evaluation; shape reads also use the loaded-only overlay.
            for(var neighbour:BlockPos.betweenClosed(pos.offset(-1,-1,-1),pos.offset(1,1,1)))proposed.getBlockState(neighbour);
            if(!level.isUnobstructed(null,entry.getValue().getCollisionShape(proposed,pos,CollisionContext.empty()).move(pos)))
                throw new Deferred(Status.OBSTRUCTED,pos,"An entity occupies the desired block volume");
        }
        var facts=WorldStoryRuntime.prepareChanges(context,work.projection.definition.getOnApplied());facts.projection(receipt(work));facts.assertUnchanged();
        return new Plan(facts,before,after);
    }
    private static Report apply(WorldStoryRuntime.Bound bound,Work work) {
        Plan plan;
        try {plan=preflight(bound,work);}
        catch(Deferred reason){return report(work,reason.status,reason.position,reason.getMessage());}
        catch(RuntimeException failure){return report(work,Status.INVALID,null,failure.getMessage());}
        var level=bound.level;var changed=new ArrayList<BlockPos>();
        try {
            plan.facts.assertUnchanged();
            for(var entry:plan.before.entrySet())if(level.getBlockState(entry.getKey())!=entry.getValue())throw new IllegalStateException("Projection preflight became stale");
            for(var entry:plan.after.entrySet()) {
                if(plan.before.get(entry.getKey())==entry.getValue())continue;
                changed.add(entry.getKey());
                if(!level.setBlock(entry.getKey(),entry.getValue(),FLAGS)||level.getBlockState(entry.getKey())!=entry.getValue())throw new IllegalStateException("Projection block application failed");
            }
            plan.facts.commit();
        } catch(RuntimeException failure) {
            Collections.reverse(changed);
            for(var pos:changed)try {
                // A third state from an external observer is retained, not overwritten during compensation.
                var current=level.getBlockState(pos);
                if(current==plan.after.get(pos)) {
                    level.setBlock(pos,plan.before.get(pos),FLAGS|Block.UPDATE_CLIENTS);
                    if(level.getBlockState(pos)!=plan.before.get(pos))throw new IllegalStateException("Projection compensation failed");
                }
            } catch(RuntimeException rollback){failure.addSuppressed(rollback);}
            Worldsmith.LOGGER.error("Story projection {} failed before receipt; expected/desired states remain recoverable",work.projection.definition.getId(),failure);
            return report(work,Status.INVALID,null,failure.getMessage());
        }
        try {
            for(var pos:changed) {
                var old=plan.before.get(pos);var current=level.getBlockState(pos);
                level.sendBlockUpdated(pos,old,current,Block.UPDATE_ALL);
                current.onPlace(level,pos,old,false);level.updateNeighboursOnBlockSet(pos,old);
                old.updateIndirectNeighbourShapes(level,pos,Block.UPDATE_CLIENTS,64);
                current.updateNeighbourShapes(level,pos,Block.UPDATE_CLIENTS,64);current.updateIndirectNeighbourShapes(level,pos,Block.UPDATE_CLIENTS,64);
            }
        } catch(RuntimeException observer){Worldsmith.LOGGER.warn("Story projection receipt committed; native observer failed",observer);}
        return report(work,Status.APPLIED,null,"");
    }
    private static final class Overlay implements BlockGetter {
        final ServerLevel level;final Map<BlockPos,BlockState> writes;final Map<BlockPos,BlockState> reads=new HashMap<>();
        Overlay(ServerLevel level,Map<BlockPos,BlockState> writes){this.level=level;this.writes=writes;}
        @Override public BlockState getBlockState(BlockPos pos) {
            if(!level.isInValidBounds(pos))return Blocks.AIR.defaultBlockState();
            var cached=reads.get(pos);if(cached!=null)return cached;
            if(reads.size()>=4096)throw new Deferred(Status.INVALID,pos,"Collision read budget exceeded");
            var chunk=level.getChunkSource().getChunkNow(pos.getX()>>4,pos.getZ()>>4);
            if(chunk==null)throw new Deferred(Status.UNLOADED,pos,"Collision neighbour chunk is not loaded");
            var state=writes.get(pos);if(state==null)state=chunk.getBlockState(pos);reads.put(pos.immutable(),state);return state;
        }
        @Override public FluidState getFluidState(BlockPos pos){return getBlockState(pos).getFluidState();}
        @Override public BlockEntity getBlockEntity(BlockPos pos){return null;}
        @Override public int getHeight(){return level.getHeight();}
        @Override public int getMinY(){return level.getMinY();}
    }
}

package com.wjz.worldsmith.content.story;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.wjz.worldsmith.Worldsmith;
import com.wjz.worldsmith.ability.WorldAbilityRuntime;
import com.wjz.worldsmith.ability.AbilityActorControl;
import com.wjz.worldsmith.content.creature.*;
import com.wjz.worldsmith.core.content.CreatureCategory;
import com.wjz.worldsmith.core.story.*;
import java.util.*;
import net.fabricmc.fabric.api.attachment.v1.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.world.Difficulty;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.*;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Marker-owned persistent residents. An unloaded spawned actor is never interpreted as an empty slot. */
public final class StoryCharacters {
    private static AttachmentType<Identity> TYPE;
    private static final Map<ServerLevel,LinkedHashSet<UUID>> LOADED=new IdentityHashMap<>();
    private static final Map<ServerLevel,Map<UUID,RoutineMotion>> MOTION=new IdentityHashMap<>();
    private StoryCharacters() {}
    record Identity(String scope,UUID anchor) {
        static final Codec<Identity> CODEC=StorySavedData.strict(RecordCodecBuilder.create(i -> i.group(
            Codec.STRING.fieldOf("scope").forGetter(Identity::scope),StorySavedData.UUID_CODEC.fieldOf("anchor").forGetter(Identity::anchor)
        ).apply(i,Identity::new)));
        Identity {if(scope==null||!scope.matches("[0-9a-f]{64}"))throw new IllegalArgumentException("Invalid story actor scope");Objects.requireNonNull(anchor);}
    }
    private static final class RoutineMotion {
        String routine;BlockPos target;Path path;long retryAt;
        RoutineMotion(String routine,BlockPos target){this.routine=routine;this.target=target;}
    }
    public static synchronized void register(){if(TYPE==null)TYPE=AttachmentRegistry.<Identity>create(Worldsmith.id("story_character"),b -> b.persistent(Identity.CODEC));}
    private static Identity identity(Entity entity){return TYPE==null?null:((AttachmentTarget)entity).getAttached(TYPE);}
    public static String key(Entity entity) {
        if(!(entity.level() instanceof ServerLevel level))return null;var bound=WorldStoryRuntime.WORLDS.get(level);var record=bound==null?null:record(bound,entity);
        return record==null?null:bound.snapshot.scope()+":"+record.anchor();
    }
    static StorySavedData.Character record(WorldStoryRuntime.Bound bound,Entity entity) {
        var identity=identity(entity);if(identity==null||!identity.scope.equals(bound.snapshot.scope())||!(entity instanceof CreatureEntity creature))return null;
        var record=bound.store().state().characters().get(identity.anchor);var definition=record==null?null:bound.snapshot.characters.get(record.definition());
        return record!=null&&!record.dead()&&record.actor().equals(entity.getUUID())&&record.dimension().equals(bound.level.dimension().identifier().toString())&&definition!=null
            &&creature.bundleHash().equals(bound.snapshot.scope())&&creature.creatureId().equals(definition.getCreature())?record:null;
    }
    public static void loaded(Entity entity,ServerLevel level) {
        if(!(entity instanceof CreatureEntity)||identity(entity)==null)return;
        var bound=WorldStoryRuntime.WORLDS.get(level);var identity=identity(entity);
        if(bound!=null&&identity.scope.equals(bound.snapshot.scope())) {
            var slot=bound.store().state().characters().get(identity.anchor);
            if(slot!=null&&(slot.dead()||!slot.actor().equals(entity.getUUID()))) {
                // Retire a stale saved birth before a queued spawn-event ability gets a server tick.
                WorldAbilityRuntime.cancelOwner((CreatureEntity)entity);entity.discard();return;
            }
        }
        synchronized(LOADED){var ids=LOADED.computeIfAbsent(level,ignored -> new LinkedHashSet<>());if(ids.size()<StorySavedData.MAX_CHARACTERS)ids.add(entity.getUUID());}
    }
    static void flushLoads(MinecraftServer server) {
        List<ServerLevel> levels;synchronized(LOADED){levels=List.copyOf(LOADED.keySet());}
        for(var level:levels)if(level.getServer()==server) {
            var bound=WorldStoryRuntime.WORLDS.get(level);if(bound==null)continue;
            List<UUID> ids;synchronized(LOADED){ids=LOADED.getOrDefault(level,new LinkedHashSet<>()).stream().limit(256).toList();LOADED.get(level).removeAll(ids);}
            for(UUID id:ids)if(level.getEntity(id) instanceof CreatureEntity actor) {
                var identity=identity(actor);if(identity==null)continue;
                var slot=bound.store().state().characters().get(identity.anchor);
                if(identity.scope.equals(bound.snapshot.scope())&&slot!=null&&(slot.dead()||!slot.actor().equals(id))) {actor.discard();continue;}
                var record=record(bound,actor);
                if(record==null) {actor.setNoAi(true);actor.getNavigation().stop();Worldsmith.LOGGER.error("Story resident {} has unresolved persistent identity; retained without AI",id);continue;}
                configure(actor,bound,record);
                if(!record.spawned()) {var store=bound.store();var before=store.state();store.replace(before,before.withCharacter(record.spawnedNow()));}
            }
        }
    }
    static void marker(WorldStoryRuntime.Bound bound,Marker marker,StoryPlaces.MarkerIdentity identity) {
        var definition=bound.snapshot.characters.get(identity.character());
        if(definition==null||!definition.getPlace().equals(identity.place()))throw new IllegalArgumentException("Story character marker does not match its declared home");
        var store=bound.store();var before=store.state();var record=before.characters().get(marker.getUUID());
        if(record==null) {
            var place=StoryPlaces.homePlace(bound,marker,identity.place());if(place==null)return;
            record=new StorySavedData.Character(marker.getUUID(),definition.getId(),place.instance(),bound.level.dimension().identifier().toString(),marker.blockPosition(),StoryPlaces.quarterTurns(marker.getYRot()),UUID.randomUUID(),false,false,0);
            store.replace(before,before.withCharacter(record));
        } else if(!record.definition().equals(definition.getId())||!record.dimension().equals(bound.level.dimension().identifier().toString())||!record.home().equals(marker.blockPosition())||record.quarterTurns()!=StoryPlaces.quarterTurns(marker.getYRot()))
            throw new IllegalStateException("A persisted character marker changed its immutable identity/location");
        // spawned + absent means unloaded, removed administratively, or pending disk load: not permission to duplicate.
        if(record.spawned()&&!record.dead())return;
        long now=bound.level.getGameTime();
        if(!spawnEligible(record,now,definition.getRespawnTicks()!=null)||bound.level.getEntity(record.actor())!=null)return;
        var context=new WorldStoryRuntime.Context(bound,null,null,record,store.state().places().get(record.place()));
        if(!WorldStoryRuntime.test(context,definition.getSpawnWhen()))return;
        attemptSpawn(bound,record,definition);
    }
    static boolean spawnEligible(StorySavedData.Character record,long now,boolean respawnAllowed) {
        return record.dead()?respawnAllowed&&now>=record.availableAt():!record.spawned();
    }
    private static void attemptSpawn(WorldStoryRuntime.Bound bound,StorySavedData.Character old,StoryCharacter definition) {
        var level=bound.level;var home=old.home();var creature=bound.snapshot.creatures.definitions().get(definition.getCreature());
        if(creature==null||creature.getCategory()==CreatureCategory.HOSTILE&&level.getDifficulty()==Difficulty.PEACEFUL)return;
        if(!level.isInValidBounds(home)||!WorldAbilityRuntime.loaded(level,new AABB(home).inflate(2))||!level.getWorldBorder().isWithinBounds(home)
            ||!level.getBlockState(home.below()).isFaceSturdy(level,home.below(),Direction.UP))return;
        var pending=old.dead()?old.respawned(UUID.randomUUID()):old;
        var type=creature.getCategory()==CreatureCategory.PASSIVE?CreatureRuntime.passiveType():CreatureRuntime.hostileType();
        CreatureEntity actor=type.create(level,EntitySpawnReason.TRIGGERED);if(actor==null)return;
        var store=bound.store();boolean added=false;
        try {
            actor.setUUID(pending.actor());actor.snapTo(home.getX()+.5,home.getY(),home.getZ()+.5,pending.quarterTurns()*90f,0);
            actor.initialize(bound.snapshot.scope(),definition.getCreature(),level.getRandom().nextLong());
            ((AttachmentTarget)actor).setAttached(TYPE,new Identity(bound.snapshot.scope(),pending.anchor()));configure(actor,bound,pending);
            if(!WorldAbilityRuntime.loaded(level,actor.getBoundingBox().inflate(1))||!level.getWorldBorder().isWithinBounds(actor.getBoundingBox())||!actor.checkSpawnObstruction(level)||actor.getBoundingBox().maxY>level.getMaxY())return;
            var before=store.state();if(before.characters().get(old.anchor())!=old)return;
            // Reserve the stable slot before publication. Entity-load callbacks are queued, not reentrant ledger writes.
            store.replace(before,before.withCharacter(pending));
            if(!level.addFreshEntity(actor))return;
            added=true;
            var current=store.state();if(current.characters().get(pending.anchor())!=pending)throw new IllegalStateException("Story spawn reservation changed during entity publication");
            store.replace(current,current.withCharacter(pending.spawnedNow()));WorldStoryRuntime.queueAll(level.getServer());
        } finally {
            var current=store.state();var slot=current.characters().get(old.anchor());
            if(!added||slot==pending) {
                actor.discard();
                // Roll back only our reservation, retaining unrelated facts changed by other observers.
                if(slot==pending&&pending!=old)store.replace(current,current.withCharacter(old));
            }
        }
    }
    private static void configure(CreatureEntity actor,WorldStoryRuntime.Bound bound,StorySavedData.Character record) {
        actor.setPersistenceRequired();actor.bindStoryHome(record.home());
        actor.setCustomName(Component.literal(bound.snapshot.characters.get(record.definition()).getName()));
    }
    public static void died(LivingEntity actor,DamageSource source) {
        if(!(actor.level() instanceof ServerLevel level))return;var bound=WorldStoryRuntime.WORLDS.get(level);var old=bound==null?null:record(bound,actor);if(old==null)return;
        var definition=bound.snapshot.characters.get(old.definition());long now=level.getGameTime();
        long ready=definition.getRespawnTicks()==null?Long.MAX_VALUE:now>Long.MAX_VALUE-definition.getRespawnTicks()?Long.MAX_VALUE:now+definition.getRespawnTicks();
        var store=bound.store();var before=store.state();var dead=old.died(ready);store.replace(before,before.withCharacter(dead));
        if(actor instanceof CreatureEntity creature)stopRoutine(creature);
        try {
            var killer=source.getEntity() instanceof ServerPlayer player&&player.level()==level?player:null;
            var context=new WorldStoryRuntime.Context(bound,actor,killer,dead,store.state().places().get(dead.place()));
            var changes=WorldStoryRuntime.prepareChanges(context,definition.getOnDeath());changes.commit();
        } catch(RuntimeException failure) {Worldsmith.LOGGER.error("Resident {} death facts failed; death ownership remains spent",old.anchor(),failure);}
        WorldStoryRuntime.queueAll(level.getServer());
    }
    static boolean inWindow(long clock,int start,int end) {if(start<0||start>23999||end<0||end>23999||start==end)return false;int tick=(int)Math.floorMod(clock,24000);return start<end?tick>=start&&tick<end:tick>=start||tick<end;}
    static BlockPos destination(StorySavedData.Character record,StoryOffset offset) {
        int x=offset.getX(),z=offset.getZ();for(int i=0;i<record.quarterTurns();i++){int next=-z;z=x;x=next;}return record.home().offset(x,offset.getY(),z);
    }
    private static StoryRoutine routine(CreatureEntity actor) {
        if(!(actor.level() instanceof ServerLevel level)||!actor.isAlive()||actor.isRemoved()||actor.isNoAi()||AbilityActorControl.hasControl(actor))return null;
        var bound=WorldStoryRuntime.WORLDS.get(level);var record=bound==null?null:record(bound,actor);if(record==null)return null;
        var context=new WorldStoryRuntime.Context(bound,actor,null,record,bound.store().state().places().get(record.place()));
        return bound.snapshot.characters.get(record.definition()).getRoutines().stream().filter(r -> inWindow(level.getOverworldClockTime(),r.getStartTick(),r.getEndTick())&&WorldStoryRuntime.test(context,r.getCondition())).findFirst().orElse(null);
    }
    private static boolean controllable(CreatureEntity actor) {
        if(!(actor.level() instanceof ServerLevel level)||!actor.isAlive()||actor.isRemoved()||actor.isNoAi()||AbilityActorControl.hasControl(actor))return false;
        var bound=WorldStoryRuntime.WORLDS.get(level);return bound!=null&&record(bound,actor)!=null;
    }
    public static boolean hasRoutine(CreatureEntity actor){return controllable(actor)&&(StorySessions.speaker(actor)!=null||routine(actor)!=null);}
    /** A player's live conversation outranks ambient decisions; explicit emergencies may interrupt it. */
    public static boolean allowControl(CreatureEntity actor,int priority) {
        return priority>=AbilityActorControl.EMERGENCY_PRIORITY||StorySessions.speaker(actor)==null;
    }
    public static void interruptForControl(CreatureEntity actor,int priority) {
        if(priority>=AbilityActorControl.EMERGENCY_PRIORITY)StorySessions.interruptActor(actor);
    }
    public static boolean beginConversation(CreatureEntity actor) { return AbilityActorControl.yieldToConversation(actor); }
    public static boolean tick(CreatureEntity actor) {
        if(!controllable(actor))return false;
        var speaker=StorySessions.speaker(actor);
        if(speaker!=null) {
            stopRoutine(actor);actor.getNavigation().stop();
            float yaw=(float)Math.toDegrees(Math.atan2(-(speaker.getX()-actor.getX()),speaker.getZ()-actor.getZ()));
            float facing=net.minecraft.util.Mth.approachDegrees(actor.getYRot(),yaw,20);
            actor.setYRot(facing);actor.setYBodyRot(facing);actor.setYHeadRot(facing);
            actor.getLookControl().setLookAt(speaker.getX(),speaker.getEyeY(),speaker.getZ(),30,30);
            return true;
        }
        var routine=routine(actor);if(routine==null)return false;
        var level=(ServerLevel)actor.level();var bound=WorldStoryRuntime.WORLDS.get(level);var record=record(bound,actor);if(record==null)return false;
        var target=destination(record,routine.getOffset());var states=MOTION.computeIfAbsent(level,ignored -> new HashMap<>());var state=states.get(actor.getUUID());
        if(state==null||!state.routine.equals(routine.getId())||!state.target.equals(target)) {stopRoutine(actor);state=new RoutineMotion(routine.getId(),target);states=MOTION.computeIfAbsent(level,ignored -> new HashMap<>());states.put(actor.getUUID(),state);}
        long now=level.getGameTime();if(now<state.retryAt)return true;state.retryAt=now+20;
        if(actor.position().distanceToSqr(Vec3.atBottomCenterOf(target))<=1.25) {
            if(state.path!=null&&actor.getNavigation().getPath()==state.path)actor.getNavigation().stop();state.path=null;return true;
        }
        if(!level.isInValidBounds(target)||!level.getWorldBorder().isWithinBounds(target)||!WorldAbilityRuntime.loaded(level,new AABB(actor.position(),Vec3.atBottomCenterOf(target)).inflate(8)))return true;
        var path=actor.getNavigation().createPath(target.getX()+.5,target.getY(),target.getZ()+.5,1);
        if(path==null||path.isDone()||!path.canReach())return true;
        if(actor.getNavigation().moveTo(path,routine.getSpeed()))state.path=path;
        return true;
    }
    public static void stopRoutine(CreatureEntity actor){stopRoutine(actor,false);}
    /** Called before an event program's first native effects; retires the old route without touching its future route. */
    public static void yieldRoutine(CreatureEntity actor){stopRoutine(actor,true);}
    private static void stopRoutine(CreatureEntity actor,boolean beforeProgramEffects) {
        if(!(actor.level() instanceof ServerLevel level))return;var states=MOTION.get(level);var state=states==null?null:states.remove(actor.getUUID());
        if(state!=null&&state.path!=null&&actor.getNavigation().getPath()==state.path&&(beforeProgramEffects||!AbilityActorControl.hasControl(actor)))actor.getNavigation().stop();
    }
    public static void unbind(ServerLevel level){synchronized(LOADED){LOADED.remove(level);}MOTION.remove(level);}
}

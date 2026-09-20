package com.wjz.worldsmith.content.story;

import com.wjz.worldsmith.Worldsmith;
import com.wjz.worldsmith.ability.WorldAbilityRuntime;
import com.wjz.worldsmith.core.story.StoryValidation;
import com.wjz.worldsmith.content.creature.CreatureEntity;
import java.util.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Marker;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Actual typed marker discovery. Registration waits until END tick, after native template transforms. */
public final class StoryPlaces {
    static final String MARKER="worldsmith.story",SCOPE="worldsmith.scope.",PLACE="worldsmith.place.",CHARACTER="worldsmith.character.";
    private static final int MAX_PENDING=8192,PER_TICK=256;
    private static final Map<ServerLevel,LinkedHashMap<UUID,Pending>> PENDING=new IdentityHashMap<>();
    private StoryPlaces() {}
    record MarkerIdentity(String scope,String place,String character) {}
    private record Pending(MarkerIdentity identity,long readyAt) {}

    /** No arbitrary marker NBT is interpreted; duplicate identity tags are invalid. */
    static MarkerIdentity identity(Set<String> tags) {
        if(!tags.contains(MARKER))return null;
        var scope=single(tags,SCOPE);var place=single(tags,PLACE);var character=single(tags,CHARACTER);
        if(scope==null||!scope.matches("[0-9a-f]{64}")||place==null||!StoryValidation.validId(place))return null;
        if(tags.stream().anyMatch(t -> t.startsWith(CHARACTER))&&(character==null||!StoryValidation.validId(character)))return null;
        return new MarkerIdentity(scope,place,character);
    }
    private static String single(Set<String> tags,String prefix) {
        var values=tags.stream().filter(v -> v.startsWith(prefix)).toList();return values.size()==1?values.getFirst().substring(prefix.length()):null;
    }
    /** Rebinding a live level must rebuild transient marker work without minting identities or loading chunks. */
    static void bind(WorldStoryRuntime.Bound bound) {
        var level=bound.level;var scope=bound.snapshot.scope();var existing=new LinkedHashMap<UUID,Entity>();
        // Durable identities are bounded and take priority over as-yet-unregistered loaded markers.
        var state=bound.store().state();
        for(var place:state.places().values())if(place.dimension().equals(level.dimension().identifier().toString())) {
            var entity=level.getEntity(place.instance());if(entity instanceof Marker)existing.put(entity.getUUID(),entity);
        }
        for(var character:state.characters().values())if(character.dimension().equals(level.dimension().identifier().toString())) {
            var marker=level.getEntity(character.anchor());if(marker instanceof Marker)existing.put(marker.getUUID(),marker);
            var actor=level.getEntity(character.actor());if(actor instanceof CreatureEntity)StoryCharacters.loaded(actor,level);
        }
        // One bind-time enumeration, not a per-tick scan. Stage before callbacks so retirement cannot mutate the iterator.
        for(var entity:level.getAllEntities())if(entity instanceof Marker marker) {
            var identity=identity(marker.entityTags());
            if(identity==null||!identity.scope().equals(scope))continue;
            if(existing.size()>=MAX_PENDING&&!existing.containsKey(entity.getUUID())) {
                Worldsmith.LOGGER.warn("Story bind marker budget reached in {}; remaining loaded markers were left intact",level.dimension().identifier());break;
            }
            existing.putIfAbsent(entity.getUUID(),entity);
        }
        for(var entity:existing.values())loaded(entity,level);
    }
    static int quarterTurns(float yaw) {
        if(!Float.isFinite(yaw))throw new IllegalArgumentException("Non-finite story marker rotation");
        return Math.floorMod(Math.round(yaw/90f),4);
    }
    public static void loaded(Entity entity,ServerLevel level) {
        StoryCharacters.loaded(entity,level);
        if(!(entity instanceof Marker))return;
        var identity=identity(entity.entityTags());if(identity==null)return;
        synchronized(PENDING) {
            var queue=PENDING.computeIfAbsent(level,ignored -> new LinkedHashMap<>());
            if(queue.size()>=MAX_PENDING&&!queue.containsKey(entity.getUUID())) {
                Worldsmith.LOGGER.error("Story marker queue is full; marker {} is left intact",entity.getUUID());return;
            }
            queue.putIfAbsent(entity.getUUID(),new Pending(identity,level.getGameTime()+1));
        }
    }
    public static void tick(MinecraftServer server) {
        List<ServerLevel> levels;synchronized(PENDING){levels=List.copyOf(PENDING.keySet());}
        for(var level:levels)if(level.getServer()==server) {
            var bound=WorldStoryRuntime.WORLDS.get(level);if(bound==null)continue;
            List<Map.Entry<UUID,Pending>> work;
            synchronized(PENDING){work=new ArrayList<>(PENDING.getOrDefault(level,new LinkedHashMap<>()).entrySet());}
            // Pure place anchors always precede residents, including loads delivered in reverse order.
            work.sort(Comparator.comparing(e -> e.getValue().identity.character()!=null));
            int processed=0;long now=level.getGameTime();
            for(var entry:work) {
                if(entry.getValue().readyAt()>now)continue;if(processed++>=PER_TICK)break;
                UUID id=entry.getKey();var pending=entry.getValue();boolean retry=false;
                var entity=level.getEntity(id);
                try {
                    if(entity instanceof Marker marker&&!marker.isRemoved()&&pending.identity.equals(identity(marker.entityTags()))&&pending.identity.scope.equals(bound.snapshot.scope())) {
                        if(pending.identity.character==null)registerPlace(bound,marker,pending.identity);
                        else {StoryCharacters.marker(bound,marker,pending.identity);retry=true;}
                    }
                } catch(RuntimeException failure) {
                    Worldsmith.LOGGER.error("Story marker {} registration stopped; original marker and history retained",id,failure);
                }
                synchronized(PENDING) {
                    var queue=PENDING.get(level);if(queue==null)continue;
                    if(queue.get(id)==pending){queue.remove(id);if(retry)queue.put(id,new Pending(pending.identity,now+20));}
                }
            }
        }
        StoryCharacters.flushLoads(server);
    }
    private static void registerPlace(WorldStoryRuntime.Bound bound,Marker marker,MarkerIdentity identity) {
        if(!bound.snapshot.places.containsKey(identity.place))throw new IllegalArgumentException("Unknown story place marker");
        var pos=marker.blockPosition();
        if(!bound.level.isInValidBounds(pos)||!bound.level.getWorldBorder().isWithinBounds(pos)||!WorldAbilityRuntime.loaded(bound.level,new AABB(pos)))return;
        var next=new StorySavedData.Place(marker.getUUID(),identity.place,bound.level.dimension().identifier().toString(),pos,quarterTurns(marker.getYRot()));
        var store=bound.store();var before=store.state();var old=before.places().get(marker.getUUID());
        if(old!=null) {if(!old.equals(next))throw new IllegalStateException("A persisted place marker changed its immutable identity/location");return;}
        store.replace(before,before.withPlace(next));WorldStoryRuntime.queueAll(bound.level.getServer());
    }
    /** Definition references resolve only to actual nearby instances, never declared coordinates. */
    static StorySavedData.Place homePlace(WorldStoryRuntime.Bound bound,Marker marker,String definition) {
        var candidates=bound.store().state().places().values().stream()
            .filter(v -> v.definition().equals(definition)&&v.dimension().equals(bound.level.dimension().identifier().toString()))
            .filter(v -> marker.position().distanceToSqr(Vec3.atBottomCenterOf(v.position()))<=256*256)
            .sorted(Comparator.<StorySavedData.Place>comparingDouble(v -> marker.position().distanceToSqr(Vec3.atBottomCenterOf(v.position()))).thenComparing(v -> v.instance().toString())).toList();
        if(candidates.isEmpty())return null;
        if(candidates.size()>1&&Math.abs(marker.position().distanceToSqr(Vec3.atBottomCenterOf(candidates.get(0).position()))-marker.position().distanceToSqr(Vec3.atBottomCenterOf(candidates.get(1).position())))<0.001)return null;
        var place=candidates.getFirst();
        return WorldAbilityRuntime.loaded(bound.level,new AABB(marker.position(),Vec3.atBottomCenterOf(place.position())).inflate(1))?place:null;
    }
    public static void unbind(ServerLevel level){synchronized(PENDING){PENDING.remove(level);}}
}

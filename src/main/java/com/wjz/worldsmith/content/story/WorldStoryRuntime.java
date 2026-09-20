package com.wjz.worldsmith.content.story;

import com.wjz.worldsmith.Worldsmith;
import com.wjz.worldsmith.content.WorldBlockBindings;
import com.wjz.worldsmith.content.WorldRewardItems;
import com.wjz.worldsmith.content.creature.*;
import com.wjz.worldsmith.content.item.CustomItemRuntime;
import com.wjz.worldsmith.content.quest.server.QuestRuntime;
import com.wjz.worldsmith.core.ability.*;
import com.wjz.worldsmith.core.model.WorldsmithPack;
import com.wjz.worldsmith.core.story.*;
import com.wjz.worldsmith.core.validation.DiagnosticSeverity;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.*;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.*;
import net.minecraft.world.phys.Vec3;

/** One save-owned fact authority shared by dialogue, quests, place discovery, character routines and abilities. */
public final class WorldStoryRuntime {
    static final Map<ServerLevel,Bound> WORLDS=new ConcurrentHashMap<>();
    private static final Map<MinecraftServer,Set<UUID>> DIRTY=new IdentityHashMap<>();
    private static Snapshot client;
    private static boolean registered;
    private WorldStoryRuntime() {}

    public static final class Snapshot {
        private final String scope;
        private final StoryLibrary library;
        final CustomItemRuntime.Snapshot items;
        final CreatureRuntime.Snapshot creatures;
        final WorldBlockBindings.Resolver blocks;
        final Map<String,StoryFact> facts=new LinkedHashMap<>();
        final Map<String,StoryPlace> places=new LinkedHashMap<>();
        final Map<String,StoryCharacter> characters=new LinkedHashMap<>();
        final Map<String,StoryDialogue> dialogues=new LinkedHashMap<>();
        final Map<String,StoryTrade> trades=new LinkedHashMap<>();
        final Map<String,StorySoundscape> soundscapes=new LinkedHashMap<>();
        final Map<String,StoryProjections.Prepared> projections=new LinkedHashMap<>();
        Snapshot(WorldsmithPack pack,CustomItemRuntime.Snapshot items,CreatureRuntime.Snapshot creatures,WorldBlockBindings.Resolver blocks) {
            scope=pack.getManifest().getId();library=StoryValidation.freeze(pack.getStory());this.items=items;this.creatures=creatures;this.blocks=blocks;
            library.getFacts().forEach(v -> facts.put(v.getId(),v));library.getPlaces().forEach(v -> places.put(v.getId(),v));
            library.getCharacters().forEach(v -> characters.put(v.getId(),v));library.getDialogues().forEach(v -> dialogues.put(v.getId(),v));
            library.getTrades().forEach(v -> trades.put(v.getId(),v));library.getSoundscapes().forEach(v -> soundscapes.put(v.getId(),v));
            library.getProjections().forEach(v -> projections.put(v.getId(),StoryProjections.prepare(v,blocks)));
        }
        public String scope() {return scope;}
        public StoryLibrary library() {return library;}
        public boolean empty() {return facts.isEmpty()&&places.isEmpty()&&characters.isEmpty()&&dialogues.isEmpty()&&library.getKnowledge().isEmpty()&&trades.isEmpty()&&soundscapes.isEmpty()&&projections.isEmpty();}
    }
    static final class Bound {
        final ServerLevel level;final Snapshot snapshot;
        StorySavedData stored;
        final StoryProjections.Schedule projectionSchedule=new StoryProjections.Schedule();
        Bound(ServerLevel level,Snapshot snapshot){this.level=level;this.snapshot=snapshot;}
        StorySavedData store(){if(stored==null)stored=StorySavedData.load(level.getServer(),snapshot.scope);return stored;}
    }
    static record Context(Bound bound,LivingEntity actor,ServerPlayer player,StorySavedData.Character character,StorySavedData.Place place) {
        Context withPlace(StorySavedData.Place value){return new Context(bound,actor,player,character,value);}
        Context withCharacter(StorySavedData.Character value){return new Context(bound,actor,player,value,value==null?place:bound.store().state().places().get(value.place()));}
    }

    public static synchronized void register() {
        if(registered)return;
        StoryCharacters.register();
        StoryProtocol.registerServer(StorySessions::action);
        ServerEntityEvents.ENTITY_LOAD.register(StoryPlaces::loaded);
        ServerLivingEntityEvents.AFTER_DEATH.register(StoryCharacters::died);
        UseEntityCallback.EVENT.register((player,level,hand,entity,hit) -> {
            if(hand!=InteractionHand.MAIN_HAND || player.isSpectator() || !player.isAlive())return InteractionResult.PASS;
            if(level.isClientSide())return InteractionResult.PASS;
            return player instanceof ServerPlayer server && openDialogue(server,entity)?InteractionResult.SUCCESS:InteractionResult.PASS;
        });
        ServerTickEvents.END_SERVER_TICK.register(WorldStoryRuntime::tick);
        registered=true;
    }
    public static Snapshot prepare(WorldsmithPack pack,CustomItemRuntime.Snapshot items,CreatureRuntime.Snapshot creatures,WorldBlockBindings.Resolver blocks) {
        String scope=pack.getManifest().getId();
        if(!items.bundleHash().equals(scope)||!creatures.bundleHash().equals(scope)||!blocks.snapshot().getScope().equals(scope))
            throw new IllegalArgumentException("Story dependencies must belong to the same immutable world");
        var errors=StoryValidation.validate(pack.getStory()).stream().filter(v -> v.getSeverity()==DiagnosticSeverity.ERROR).toList();
        if(!errors.isEmpty())throw new IllegalArgumentException("Invalid world story: "+errors);
        var snapshot=new Snapshot(pack,items,creatures,blocks);
        for(var trade:snapshot.trades.values()) {
            trade.getInputs().forEach(v -> WorldRewardItems.stack(v.getItem(),1,blocks,items));
            trade.getOutputs().forEach(v -> WorldRewardItems.stack(v.getItem(),v.getCount(),blocks,items));
        }
        for(var soundscape:snapshot.soundscapes.values()) for(var layer:soundscape.getLayers())
            if(BuiltInRegistries.SOUND_EVENT.getOptional(Identifier.parse(layer.getSound())).isEmpty())throw new IllegalArgumentException("Unknown story sound: "+layer.getSound());
        return snapshot;
    }
    public static void bind(ServerLevel level,Snapshot snapshot) {
        requireThread(level);
        var current=WORLDS.get(level);
        if(current!=null) {if(!current.snapshot.scope.equals(snapshot.scope))throw new IllegalStateException("Another story owns this level");return;}
        Bound bound=new Bound(level,snapshot);
        if(!snapshot.empty()) {bound.store();validateStored(bound);}
        WORLDS.put(level,bound);
        if(!snapshot.empty())StoryPlaces.bind(bound);
    }
    public static void unbind(ServerLevel level) {
        StorySessions.unbind(level);StoryPlaces.unbind(level);StoryCharacters.unbind(level);WORLDS.remove(level);
        if(WORLDS.keySet().stream().noneMatch(v -> v.getServer()==level.getServer()))DIRTY.remove(level.getServer());
    }
    public static Snapshot snapshot(ServerLevel level){var bound=WORLDS.get(level);return bound==null?null:bound.snapshot;}
    public static void activateClient(Snapshot value){client=Objects.requireNonNull(value);}
    public static void clearClient(){client=null;}
    public static Snapshot clientSnapshot(){return client;}
    public static long revision(ServerLevel level){var bound=WORLDS.get(level);return bound==null||bound.snapshot.empty()?0:bound.store().state().revision();}

    public static boolean test(ServerPlayer player,StoryCondition condition) {
        var context=context(player);return context==null?StoryConditions.test(condition,ref -> AbilityValues.none()):test(context,condition);
    }
    public static boolean test(ServerPlayer player,StoryCondition condition,String character,String place) {
        var context=context(player);if(context==null)return StoryConditions.test(condition,ref -> AbilityValues.none());
        if(place!=null)context=context.withPlace(resolvePlace(context,place));
        if(character!=null)context=context.withCharacter(resolveCharacter(context,character));
        return test(context,condition);
    }
    static boolean test(Context context,StoryCondition condition) {
        return StoryConditions.test(condition,ref -> {
            try{return value(context,ref);}catch(IllegalArgumentException|IllegalStateException missing){return AbilityValues.none();}
        });
    }
    public static AbilityValue value(ServerPlayer player,StoryFactRef ref){return value(Objects.requireNonNull(context(player),"No story world is bound"),ref);}
    public static AbilityValue value(LivingEntity actor,StoryFactRef ref){return value(Objects.requireNonNull(context(actor),"No story world is bound"),ref);}
    static AbilityValue value(Context context,StoryFactRef ref) {
        String address=address(context,ref);var definition=context.bound.snapshot.facts.get(ref.getId());
        return context.bound.store().state().facts().getOrDefault(address,definition.getInitial());
    }
    static String address(Context context,StoryFactRef ref) {
        var definition=context.bound.snapshot.facts.get(ref.getId());if(definition==null)throw new IllegalArgumentException("Unknown story fact: "+ref.getId());
        String subject=ref.getSubject();
        return switch(definition.getScope()) {
            case WORLD -> {if(subject!=null)throw new IllegalArgumentException("World facts do not accept a subject");yield "w:"+definition.getId();}
            case PLAYER -> {if(subject!=null || context.player==null)throw new IllegalArgumentException("Player fact requires a current player");yield "p:"+context.player.getUUID()+":"+definition.getId();}
            case CHARACTER -> {var character=subject==null?context.character:resolveCharacter(context,subject);if(character==null)throw new IllegalArgumentException("Character fact has no unambiguous current subject");yield "c:"+character.anchor()+":"+definition.getId();}
            case PLACE -> {var place=subject==null?context.place:resolvePlace(context,subject);if(place==null)throw new IllegalArgumentException("Place fact has no unambiguous current subject");yield "l:"+place.instance()+":"+definition.getId();}
        };
    }
    static Context context(LivingEntity actor) {
        if(!(actor.level() instanceof ServerLevel level))return null;requireThread(level);
        var bound=WORLDS.get(level);if(bound==null || bound.snapshot.empty())return null;
        var character=StoryCharacters.record(bound,actor);
        var place=character==null?nearest(bound,actor.position(),true):bound.store().state().places().get(character.place());
        if(actor instanceof ServerPlayer player && place!=null && !bound.store().state().player(player.getUUID()).places().contains(place.instance()))place=null;
        return new Context(bound,actor,actor instanceof ServerPlayer player?player:null,character,place);
    }
    static StorySavedData.Place nearest(Bound bound,Vec3 position,boolean withinRadius) {
        return bound.store().state().places().values().stream().filter(v -> v.dimension().equals(bound.level.dimension().identifier().toString()))
            .filter(v -> !withinRadius || position.distanceToSqr(Vec3.atCenterOf(v.position()))<=Math.pow(bound.snapshot.places.get(v.definition()).getDiscoveryRadius(),2))
            .min(Comparator.comparingDouble(v -> position.distanceToSqr(Vec3.atCenterOf(v.position())))).orElse(null);
    }
    static StorySavedData.Place resolvePlace(Context context,String definition) {
        StorySavedData.id(definition);
        if(context.place!=null && context.place.definition().equals(definition))return context.place;
        var matches=context.bound.store().state().places().values().stream().filter(v -> v.definition().equals(definition)).toList();
        return matches.size()==1?matches.getFirst():null;
    }
    static StorySavedData.Character resolveCharacter(Context context,String definition) {
        StorySavedData.id(definition);
        if(context.character!=null && context.character.definition().equals(definition))return context.character;
        var matches=context.bound.store().state().characters().values().stream().filter(v -> v.definition().equals(definition)).toList();
        if(matches.size()==1)return matches.getFirst();
        if(context.place!=null) {var local=matches.stream().filter(v -> v.place().equals(context.place.instance())).toList();if(local.size()==1)return local.getFirst();}
        return null;
    }

    public static PreparedChanges prepareChanges(ServerPlayer player,List<StoryFactChange> changes) {
        if(changes.isEmpty())return PreparedChanges.noop(player.level());
        return prepareChanges(Objects.requireNonNull(context(player),"No story world is bound"),changes);
    }
    public static PreparedChanges prepareChanges(LivingEntity actor,List<StoryFactChange> changes) {
        if(changes.isEmpty())return PreparedChanges.noop(actor.level());
        return prepareChanges(Objects.requireNonNull(context(actor),"No story world is bound"),changes);
    }
    static PreparedChanges prepareChanges(Context context,List<StoryFactChange> changes) {
        if(changes.size()>64)throw new IllegalArgumentException("Too many story changes in one transaction");
        var store=context.bound.store();var before=store.state();var facts=new LinkedHashMap<>(before.facts());var seen=new HashSet<String>();
        for(var change:changes) {
            String address=address(context,change.getFact());if(!seen.add(address))throw new IllegalArgumentException("One transaction writes a fact more than once");
            var definition=context.bound.snapshot.facts.get(change.getFact().getId());var value=change.getValue();
            if(change.getMode()==StoryChangeMode.ADD) {
                var old=facts.getOrDefault(address,definition.getInitial());
                if(!(old instanceof AbilityValue.NumberValue a) || !(value instanceof AbilityValue.NumberValue b))throw new IllegalArgumentException("ADD requires numeric story values");
                value=AbilityValues.number(a.getValue()+b.getValue());
            }
            if(!StoryValidation.matches(definition,value))throw new IllegalArgumentException("Story fact value exceeds its declared type/range: "+definition.getId());
            if(value.equals(definition.getInitial()))facts.remove(address);else facts.put(address,value);
        }
        return new PreparedChanges(context.bound.level,store,before,before.withFacts(facts));
    }
    /** Validated replacement can participate in an inventory/quest transaction; publication only queues observers. */
    public static final class PreparedChanges {
        private final ServerLevel level;
        private final StorySavedData store;
        private final StorySavedData.State before;
        private StorySavedData.State after;
        private boolean committed,rolledBack;
        PreparedChanges(ServerLevel level,StorySavedData store,StorySavedData.State before,StorySavedData.State after){this.level=level;this.store=store;this.before=before;this.after=after;}
        static PreparedChanges noop(net.minecraft.world.level.Level level){if(!(level instanceof ServerLevel server))throw new IllegalArgumentException("Story writes are server-only");return new PreparedChanges(server,null,null,null);}
        void player(UUID player,StorySavedData.PlayerMemory memory){assertUnchanged();after=after.withPlayer(player,memory);}
        void character(StorySavedData.Character character){assertUnchanged();after=after.withCharacter(character);}
        void projection(StorySavedData.ProjectionReceipt receipt){assertUnchanged();after=after.withProjection(receipt);}
        public void assertUnchanged(){requireThread(level);if(committed||rolledBack||store!=null&&store.state()!=before)throw new IllegalStateException("Story transaction became stale");}
        public void commit(){assertUnchanged();if(store!=null)store.replace(before,after);committed=true;if(after!=before)queueAll(level.getServer());}
        public void rollback(){requireThread(level);if(rolledBack)return;if(committed&&store!=null)store.replace(after,before);rolledBack=true;committed=false;}
    }
    public static void changed(ServerPlayer player){requireThread((ServerLevel)player.level());DIRTY.computeIfAbsent(player.level().getServer(),ignored -> new LinkedHashSet<>()).add(player.getUUID());}
    static void queueAll(MinecraftServer server){for(var player:server.getPlayerList().getPlayers())changed(player);}
    public static String characterKey(Entity entity){return StoryCharacters.key(entity);}
    public static StoryCharacter characterDefinition(CreatureEntity entity){var bound=entity.level() instanceof ServerLevel level?WORLDS.get(level):null;var record=bound==null?null:StoryCharacters.record(bound,entity);return record==null?null:bound.snapshot.characters.get(record.definition());}
    public static boolean characterTick(CreatureEntity entity){return StoryCharacters.tick(entity);}
    public static boolean openDialogue(ServerPlayer player,Entity entity){return StorySessions.open(player,entity);}
    public static StoryProtocol.Snapshot journalSnapshot(ServerPlayer player){requireThread((ServerLevel)player.level());return StorySessions.snapshot(player,0,StoryProtocol.Feedback.NONE,"");}
    public static void handle(ServerPlayer player,StoryProtocol.Action action){StorySessions.action(player,action);}

    static void discover(ServerPlayer player) {
        if(!player.isAlive()||player.isSpectator())return;
        var context=context(player);if(context==null)return;
        var bound=context.bound;var store=bound.store();var state=store.state();var memory=state.player(player.getUUID());
        for(var place:state.places().values()) {
            var definition=bound.snapshot.places.get(place.definition());
            if(memory.places().contains(place.instance()) || !place.dimension().equals(bound.level.dimension().identifier().toString())
                || player.position().distanceToSqr(Vec3.atCenterOf(place.position()))>Math.pow(definition.getDiscoveryRadius(),2))continue;
            var at=context.withPlace(place);if(!test(at,definition.getDiscoverWhen()))continue;
            var plan=prepareChanges(at,definition.getOnDiscover());memory=store.state().player(player.getUUID()).discover(place.instance());plan.player(player.getUUID(),memory);plan.commit();
        }
        state=store.state();memory=state.player(player.getUUID());
        for(var knowledge:bound.snapshot.library.getKnowledge())if(!memory.knowledge().contains(knowledge.getId())&&test(context,knowledge.getDiscoverWhen()))memory=memory.learn(knowledge.getId());
        if(!memory.equals(state.player(player.getUUID()))) {store.replace(state,state.withPlayer(player.getUUID(),memory));changed(player);}
    }
    private static void tick(MinecraftServer server) {
        StoryPlaces.tick(server);StoryProjections.tick(server);StorySessions.tick(server);
        if(server.getTickCount()%10==0)for(var player:server.getPlayerList().getPlayers())if(player.isAlive()&&!player.isSpectator()) {
            try{discover(player);StorySessions.refresh(player);}catch(RuntimeException failure){Worldsmith.LOGGER.error("Story observation failed for {}",player.getUUID(),failure);}
        }
        var dirty=DIRTY.remove(server);if(dirty!=null)for(UUID id:dirty) {
            var player=server.getPlayerList().getPlayer(id);if(player==null)continue;
            QuestRuntime.storyChanged(player);StorySessions.refresh(player);
        }
    }
    private static void validateStored(Bound bound) {
        validateStored(bound.snapshot.library,bound.store().state());
    }
    static void validateStored(StoryLibrary library,StorySavedData.State state) {
        var facts=new HashMap<String,StoryFact>();library.getFacts().forEach(v -> facts.put(v.getId(),v));
        var places=new HashSet<String>();library.getPlaces().forEach(v -> places.add(v.getId()));
        var characters=new HashMap<String,StoryCharacter>();library.getCharacters().forEach(v -> characters.put(v.getId(),v));
        var knowledge=new HashSet<String>();library.getKnowledge().forEach(v -> knowledge.add(v.getId()));
        var trades=new HashMap<String,StoryTrade>();library.getTrades().forEach(v -> trades.put(v.getId(),v));
        var projections=new HashMap<String,StoryProjection>();library.getProjections().forEach(v -> projections.put(v.getId(),v));
        for(var place:state.places().values())if(!places.contains(place.definition()))throw new IllegalStateException("Stored place definition is missing");
        for(var character:state.characters().values()) {
            var definition=characters.get(character.definition());var home=state.places().get(character.place());
            if(definition==null||!definition.getPlace().equals(home.definition()))throw new IllegalStateException("Stored character definition/home is invalid");
        }
        for(var receipt:state.projections()) {
            var definition=projections.get(receipt.projection());var place=state.places().get(receipt.place());
            if(definition==null||place==null||!definition.getPlace().equals(place.definition()))throw new IllegalStateException("Stored projection definition/place is invalid");
        }
        for(var entry:state.facts().entrySet()) {
            String[] parts=entry.getKey().split(":");var fact=facts.get(parts[parts.length-1]);
            if(fact==null||!StoryValidation.matches(fact,entry.getValue()))throw new IllegalStateException("Stored fact definition/type is invalid");
            String prefix=switch(fact.getScope()){case WORLD -> "w";case PLAYER -> "p";case CHARACTER -> "c";case PLACE -> "l";};
            if(!parts[0].equals(prefix))throw new IllegalStateException("Stored fact scope is invalid");
        }
        for(var memory:state.players().values()) {
            if(!knowledge.containsAll(memory.knowledge()))throw new IllegalStateException("Stored knowledge definition is missing");
            memory.trades().forEach((id,use) -> {
                var definition=trades.get(id);
                if(definition==null||definition.getMaxUsesPerPlayer()>0&&use.count()>definition.getMaxUsesPerPlayer())
                    throw new IllegalStateException("Stored trade definition/count is invalid");
            });
        }
    }
    static void requireThread(ServerLevel level){if(!level.getServer().isSameThread())throw new IllegalStateException("Story state belongs to the server thread");}
}

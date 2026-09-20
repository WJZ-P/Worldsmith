package com.wjz.worldsmith.content.story;

import com.mojang.datafixers.util.Pair;
import com.mojang.serialization.*;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.wjz.worldsmith.core.ability.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.saveddata.*;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.level.storage.SavedDataStorage;

/** Save-owned story facts and actual instance identities; no script continuation or client assertion is persisted. */
public final class StorySavedData extends SavedData {
    static final int MAX_FACTS=32768,MAX_PLACES=2048,MAX_CHARACTERS=4096,MAX_PLAYERS=1024,MAX_RECEIPTS=32768,MAX_BYTES=8*1024*1024;
    static final Codec<UUID> UUID_CODEC=strict(Codec.STRING.xmap(StorySavedData::uuid,UUID::toString));
    static final Codec<AbilityValue> VALUE_CODEC=strict(Codec.STRING.xmap(text -> {
        var values=AbilityValues.decodeState(text);
        if(values.size()!=1 || !values.containsKey("v")) throw new IllegalArgumentException("Invalid stored story value");
        return primitive(values.get("v"));
    },value -> AbilityValues.encodeState(Map.of("v",primitive(value)))));

    public record Place(UUID instance,String definition,String dimension,BlockPos position,int quarterTurns) {
        static final Codec<Place> CODEC=strict(RecordCodecBuilder.create(i -> i.group(
            UUID_CODEC.fieldOf("instance").forGetter(Place::instance),Codec.STRING.fieldOf("definition").forGetter(Place::definition),
            Codec.STRING.fieldOf("dimension").forGetter(Place::dimension),BlockPos.CODEC.fieldOf("position").forGetter(Place::position),Codec.intRange(0,3).fieldOf("quarterTurns").forGetter(Place::quarterTurns)
        ).apply(i,Place::new)));
        public Place { Objects.requireNonNull(instance); id(definition); location(dimension,position); position=position.immutable();if(quarterTurns<0||quarterTurns>3)throw new IllegalArgumentException("Invalid place rotation"); }
    }
    public record Character(UUID anchor,String definition,UUID place,String dimension,BlockPos home,int quarterTurns,UUID actor,boolean spawned,boolean dead,long availableAt) {
        static final Codec<Character> CODEC=strict(RecordCodecBuilder.create(i -> i.group(
            UUID_CODEC.fieldOf("anchor").forGetter(Character::anchor),Codec.STRING.fieldOf("definition").forGetter(Character::definition),
            UUID_CODEC.fieldOf("place").forGetter(Character::place),Codec.STRING.fieldOf("dimension").forGetter(Character::dimension),
            BlockPos.CODEC.fieldOf("home").forGetter(Character::home),Codec.intRange(0,3).fieldOf("quarterTurns").forGetter(Character::quarterTurns),UUID_CODEC.fieldOf("actor").forGetter(Character::actor),
            Codec.BOOL.fieldOf("spawned").forGetter(Character::spawned),Codec.BOOL.fieldOf("dead").forGetter(Character::dead),Codec.LONG.fieldOf("availableAt").forGetter(Character::availableAt)
        ).apply(i,Character::new)));
        public Character {
            Objects.requireNonNull(anchor); id(definition); Objects.requireNonNull(place); location(dimension,home); home=home.immutable(); Objects.requireNonNull(actor);
            if(availableAt<0||quarterTurns<0||quarterTurns>3||dead&&(!spawned||availableAt==0)||!dead&&availableAt!=0)
                throw new IllegalArgumentException("Invalid character lifecycle/timestamp/rotation");
        }
        public Character died(long retryAt) { return new Character(anchor,definition,place,dimension,home,quarterTurns,actor,true,true,retryAt); }
        public Character spawnedNow() { return new Character(anchor,definition,place,dimension,home,quarterTurns,actor,true,false,0); }
        public Character respawned(UUID next) { return new Character(anchor,definition,place,dimension,home,quarterTurns,next,false,false,0); }
    }
    public record TradeUse(int count,long readyAt) {
        static final Codec<TradeUse> CODEC=strict(RecordCodecBuilder.create(i -> i.group(
            Codec.INT.fieldOf("count").forGetter(TradeUse::count),Codec.LONG.fieldOf("readyAt").forGetter(TradeUse::readyAt)
        ).apply(i,TradeUse::new)));
        public TradeUse { if(count<0 || count>1_000_000 || readyAt<0)throw new IllegalArgumentException("Invalid trade record"); }
    }
    public record PlayerMemory(Set<UUID> places,Set<String> knowledge,Map<String,TradeUse> trades) {
        static final PlayerMemory EMPTY=new PlayerMemory(Set.of(),Set.of(),Map.of());
        static final Codec<PlayerMemory> CODEC=strict(RecordCodecBuilder.create(i -> i.group(
            UUID_CODEC.listOf().fieldOf("places").forGetter(p -> List.copyOf(p.places)),
            Codec.STRING.listOf().fieldOf("knowledge").forGetter(p -> List.copyOf(p.knowledge)),
            Codec.unboundedMap(Codec.STRING,TradeUse.CODEC).fieldOf("trades").forGetter(PlayerMemory::trades)
        ).apply(i,(places,knowledge,trades) -> {
            if(new HashSet<>(places).size()!=places.size() || new HashSet<>(knowledge).size()!=knowledge.size())throw new IllegalArgumentException("Duplicate discovery");
            return new PlayerMemory(new LinkedHashSet<>(places),new LinkedHashSet<>(knowledge),trades);
        })));
        public PlayerMemory {
            if(places.size()>MAX_PLACES || knowledge.size()>128 || trades.size()>256)throw new IllegalArgumentException("Player story memory budget exceeded");
            places.forEach(Objects::requireNonNull); knowledge.forEach(StorySavedData::id); trades.forEach((key,value) -> {id(key);Objects.requireNonNull(value);});
            places=Collections.unmodifiableSet(new LinkedHashSet<>(places)); knowledge=Collections.unmodifiableSet(new LinkedHashSet<>(knowledge)); trades=immutable(trades);
        }
        public PlayerMemory discover(UUID place) { var next=new LinkedHashSet<>(places);next.add(place);return new PlayerMemory(next,knowledge,trades); }
        public PlayerMemory learn(String id) { var next=new LinkedHashSet<>(knowledge);next.add(id);return new PlayerMemory(places,next,trades); }
        public PlayerMemory trade(String id,long readyAt) {var next=new LinkedHashMap<>(trades);var old=next.getOrDefault(id,new TradeUse(0,0));next.put(id,new TradeUse(Math.incrementExact(old.count),readyAt));return new PlayerMemory(places,knowledge,next);}
    }
    public record ProjectionReceipt(UUID place,String projection) {
        static final Codec<ProjectionReceipt> CODEC=strict(RecordCodecBuilder.create(i -> i.group(
            UUID_CODEC.fieldOf("place").forGetter(ProjectionReceipt::place),Codec.STRING.fieldOf("projection").forGetter(ProjectionReceipt::projection)
        ).apply(i,ProjectionReceipt::new)));
        public ProjectionReceipt {Objects.requireNonNull(place);id(projection);}
    }
    public record State(String scope,long revision,Map<String,AbilityValue> facts,Map<UUID,Place> places,Map<UUID,Character> characters,Map<UUID,PlayerMemory> players,Set<ProjectionReceipt> projections) {
        static final Codec<State> CODEC=strict(RecordCodecBuilder.create(i -> i.group(
            Codec.STRING.fieldOf("scope").forGetter(State::scope),Codec.LONG.fieldOf("revision").forGetter(State::revision),
            Codec.unboundedMap(Codec.STRING,VALUE_CODEC).fieldOf("facts").forGetter(State::facts),
            Codec.unboundedMap(UUID_CODEC,Place.CODEC).fieldOf("places").forGetter(State::places),
            Codec.unboundedMap(UUID_CODEC,Character.CODEC).fieldOf("characters").forGetter(State::characters),
            Codec.unboundedMap(UUID_CODEC,PlayerMemory.CODEC).fieldOf("players").forGetter(State::players),
            ProjectionReceipt.CODEC.listOf().fieldOf("projections").forGetter(s -> List.copyOf(s.projections))
        ).apply(i,(scope,revision,facts,places,characters,players,projections) -> {
            if(new HashSet<>(projections).size()!=projections.size())throw new IllegalArgumentException("Duplicate projection receipt");
            return new State(scope,revision,facts,places,characters,players,new LinkedHashSet<>(projections));
        })));
        public State {
            if(!scope.matches("[0-9a-f]{64}") || revision<0 || facts.size()>MAX_FACTS || places.size()>MAX_PLACES || characters.size()>MAX_CHARACTERS || players.size()>MAX_PLAYERS || projections.size()>MAX_RECEIPTS)
                throw new IllegalArgumentException("Invalid story ledger scope/count");
            int bytes=0;
            for(var entry:facts.entrySet()) {address(entry.getKey());primitive(entry.getValue());bytes=Math.addExact(bytes,entry.getKey().length()+AbilityValues.encodeState(Map.of("v",entry.getValue())).getBytes(StandardCharsets.UTF_8).length);}
            if(bytes>MAX_BYTES)throw new IllegalArgumentException("Story fact data exceeds 8 MiB");
            places.forEach((key,value) -> {if(!key.equals(value.instance))throw new IllegalArgumentException("Place identity mismatch");});
            var placeRecords=places;var knownPlaces=places.keySet();
            var actorIds=new HashSet<UUID>();
            characters.forEach((key,value) -> {
                if(!key.equals(value.anchor) || !knownPlaces.contains(value.place)
                    || !placeRecords.get(value.place).dimension.equals(value.dimension) || !actorIds.add(value.actor))
                    throw new IllegalArgumentException("Character anchor/place/actor mismatch");
            });
            players.forEach((key,memory) -> {Objects.requireNonNull(key);if(!knownPlaces.containsAll(memory.places))throw new IllegalArgumentException("Unknown discovered place");});
            projections.forEach(receipt -> {if(!knownPlaces.contains(receipt.place))throw new IllegalArgumentException("Unknown projection place");});
            for(String key:facts.keySet()) {
                var parts=key.split(":");
                if(parts[0].equals("c")&&!characters.containsKey(uuid(parts[1])) || parts[0].equals("l")&&!places.containsKey(uuid(parts[1])))
                    throw new IllegalArgumentException("Unknown story fact owner");
            }
            facts=immutable(facts);places=immutable(places);characters=immutable(characters);players=immutable(players);projections=Collections.unmodifiableSet(new LinkedHashSet<>(projections));
        }
        public static State empty(String scope) {return new State(scope,0,Map.of(),Map.of(),Map.of(),Map.of(),Set.of());}
        public boolean empty() {return facts.isEmpty()&&places.isEmpty()&&characters.isEmpty()&&players.isEmpty()&&projections.isEmpty();}
        public PlayerMemory player(UUID id) {return players.getOrDefault(id,PlayerMemory.EMPTY);}
        State update(Map<String,AbilityValue> f,Map<UUID,Place> p,Map<UUID,Character> c,Map<UUID,PlayerMemory> u) {
            return facts.equals(f)&&places.equals(p)&&characters.equals(c)&&players.equals(u)?this:new State(scope,Math.incrementExact(revision),f,p,c,u,projections);
        }
        public State withFacts(Map<String,AbilityValue> value) {return update(value,places,characters,players);}
        public State withPlace(Place value) {var next=new LinkedHashMap<>(places);next.put(value.instance,value);return update(facts,next,characters,players);}
        public State withCharacter(Character value) {var next=new LinkedHashMap<>(characters);next.put(value.anchor,value);return update(facts,places,next,players);}
        public State withPlayer(UUID id,PlayerMemory value) {var next=new LinkedHashMap<>(players);next.put(id,value);return update(facts,places,characters,next);}
        public State withProjection(ProjectionReceipt receipt) {
            if(projections.contains(receipt))return this;
            var next=new LinkedHashSet<>(projections);next.add(receipt);
            return new State(scope,Math.incrementExact(revision),facts,places,characters,players,next);
        }
    }
    public static final Codec<StorySavedData> CODEC=State.CODEC.xmap(StorySavedData::new,StorySavedData::state);
    public static final SavedDataType<StorySavedData> TYPE=new SavedDataType<>(Identifier.fromNamespaceAndPath("worldsmith","story_state"),
        () -> {throw new IllegalStateException("Story storage requires scoped creation");},CODEC,DataFixTypes.SAVED_DATA_COMMAND_STORAGE);
    private State state;
    public StorySavedData(State state) {this.state=Objects.requireNonNull(state);}
    public State state() {return state;}
    public static StorySavedData load(MinecraftServer server,String scope) {
        return load(server.overworld().getDataStorage(),dataRoot(server.getWorldPath(LevelResource.ROOT)),scope);
    }
    static Path dataRoot(Path worldRoot) {return DimensionType.getStorageFolder(Level.OVERWORLD,worldRoot).resolve("data");}
    static StorySavedData load(SavedDataStorage storage,Path dataRoot,String scope) {
        var path=TYPE.id().withSuffix(".dat").resolveAgainst(dataRoot);
        boolean absent=Files.notExists(path,LinkOption.NOFOLLOW_LINKS);var data=storage.get(TYPE);
        if(data==null) {
            if(!absent)throw new IllegalStateException("Existing story history failed to load; original file preserved: "+path);
            data=new StorySavedData(State.empty(scope));storage.set(TYPE,data);
        }
        if(!data.state.scope.equals(scope)) {
            if(!data.state.empty())throw new IllegalStateException("Story history belongs to another immutable world");
            data.state=State.empty(scope);data.setDirty();
        }
        return data;
    }
    public void replace(State expected,State replacement) {
        if(state!=expected || !state.scope.equals(replacement.scope))throw new IllegalStateException("Story transaction became stale");
        if(expected!=replacement) {state=replacement;setDirty();}
    }
    private static <K,V> Map<K,V> immutable(Map<K,V> values) {return Collections.unmodifiableMap(new LinkedHashMap<>(values));}
    static UUID uuid(String value) {var id=UUID.fromString(value);if(!id.toString().equals(value))throw new IllegalArgumentException("Expected canonical UUID");return id;}
    static String id(String value) {if(value==null || !value.matches("[a-z0-9][a-z0-9_.-]{0,63}"))throw new IllegalArgumentException("Invalid story id");return value;}
    private static void location(String dimension,BlockPos position) {
        if(dimension.length()>256||!Identifier.parse(dimension).toString().equals(dimension))throw new IllegalArgumentException("Expected canonical story dimension");
        if(Math.abs((long)position.getX())>30_000_000||Math.abs((long)position.getZ())>30_000_000||position.getY() < -2048||position.getY()>2048)
            throw new IllegalArgumentException("Story location exceeds world position bounds");
    }
    static AbilityValue primitive(AbilityValue value) {
        if(!(value instanceof AbilityValue.BoolValue || value instanceof AbilityValue.NumberValue number && Double.isFinite(number.getValue()) || value instanceof AbilityValue.TextValue text && text.getValue().length()<=4096))
            throw new IllegalArgumentException("Story values must be bounded primitive values");
        return value;
    }
    static String address(String key) {
        String[] parts=key.split(":",-1);
        if(parts.length==2 && parts[0].equals("w")){id(parts[1]);return key;}
        if(parts.length==3 && Set.of("p","c","l").contains(parts[0])){uuid(parts[1]);id(parts[2]);return key;}
        throw new IllegalArgumentException("Invalid story fact address");
    }
    static <T> Codec<T> strict(Codec<T> delegate) {
        return new Codec<>() {
            @Override public <R> DataResult<Pair<T,R>> decode(DynamicOps<R> ops,R input) {
                try {var result=delegate.decode(ops,input);return result.error().isPresent()?DataResult.error(() -> result.error().orElseThrow().message()):result;}
                catch(RuntimeException invalid){return DataResult.error(() -> "Invalid story state: "+invalid.getMessage());}
            }
            @Override public <R> DataResult<R> encode(T value,DynamicOps<R> ops,R prefix){return delegate.encode(value,ops,prefix);}
        };
    }
}

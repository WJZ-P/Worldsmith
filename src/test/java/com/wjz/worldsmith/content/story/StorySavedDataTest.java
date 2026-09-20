package com.wjz.worldsmith.content.story;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import com.wjz.worldsmith.core.ability.*;
import com.wjz.worldsmith.core.story.*;
import java.util.*;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class StorySavedDataTest {
    private static final String SCOPE="a".repeat(64);
    private static final UUID PLACE=UUID.randomUUID(),ANCHOR=UUID.randomUUID(),ACTOR=UUID.randomUUID(),PLAYER=UUID.randomUUID();
    private static StorySavedData.Place place() {return new StorySavedData.Place(PLACE,"village","minecraft:overworld",new BlockPos(13,64,-27),1);}
    private static StorySavedData.Character character() {return new StorySavedData.Character(ANCHOR,"keeper",PLACE,"minecraft:overworld",new BlockPos(10,64,-25),1,ACTOR,true,false,0);}
    private static StorySavedData.State populated() {
        return StorySavedData.State.empty(SCOPE).withPlace(place()).withCharacter(character())
            .withPlayer(PLAYER,new StorySavedData.PlayerMemory(Set.of(PLACE),Set.of("history"),Map.of("key",new StorySavedData.TradeUse(1,500))))
            .withFacts(Map.of("w:lit",AbilityValues.bool(true),"p:"+PLAYER+":heard",AbilityValues.bool(true),
                "c:"+ANCHOR+":visits",AbilityValues.number(2),"l:"+PLACE+":name",AbilityValues.text("归灯村")));
    }
    private static StoryLibrary library() {
        return new StoryLibrary(2,List.of(new StoryFact("lit",StoryFactScope.WORLD,StoryFactType.BOOL,AbilityValues.bool(false)),
                new StoryFact("heard",StoryFactScope.PLAYER,StoryFactType.BOOL,AbilityValues.bool(false)),
                new StoryFact("visits",StoryFactScope.CHARACTER,StoryFactType.NUMBER,AbilityValues.number(0),0,10),
                new StoryFact("name",StoryFactScope.PLACE,StoryFactType.TEXT,AbilityValues.text(""))),
            List.of(new StoryPlace("village","Village","Home","village_structure")),
            List.of(new StoryCharacter("keeper","Keeper","resident","village")),List.of(),
            List.of(new StoryKnowledge("history","History","Old lights",StoryTruth.FACT,StoryCondition.Always.INSTANCE)),
            List.of(new StoryTrade("key","Key",List.of(),List.of(new StoryItemAmount("minecraft:copper_ingot")),StoryCondition.Always.INSTANCE,List.of(),20,2)),List.of());
    }
    @Test void completeLedgerRoundTripsWithStableInstancesAndDeathHistory() {
        var state=populated().withCharacter(character().died(1600));
        var data=new StorySavedData(state);
        var json=StorySavedData.CODEC.encodeStart(JsonOps.INSTANCE,data).getOrThrow();
        var restored=StorySavedData.CODEC.parse(JsonOps.INSTANCE,json).getOrThrow();
        assertEquals(state,restored.state());
        assertEquals(ACTOR,restored.state().characters().get(ANCHOR).actor());
        assertEquals(1600,restored.state().characters().get(ANCHOR).availableAt());
        assertTrue(restored.state().characters().get(ANCHOR).dead());
        assertDoesNotThrow(() -> WorldStoryRuntime.validateStored(library(),restored.state()));
        assertFalse(json.toString().contains("token"));assertFalse(json.toString().contains("programCounter"));
    }
    @Test void corruptMemberRejectsEntireLedgerWithoutPartialRecovery() {
        var json=StorySavedData.CODEC.encodeStart(JsonOps.INSTANCE,new StorySavedData(populated())).getOrThrow().getAsJsonObject();
        json.getAsJsonObject("facts").addProperty("w:broken","{\"v\":{\"type\":\"list\",\"values\":[]}}");
        var result=StorySavedData.CODEC.parse(JsonOps.INSTANCE,json);
        assertTrue(result.error().isPresent());assertTrue(result.resultOrPartial(message -> {}).isEmpty());
    }
    @Test void duplicateDiscoveriesAndNoncanonicalIdentitiesRejectWholeDecode() {
        var json=StorySavedData.CODEC.encodeStart(JsonOps.INSTANCE,new StorySavedData(populated())).getOrThrow().getAsJsonObject();
        json.getAsJsonObject("players").getAsJsonObject(PLAYER.toString()).getAsJsonArray("places").add(PLACE.toString());
        var result=StorySavedData.CODEC.parse(JsonOps.INSTANCE,json);
        assertTrue(result.error().isPresent());assertTrue(result.resultOrPartial(message -> {}).isEmpty());
        assertTrue(StorySavedData.UUID_CODEC.parse(JsonOps.INSTANCE,JsonParser.parseString("\"1-1-1-1-1\"")).error().isPresent());
    }
    @Test void immutableSnapshotsDetachAllMutableInputsAndPositions() {
        var pos=new BlockPos.MutableBlockPos(13,64,-27);
        var p=new StorySavedData.Place(PLACE,"village","minecraft:overworld",pos,1);pos.set(0,0,0);
        assertEquals(place(),p);
        var facts=new LinkedHashMap<String,AbilityValue>();facts.put("w:lit",AbilityValues.bool(true));
        var discoveries=new LinkedHashSet<UUID>();discoveries.add(PLACE);
        var memory=new StorySavedData.PlayerMemory(discoveries,Set.of(),Map.of());discoveries.clear();
        var state=StorySavedData.State.empty(SCOPE).withPlace(p).withPlayer(PLAYER,memory).withFacts(facts);facts.clear();
        assertEquals(1,state.facts().size());assertEquals(Set.of(PLACE),state.player(PLAYER).places());
        assertThrows(UnsupportedOperationException.class,() -> state.facts().clear());
        assertThrows(UnsupportedOperationException.class,() -> state.player(PLAYER).places().clear());
        assertSame(state,state.withFacts(state.facts()));
    }
    @Test void boundedPrimitiveAndAggregateCapacityAreCheckedBeforePublication() {
        assertThrows(IllegalArgumentException.class,() -> StorySavedData.primitive(AbilityValues.list(List.of())));
        assertThrows(IllegalArgumentException.class,() -> StorySavedData.primitive(AbilityValues.text("x".repeat(4097))));
        var facts=new LinkedHashMap<String,AbilityValue>();
        for(int i=0;i<750;i++)facts.put("w:fact_"+i,AbilityValues.text("灯".repeat(4000)));
        var empty=StorySavedData.State.empty(SCOPE);
        assertThrows(IllegalArgumentException.class,() -> empty.withFacts(facts));assertEquals(0,empty.revision());
        var knowledge=new LinkedHashSet<String>();for(int i=0;i<129;i++)knowledge.add("fact_"+i);
        assertThrows(IllegalArgumentException.class,() -> new StorySavedData.PlayerMemory(Set.of(),knowledge,Map.of()));
    }
    @Test void orphanOwnersCrossDimensionHomesAndDuplicateActorOwnershipAreRejected() {
        var state=populated();
        assertThrows(IllegalArgumentException.class,() -> state.withFacts(Map.of("c:"+UUID.randomUUID()+":visits",AbilityValues.number(1))));
        assertThrows(IllegalArgumentException.class,() -> state.withFacts(Map.of("l:"+UUID.randomUUID()+":name",AbilityValues.text("Missing"))));
        assertThrows(IllegalArgumentException.class,() -> state.withCharacter(new StorySavedData.Character(ANCHOR,"keeper",PLACE,"minecraft:the_nether",BlockPos.ZERO,0,ACTOR,true,false,0)));
        assertThrows(IllegalArgumentException.class,() -> state.withCharacter(new StorySavedData.Character(UUID.randomUUID(),"keeper",PLACE,"minecraft:overworld",BlockPos.ZERO,0,ACTOR,true,false,0)));
        assertThrows(IllegalArgumentException.class,() -> state.withPlayer(PLAYER,new StorySavedData.PlayerMemory(Set.of(UUID.randomUUID()),Set.of(),Map.of())));
    }
    @Test void invalidLocationsAndImpossibleLifecycleStatesFailAtTheStorageBoundary() {
        assertThrows(IllegalArgumentException.class,() -> new StorySavedData.Place(PLACE,"village","overworld",BlockPos.ZERO,0));
        assertThrows(IllegalArgumentException.class,() -> new StorySavedData.Place(PLACE,"village","minecraft:overworld",new BlockPos(0,4096,0),0));
        assertThrows(IllegalArgumentException.class,() -> new StorySavedData.Place(PLACE,"village","minecraft:overworld",new BlockPos(Integer.MIN_VALUE,0,0),0));
        assertThrows(IllegalArgumentException.class,() -> new StorySavedData.Character(ANCHOR,"keeper",PLACE,"minecraft:overworld",BlockPos.ZERO,0,ACTOR,false,true,50));
        assertThrows(IllegalArgumentException.class,() -> new StorySavedData.Character(ANCHOR,"keeper",PLACE,"minecraft:overworld",BlockPos.ZERO,0,ACTOR,true,true,0));
        assertThrows(IllegalArgumentException.class,() -> new StorySavedData.Character(ANCHOR,"keeper",PLACE,"minecraft:overworld",BlockPos.ZERO,0,ACTOR,true,false,50));
        var json=StorySavedData.CODEC.encodeStart(JsonOps.INSTANCE,new StorySavedData(populated())).getOrThrow().getAsJsonObject();
        json.getAsJsonObject("characters").getAsJsonObject(ANCHOR.toString()).addProperty("availableAt",50);
        var result=StorySavedData.CODEC.parse(JsonOps.INSTANCE,json);
        assertTrue(result.error().isPresent());assertTrue(result.resultOrPartial(message -> {}).isEmpty());
    }
    @Test void storedFactScopeTypeRangeAndContentReferencesMatchTheBoundLibrary() {
        var state=populated();
        assertThrows(IllegalStateException.class,() -> WorldStoryRuntime.validateStored(library(),state.withFacts(Map.of("p:"+PLAYER+":lit",AbilityValues.bool(true)))));
        assertThrows(IllegalStateException.class,() -> WorldStoryRuntime.validateStored(library(),state.withFacts(Map.of("c:"+ANCHOR+":visits",AbilityValues.number(11)))));
        assertThrows(IllegalStateException.class,() -> WorldStoryRuntime.validateStored(library(),state.withFacts(Map.of("w:lit",AbilityValues.text("not bool")))));
        assertThrows(IllegalStateException.class,() -> WorldStoryRuntime.validateStored(library(),state.withPlayer(PLAYER,new StorySavedData.PlayerMemory(Set.of(),Set.of("unknown"),Map.of()))));
        assertThrows(IllegalStateException.class,() -> WorldStoryRuntime.validateStored(library(),state.withPlayer(PLAYER,new StorySavedData.PlayerMemory(Set.of(),Set.of(),Map.of("unknown",new StorySavedData.TradeUse(1,0))))));
        assertThrows(IllegalStateException.class,() -> WorldStoryRuntime.validateStored(library(),state.withPlayer(PLAYER,new StorySavedData.PlayerMemory(Set.of(),Set.of(),Map.of("key",new StorySavedData.TradeUse(3,0))))));
        var wrongHome=state.withPlace(new StorySavedData.Place(UUID.randomUUID(),"unknown","minecraft:overworld",BlockPos.ZERO,0));
        assertThrows(IllegalStateException.class,() -> WorldStoryRuntime.validateStored(library(),wrongHome));
    }
    @Test void optimisticReplacementRejectsStaleForeignAndEqualButDistinctExpectedStates() {
        var before=populated();var data=new StorySavedData(before);var after=before.withFacts(Map.of("w:lit",AbilityValues.bool(true)));
        var equalCopy=new StorySavedData.State(before.scope(),before.revision(),before.facts(),before.places(),before.characters(),before.players(),before.projections());
        assertThrows(IllegalStateException.class,() -> data.replace(equalCopy,after));
        assertThrows(IllegalStateException.class,() -> data.replace(before,StorySavedData.State.empty("b".repeat(64))));
        data.replace(before,after);assertSame(after,data.state());
        assertThrows(IllegalStateException.class,() -> data.replace(before,before));
        data.replace(after,before);assertSame(before,data.state());
    }
    @Test void projectionReceiptsAreOncePerActualPlaceAndMalformedHistoryNeverPartiallyLoads() {
        var receipt=new StorySavedData.ProjectionReceipt(PLACE,"lamp");var before=populated();var state=before.withProjection(receipt);
        assertSame(state,state.withProjection(receipt));assertEquals(before.revision()+1,state.revision());
        assertThrows(UnsupportedOperationException.class,() -> state.projections().clear());
        assertThrows(IllegalArgumentException.class,() -> before.withProjection(new StorySavedData.ProjectionReceipt(UUID.randomUUID(),"lamp")));
        var json=StorySavedData.CODEC.encodeStart(JsonOps.INSTANCE,new StorySavedData(state)).getOrThrow().getAsJsonObject();
        assertEquals(state,StorySavedData.CODEC.parse(JsonOps.INSTANCE,json).getOrThrow().state());
        assertThrows(IllegalStateException.class,() -> WorldStoryRuntime.validateStored(library(),state));
        json.getAsJsonArray("projections").add(json.getAsJsonArray("projections").get(0).deepCopy());
        var invalid=StorySavedData.CODEC.parse(JsonOps.INSTANCE,json);assertTrue(invalid.error().isPresent());assertTrue(invalid.resultOrPartial(ignored -> {}).isEmpty());
    }
}

package com.wjz.worldsmith.ability;

import com.mojang.serialization.JsonOps;
import com.wjz.worldsmith.core.ability.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AbilityActorResourcesTest {
    static final String SCOPE="a".repeat(64);
    private AbilityActorResources.State state() { return new AbilityActorResources.State(SCOPE,AbilityData.Values.EMPTY,
        Map.of("mana",new AbilityActorResources.Pool(100,40,1,10),"focus",new AbilityActorResources.Pool(20,5,0,10))); }
    @Test void lazyRegenerationCapsAndNeverReplaysRewoundTime() {
        var pool=new AbilityActorResources.Pool(100,40,1,10); assertEquals(45,pool.at(15).value()); assertEquals(100,pool.at(1000).value());
        assertSame(pool,pool.at(5)); var spent=pool.at(20).with(0,20); assertSame(spent,spent.at(10)); assertEquals(1,spent.at(21).value());
    }
    @Test void failedPaymentIsAtomicAcrossEveryPool() {
        var old=state(); assertNull(AbilityActorResources.planPayment(old,Map.of("mana",20.0,"focus",6.0),20));
        assertEquals(40,old.pool("mana").value()); assertEquals(5,old.pool("focus").value()); assertEquals(10,old.pool("mana").updatedAt());
    }
    @Test void paymentUsesOneTimestampAndReturnsAnImmutableReplacement() {
        var old=state(); var paid=AbilityActorResources.planPayment(old,Map.of("mana",45.0,"focus",5.0),20);
        assertNotNull(paid); assertEquals(5,paid.pool("mana").value()); assertEquals(0,paid.pool("focus").value()); assertEquals(20,paid.pool("mana").updatedAt());
        assertEquals(40,old.pool("mana").value()); assertThrows(UnsupportedOperationException.class,() -> paid.pools().clear());
    }
    @Test void invalidCostsNeverProduceAnUpdate() {
        for(double invalid:new double[]{-1,Double.NaN,Double.POSITIVE_INFINITY,1e7}) assertThrows(IllegalArgumentException.class,() -> AbilityActorResources.planPayment(state(),Map.of("mana",invalid),20));
        assertThrows(IllegalArgumentException.class,() -> AbilityActorResources.planPayment(state(),Map.of("missing",1.0),20));
        assertThrows(IllegalArgumentException.class,() -> AbilityActorResources.planPayment(state(),Map.of(),20));
    }
    @Test void sharedStateCopiesValuesDeletesNullAndRejectsNestedEntityHandles() {
        var map=new LinkedHashMap<String,AbilityValue>(); map.put("x",AbilityValues.number(1)); var values=new AbilityData.Values(map); map.clear();
        assertEquals(AbilityValues.number(1),values.get("x")); assertSame(values,values.with("x",AbilityValues.number(1)));
        assertTrue(values.with("x",AbilityValues.none()).empty()); assertThrows(IllegalArgumentException.class,() -> values.with("entity",AbilityValues.list(List.of(AbilityValues.entity(UUID.randomUUID().toString())))));
    }
    @Test void poolAndSharedLimitsFailBeforePublication() {
        var pools=new LinkedHashMap<String,AbilityActorResources.Pool>(); for(int i=0;i<32;i++) pools.put("p"+i,new AbilityActorResources.Pool(1,1,0,0));
        var old=new AbilityActorResources.State(SCOPE,AbilityData.Values.EMPTY,pools);
        assertThrows(IllegalArgumentException.class,() -> old.pool("too_many",new AbilityActorResources.Pool(1,1,0,0)));
        var values=AbilityData.Values.EMPTY; for(int i=0;i<3;i++) values=values.with("text"+i,AbilityValues.text("x".repeat(4000)));
        var full=values; assertThrows(IllegalArgumentException.class,() -> full.with("overflow",AbilityValues.text("é".repeat(4000))));
    }
    @Test void completeActorStateRoundTripsAndBadPoolHasNoPartialFallback() {
        var original=state(); var encoded=AbilityActorResources.State.CODEC.encodeStart(JsonOps.INSTANCE,original).getOrThrow();
        var copy=AbilityActorResources.State.CODEC.parse(JsonOps.INSTANCE,encoded).getOrThrow(); assertEquals(original.pools(),copy.pools()); assertEquals(original.scope(),copy.scope());
        encoded.getAsJsonObject().getAsJsonObject("pools").getAsJsonObject("mana").addProperty("value",-1);
        var invalid=AbilityActorResources.State.CODEC.parse(JsonOps.INSTANCE,encoded); assertTrue(invalid.error().isPresent()); assertTrue(invalid.resultOrPartial(x -> {}).isEmpty());
    }
    @Test void sceneStateCapsAndKeysAreValidated() {
        assertTrue(AbilitySceneSavedData.validKey("encounter@123")); assertFalse(AbilitySceneSavedData.validKey("encounter@0123"));
        assertThrows(IllegalArgumentException.class,() -> new AbilitySceneSavedData.State(SCOPE,Map.of("bad",AbilityData.Values.EMPTY)));
        var scenes=new HashMap<String,AbilityData.Values>(); for(int i=0;i<2049;i++) scenes.put("scene@"+i,AbilityData.Values.EMPTY);
        assertThrows(IllegalArgumentException.class,() -> new AbilitySceneSavedData.State(SCOPE,scenes));
    }
}

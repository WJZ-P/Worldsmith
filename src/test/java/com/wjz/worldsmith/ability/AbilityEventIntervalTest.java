package com.wjz.worldsmith.ability;

import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AbilityEventIntervalTest {
    private static final UUID ACTOR=UUID.fromString("00000000-0000-0000-0000-000000000013");
    @Test void oneTickInputStillArrivesOnEveryNativeTick() {
        for(long tick=-50;tick<100;tick++)assertTrue(AbilityEventRuntime.tickDue(ACTOR,"observe",tick,1));
    }
    @Test void periodicObservationRunsExactlyOncePerPeriodAndContinuesPastInvocationLifetime() {
        for(int cycle=0;cycle<1000;cycle++) {
            int count=0;
            for(int tick=cycle*40;tick<(cycle+1)*40;tick++)if(AbilityEventRuntime.tickDue(ACTOR,"observe",tick,40))count++;
            assertEquals(1,count);
        }
    }
    @Test void actorsAreDeterministicallyStaggeredRatherThanSharingOneGlobalFrame() {
        int[] bins=new int[32];
        for(int id=0;id<512;id++) {
            UUID actor=new UUID(0,id);
            for(int tick=0;tick<32;tick++)if(AbilityEventRuntime.tickDue(actor,"observe",tick,32))bins[tick]++;
        }
        assertEquals(512,IntStream.of(bins).sum());
        for(int value:bins)assertEquals(16,value);
        for(long tick:new long[]{Long.MIN_VALUE,-1,0,1,Long.MAX_VALUE})
            assertEquals(AbilityEventRuntime.tickDue(ACTOR,"observe",tick,31),AbilityEventRuntime.tickDue(UUID.fromString(ACTOR.toString()),"observe",tick,31));
    }
    @Test void invalidPeriodsFailWithoutAllocatingTimers() {
        assertThrows(IllegalArgumentException.class,() -> AbilityEventRuntime.tickDue(ACTOR,"observe",0,0));
        assertThrows(IllegalArgumentException.class,() -> AbilityEventRuntime.tickDue(ACTOR,"observe",0,1201));
    }
}

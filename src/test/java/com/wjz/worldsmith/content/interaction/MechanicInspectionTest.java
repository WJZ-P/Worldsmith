package com.wjz.worldsmith.content.interaction;

import static com.wjz.worldsmith.content.interaction.MechanicInspection.Code.*;
import static org.junit.jupiter.api.Assertions.*;

import com.wjz.worldsmith.core.content.*;
import com.wjz.worldsmith.core.serialization.WorldsmithJson;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class MechanicInspectionTest {
    private static final String SCOPE = "a".repeat(64);
    @BeforeAll static void bootstrap() { MechanicTestBootstrap.initialize(); }

    private static WorldMechanicDefinition gate() {
        return WorldsmithJson.INSTANCE.getFormat().decodeFromString(WorldMechanicLibrary.Companion.serializer(), """
            {"mechanics":[{"id":"gate","displayName":"Gate","rules":[{"id":"open","event":"USE_BLOCK",
              "pattern":[{"offset":{},"block":{"block":"minecraft:stone"}}],
              "heldItem":{"item":"minecraft:emerald","count":2},
              "actions":[{"kind":"set_block","offset":{},"block":{"block":"minecraft:air"}}]}]}]}
            """).getMechanics().getFirst();
    }
    private static WorldMechanicSavedData data(Map<String, WorldMechanicSavedData.Progress> entries) {
        return new WorldMechanicSavedData(new WorldMechanicSavedData.State(1, SCOPE, 0, entries));
    }
    private static WorldMechanicRule repeatRule(WorldMechanicDefinition definition) {
        var old = definition.getRules().getFirst();
        return new WorldMechanicRule("trade", old.getEvent(), old.getPattern(), List.of(new MechanicAction.GiveItem("minecraft:diamond")),
            "idle", "idle", false, old.getHeldItem(), 20, List.of());
    }

    @Test void observationIsBoundedBeforeProtocolOrUiUse() {
        assertThrows(IllegalArgumentException.class, () -> new MechanicInspection(READY, "../bad", 0, 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new MechanicInspection(READY, "", -1, 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new MechanicInspection(READY, "", 129, 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new MechanicInspection(READY, "", 0, 65, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new MechanicInspection(READY, "", 0, 0, 65, 0));
        assertThrows(IllegalArgumentException.class, () -> new MechanicInspection(READY, "", 0, 0, 0, 72001));
        assertThrows(NullPointerException.class, () -> new MechanicInspection(null, "", 0, 0, 0, 0));
        assertEquals(READY, MechanicInspection.of(READY).code());
    }

    @Test void translationsCarryDistinctStableArguments() {
        var pattern = (TranslatableContents)new MechanicInspection(INCOMPLETE_PATTERN, "open", 3, 2, 0, 0).message().getContents();
        assertEquals("worldsmith.mechanics.inspection.incomplete_pattern", pattern.getKey());
        assertArrayEquals(new Object[] {3}, pattern.getArgs());
        var cost = (TranslatableContents)new MechanicInspection(INSUFFICIENT_ITEMS, "open", 0, 2, 1, 0).message().getContents();
        assertArrayEquals(new Object[] {2, 1}, cost.getArgs());
        var cooldown = (TranslatableContents)new MechanicInspection(COOLDOWN, "open", 0, 0, 0, 21).message().getContents();
        assertArrayEquals(new Object[] {2L}, cooldown.getArgs());
        for (var code : MechanicInspection.Code.values())
            assertEquals("worldsmith.mechanics.inspection." + code.name().toLowerCase(java.util.Locale.ROOT),
                ((TranslatableContents)MechanicInspection.of(code).message().getContents()).getKey());
    }

    @Test void repeatedUnknownAnchorReadsNeverAllocateLedgerEntriesOrDirtySavedData() {
        var definition = gate(); var rule = definition.getRules().getFirst(); var ledger = data(Map.of());
        var before = ledger.state();
        assertFalse(ledger.isDirty());
        for (int i = 0; i < 1000; i++) {
            var result = WorldMechanicRuntime.inspectState(ledger, "gate@" + i, definition, rule, 10);
            assertEquals(READY, result.code());
            assertEquals(2, result.requiredItems());
        }
        assertSame(before, ledger.state()); assertTrue(ledger.state().instances().isEmpty()); assertFalse(ledger.isDirty());
    }

    @Test void spentAndCooldownAreDifferentAndAgreeWithLedgerEligibility() {
        var definition = gate(); var once = definition.getRules().getFirst();
        var spent = data(Map.of("gate@1", new WorldMechanicSavedData.Progress("active", 50, 1)));
        assertEquals(SPENT, WorldMechanicRuntime.inspectState(spent, "gate@1", definition, once, 20).code());
        assertFalse(spent.eligible("gate@1", definition, once, 20));
        var repeat = repeatRule(definition);
        var cooling = data(Map.of("gate@1", new WorldMechanicSavedData.Progress("idle", 50, 1)));
        var before = cooling.state();
        var result = WorldMechanicRuntime.inspectState(cooling, "gate@1", definition, repeat, 20);
        assertEquals(COOLDOWN, result.code()); assertEquals(30, result.cooldownTicks());
        assertFalse(cooling.eligible("gate@1", definition, repeat, 20));
        assertEquals(READY, WorldMechanicRuntime.inspectState(cooling, "gate@1", definition, repeat, 50).code());
        assertTrue(cooling.eligible("gate@1", definition, repeat, 50));
        assertSame(before, cooling.state()); assertFalse(cooling.isDirty());
    }

    @Test void capacityAppliesOnlyToNewAnchorsAndQuarantineWinsEveryOtherReason() {
        var definition = gate(); var rule = repeatRule(definition);
        var entries = new LinkedHashMap<String, WorldMechanicSavedData.Progress>();
        for (int i = 0; i < WorldMechanicSavedData.MAX_INSTANCES; i++)
            entries.put("gate@" + i, new WorldMechanicSavedData.Progress("idle", 1, 1));
        var ledger = data(entries); var before = ledger.state();
        assertEquals(INSTANCE_LIMIT, WorldMechanicRuntime.inspectState(ledger, "gate@99999", definition, rule, 100).code());
        assertEquals(READY, WorldMechanicRuntime.inspectState(ledger, "gate@1", definition, rule, 100).code());
        assertSame(before, ledger.state()); assertFalse(ledger.isDirty());
        var quarantined = new WorldMechanicSavedData(new WorldMechanicSavedData.State(1, SCOPE, 0, entries, true));
        assertEquals(QUARANTINED, WorldMechanicRuntime.inspectState(quarantined, "gate@99999", definition, rule, 100).code());
        assertFalse(quarantined.isDirty());
    }

    @Test void negativeClockAndUnboundLedgerAreUnavailableWithoutBindingThem() {
        var definition = gate(); var rule = definition.getRules().getFirst();
        var unbound = new WorldMechanicSavedData(); var before = unbound.state();
        assertEquals(UNAVAILABLE, WorldMechanicRuntime.inspectState(unbound, "gate@1", definition, rule, 100).code());
        assertSame(before, unbound.state()); assertFalse(unbound.isDirty());
        assertEquals(UNAVAILABLE, WorldMechanicRuntime.inspectState(data(Map.of()), "gate@1", definition, rule, -1).code());
    }

    @Test void transitionCounterOverflowIsDetectedWithoutPlanningOrDirtyingTheLedger() {
        var definition = gate(); var rule = repeatRule(definition);
        var fullRevision = new WorldMechanicSavedData(new WorldMechanicSavedData.State(1, SCOPE, Long.MAX_VALUE, Map.of()));
        assertEquals(UNAVAILABLE, WorldMechanicRuntime.inspectState(fullRevision, "gate@1", definition, rule, 1).code());
        var fullActivations = data(Map.of("gate@1", new WorldMechanicSavedData.Progress("idle", 0, Long.MAX_VALUE)));
        assertEquals(UNAVAILABLE, WorldMechanicRuntime.inspectState(fullActivations, "gate@1", definition, rule, 1).code());
        assertEquals(UNAVAILABLE, WorldMechanicRuntime.inspectState(data(Map.of()), "gate@1", definition, rule, Long.MAX_VALUE).code());
        assertFalse(fullRevision.isDirty()); assertFalse(fullActivations.isDirty());
    }

    @Test void bestCandidatePreservesReadyRouteAndProvidesClosestActionableFailure() {
        var missing5 = new MechanicInspection(INCOMPLETE_PATTERN, "left", 5, 0, 0, 0);
        var missing1 = new MechanicInspection(INCOMPLETE_PATTERN, "right", 1, 0, 0, 0);
        assertSame(missing1, WorldMechanicRuntime.prefer(missing5, missing1));
        assertSame(missing1, WorldMechanicRuntime.prefer(MechanicInspection.of(SPENT), missing1));
        var bulk = new MechanicInspection(INSUFFICIENT_ITEMS, "bulk", 0, 64, 1, 0);
        var small = new MechanicInspection(INSUFFICIENT_ITEMS, "small", 0, 2, 1, 0);
        assertSame(small, WorldMechanicRuntime.prefer(bulk, small));
        var ready = new MechanicInspection(READY, "small", 0, 2, 2, 0);
        assertSame(ready, WorldMechanicRuntime.prefer(bulk, ready));
        assertSame(ready, WorldMechanicRuntime.prefer(ready, MechanicInspection.of(NO_SPACE)));
    }

    @Test void nativeBodyCollisionDetectsSolidLiquidAndProtrudingFenceWithoutEntities() {
        AABB body = new AABB(.2, 1, .2, .8, 2, .8);
        assertFalse(WorldMechanicRuntime.hasBodyObstruction(blocks(Map.of(BlockPos.ZERO, Blocks.STONE.defaultBlockState())), body));
        assertTrue(WorldMechanicRuntime.hasBodyObstruction(blocks(Map.of(new BlockPos(0, 1, 0), Blocks.STONE.defaultBlockState())), body));
        assertTrue(WorldMechanicRuntime.hasBodyObstruction(blocks(Map.of(new BlockPos(0, 1, 0), Blocks.WATER.defaultBlockState())), body));
        assertTrue(WorldMechanicRuntime.hasBodyObstruction(blocks(Map.of(BlockPos.ZERO, Blocks.OAK_FENCE.defaultBlockState())), body));
    }

    @Test void provisionalConsumedCellIsAirButUnchangedWallsStillObstruct() {
        BlockPos anchor = new BlockPos(0, 1, 0); AABB body = new AABB(.2, 1, .2, .8, 2, .8);
        var original = Map.of(anchor, Blocks.GOLD_BLOCK.defaultBlockState());
        assertTrue(WorldMechanicRuntime.hasBodyObstruction(blocks(original), body));
        var proposed = new LinkedHashMap<>(original); proposed.put(anchor, Blocks.AIR.defaultBlockState());
        assertFalse(WorldMechanicRuntime.hasBodyObstruction(blocks(proposed), body));
        assertEquals(Blocks.GOLD_BLOCK.defaultBlockState(), original.get(anchor));
    }

    @Test void largestValidatedCreatureHasABoundedLocalCollisionFootprint() {
        AtomicInteger reads = new AtomicInteger();
        BlockGetter empty = new BlockGetter() {
            public BlockState getBlockState(BlockPos pos) { reads.incrementAndGet(); return Blocks.AIR.defaultBlockState(); }
            public FluidState getFluidState(BlockPos pos) { return getBlockState(pos).getFluidState(); }
            public BlockEntity getBlockEntity(BlockPos pos) { throw new AssertionError("Inspection must not instantiate block entities"); }
            public int getHeight() { return 384; }
            public int getMinY() { return -64; }
        };
        assertFalse(WorldMechanicRuntime.hasBodyObstruction(empty, new AABB(-1.5, 0, -1.5, 2.5, 6, 2.5)));
        assertTrue(reads.get() <= 512, "A single body check should stay inside its bounded local area: " + reads);
    }

    private static BlockGetter blocks(Map<BlockPos, BlockState> states) {
        return new BlockGetter() {
            public BlockState getBlockState(BlockPos pos) { return states.getOrDefault(pos, Blocks.AIR.defaultBlockState()); }
            public FluidState getFluidState(BlockPos pos) { return getBlockState(pos).getFluidState(); }
            public BlockEntity getBlockEntity(BlockPos pos) { throw new AssertionError("Inspection must not instantiate block entities"); }
            public int getHeight() { return 384; }
            public int getMinY() { return -64; }
        };
    }
}

package com.wjz.worldsmith.content.interaction;

import com.wjz.worldsmith.content.WorldBlockBindings;
import com.wjz.worldsmith.content.creature.CreatureRuntime;
import com.wjz.worldsmith.content.item.CustomItemRuntime;
import com.wjz.worldsmith.core.content.*;
import com.wjz.worldsmith.core.model.WorldsmithPack;
import com.wjz.worldsmith.core.pack.WorldContentBundleIO;
import com.wjz.worldsmith.core.pack.WorldsmithPackLoader;
import com.wjz.worldsmith.core.serialization.WorldsmithJson;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WorldMechanicRuntimeTest {
    @BeforeAll static void bootstrap() { MechanicTestBootstrap.initialize(); }

    private static WorldMechanicLibrary mechanics(String document) {
        return WorldsmithJson.INSTANCE.getFormat().decodeFromString(WorldMechanicLibrary.Companion.serializer(), document);
    }
    private static WorldsmithPack pack(WorldMechanicLibrary library) {
        var base = WorldsmithPackLoader.loadClasspath("worldsmith/packs/ashlands");
        return WorldContentBundleIO.create("Mechanic tests", "Native mechanic preparation", base.getTerrain(), base.getBiomes(), base.getFeatures(),
            base.getStructures(), base.getTheme(), base.getBlocks(), base.getCreatures(), base.getAssets(), base.getItems(), base.getQuests(), null, library);
    }
    private static WorldMechanicRuntime.Snapshot prepare(WorldMechanicLibrary library) {
        var pack = pack(library); String scope = pack.getManifest().getId();
        var blocks = WorldBlockBindings.resolver(CustomBlockBindings.plan(scope, pack.getBlocks()));
        var items = CustomItemRuntime.prepare(scope, pack.getItems());
        Map<String, String> biomes = new LinkedHashMap<>();
        pack.getBiomes().getBiomes().forEach(b -> biomes.put(b.getId(), "worldsmith:generated/" + scope + "/" + b.getId()));
        var creatures = CreatureRuntime.prepare(scope, pack.getCreatures(), biomes, items, blocks);
        return WorldMechanicRuntime.prepare(pack, blocks, items, creatures);
    }
    private static String converter(String anchor, String output) {
        return """
            {"mechanics":[{"id":"converter","displayName":"Converter","rules":[{"id":"exchange","event":"USE_BLOCK",
              "pattern":[{"offset":{},"block":{"block":"%s"}}],"heldItem":{"item":"minecraft:emerald","count":2},
              "fromState":"idle","toState":"idle","actions":[{"kind":"give_item","item":"%s"}]}]}]}
            """.formatted(anchor, output);
    }

    @Test void resolvesAndRotatesNativePartialProperties() {
        var predicate = WorldMechanicRuntime.resolve(new MechanicBlockPredicate("minecraft:oak_stairs", Map.of("facing", "north")), null);
        var east = Blocks.OAK_STAIRS.defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.EAST);
        assertTrue(predicate.matches(east, Rotation.COUNTERCLOCKWISE_90));
        assertFalse(predicate.matches(east, Rotation.NONE));
        // Unspecified half/waterlogged values do not accidentally become part of the predicate.
        assertTrue(predicate.matches(east.setValue(BlockStateProperties.WATERLOGGED, true), Rotation.COUNTERCLOCKWISE_90));
        assertEquals(new BlockPos(-3, 2, 1), WorldMechanicRuntime.rotate(new MechanicOffset(1, 2, 3), 1));
        assertEquals(new BlockPos(3, 2, -1), WorldMechanicRuntime.rotate(new MechanicOffset(1, 2, 3), 3));
    }

    @Test void directionalPropertyNamesRotateThroughNativeBlockStateSemantics() {
        var predicate = WorldMechanicRuntime.resolve(new MechanicBlockPredicate("minecraft:oak_fence", Map.of("north", "true", "east", "false")), null);
        var fence = Blocks.OAK_FENCE.defaultBlockState().setValue(BlockStateProperties.EAST, true).setValue(BlockStateProperties.SOUTH, false);
        assertTrue(predicate.matches(fence, Rotation.COUNTERCLOCKWISE_90));
        assertFalse(predicate.matches(fence, Rotation.NONE));
    }

    @Test void nativeIdsAndPropertyValuesAreCheckedBeforePublication() {
        assertThrows(IllegalArgumentException.class, () -> WorldMechanicRuntime.resolve(new MechanicBlockPredicate("minecraft:missing_block"), null));
        assertThrows(IllegalArgumentException.class, () -> WorldMechanicRuntime.resolve(new MechanicBlockPredicate("minecraft:stone", Map.of("facing", "north")), null));
        assertThrows(IllegalArgumentException.class, () -> WorldMechanicRuntime.resolve(new MechanicBlockPredicate("minecraft:oak_stairs", Map.of("facing", "up")), null));
        assertThrows(IllegalArgumentException.class, () -> prepare(mechanics(converter("minecraft:stone", "minecraft:missing_item"))));
        assertThrows(IllegalArgumentException.class, () -> prepare(mechanics(converter("minecraft:stone", "worldsmith:content/item/host"))));
    }

    @Test void eventIndexesAreSeparateAndEveryPlaceablePatternCellIsIndexed() {
        var library = mechanics("""
            {"mechanics":[{"id":"gate","displayName":"Gate","rules":[{"id":"assemble","event":"BLOCK_PLACED",
              "pattern":[{"offset":{},"block":{"block":"minecraft:gold_block"}},
                         {"offset":{"x":1},"block":{"block":"minecraft:stone"}}],
              "actions":[{"kind":"set_block","offset":{},"block":{"block":"minecraft:air"}}]}]}]}
            """);
        var prepared = prepare(library);
        assertEquals(4, prepared.placementCandidateCount(Blocks.GOLD_BLOCK));
        assertEquals(4, prepared.placementCandidateCount(Blocks.STONE));
        assertEquals(0, prepared.useCandidateCount(Blocks.GOLD_BLOCK));
        var use = prepare(mechanics(converter("minecraft:gold_block", "minecraft:diamond")));
        assertEquals(4, use.useCandidateCount(Blocks.GOLD_BLOCK));
        assertEquals(0, use.placementCandidateCount(Blocks.GOLD_BLOCK));
        assertThrows(UnsupportedOperationException.class, () -> prepared.definitions().clear());
    }

    @Test void blockEntitiesAreNotAValidConsumeOrWriteTarget() {
        var library = mechanics("""
            {"mechanics":[{"id":"gate","displayName":"Gate","rules":[{"id":"open","event":"USE_BLOCK",
              "pattern":[{"offset":{},"block":{"block":"minecraft:chest"}}],
              "actions":[{"kind":"set_block","offset":{},"block":{"block":"minecraft:air"}}]}]}]}
            """);
        assertThrows(IllegalArgumentException.class, () -> prepare(library));
        assertThrows(IllegalArgumentException.class, () -> prepare(mechanics("""
            {"mechanics":[{"id":"gate","displayName":"Gate","rules":[{"id":"open","event":"USE_BLOCK",
              "pattern":[{"offset":{},"block":{"block":"minecraft:stone"}}],
              "actions":[{"kind":"set_block","offset":{},"block":{"block":"minecraft:chest"}}]}]}]}
            """)));
    }

    @Test void overlappingIndexOverBudgetIsRejectedInsteadOfStarvingLateRules() {
        var original = mechanics(converter("minecraft:gold_block", "minecraft:diamond")).getMechanics().getFirst();
        var definitions = new java.util.ArrayList<WorldMechanicDefinition>();
        for (int m = 0; m < 9; m++) {
            var rules = new java.util.ArrayList<WorldMechanicRule>();
            var rule = original.getRules().getFirst();
            for (int r = 0; r < 16; r++) rules.add(new WorldMechanicRule("rule_" + r, rule.getEvent(), rule.getPattern(), rule.getActions(),
                rule.getFromState(), rule.getToState(), true, rule.getHeldItem(), rule.getCooldownTicks(), List.of()));
            definitions.add(new WorldMechanicDefinition("device_" + m, "Device", "idle", List.of("idle", "active"), rules, ""));
        }
        var library = new WorldMechanicLibrary(1, definitions);
        assertTrue(WorldMechanicValidation.validate(library).isEmpty());
        var failure = assertThrows(IllegalArgumentException.class, () -> prepare(library));
        assertTrue(failure.getMessage().contains("per-event budget"), failure.getMessage());
    }
}

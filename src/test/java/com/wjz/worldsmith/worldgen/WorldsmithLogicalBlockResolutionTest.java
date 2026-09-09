package com.wjz.worldsmith.worldgen;

import com.wjz.worldsmith.content.WorldBlockBindings;
import com.wjz.worldsmith.core.content.CustomBlockBindings;
import com.wjz.worldsmith.core.content.CustomBlockDefinition;
import com.wjz.worldsmith.core.content.CustomBlockLibrary;
import com.wjz.worldsmith.core.content.CustomBlockProfile;
import com.wjz.worldsmith.core.model.MaterialSelector;
import com.wjz.worldsmith.core.model.WorldsmithPack;
import com.wjz.worldsmith.core.structure.BuildMaterial;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

final class WorldsmithLogicalBlockResolutionTest {
    @BeforeAll static void bootstrap() { WorldsmithTestBootstrap.bootStrap(); }
    private static MaterialSelector material(String id, List<String> tags) { return new MaterialSelector("test", List.of(id), tags, List.of()); }
    private static WorldBlockBindings.Resolver resolver() {
        var library = new CustomBlockLibrary(1, List.of(new CustomBlockDefinition("moon", "Moon", CustomBlockProfile.STONE, "a".repeat(64), 7, "")));
        return WorldBlockBindings.resolver(CustomBlockBindings.plan("realm", library));
    }

    @Test void customReferencesNeverBecomeOrdinaryMissingMaterialFallbacks() {
        assertThrows(IllegalArgumentException.class, () -> new MaterialResolver().resolve(material("worldsmith:content/moon", List.of()), Blocks.STONE));
        assertThrows(IllegalArgumentException.class, () -> new MaterialResolver(resolver()).resolve(material("worldsmith:content/missing", List.of()), Blocks.STONE));
        assertThrows(IllegalArgumentException.class, () -> new MaterialResolver(resolver()).resolve(material("worldsmith:content/moon[light=0]", List.of()), Blocks.STONE));
        assertThrows(IllegalArgumentException.class, () -> new MaterialResolver(resolver()).resolve(material("worldsmith:content/block/stone/00", List.of()), Blocks.STONE));
    }

    @Test void requiredTagsAndStructureStateOverridesAreExplicitlyRejectedBeforeReload() {
        assertThrows(IllegalArgumentException.class, () -> new MaterialResolver(resolver()).resolve(material("worldsmith:content/moon", List.of("minecraft:mineable/pickaxe")), Blocks.STONE));
        assertThrows(IllegalArgumentException.class, () -> WorldsmithStructureTemplates.resolve(new BuildMaterial("worldsmith:content/moon", Map.of("light", "15")), resolver()));
        assertThrows(IllegalArgumentException.class, () -> WorldsmithStructureTemplates.resolve(new BuildMaterial("worldsmith:content/block/stone/00", Map.of()), resolver()));
        assertEquals(Blocks.STONE.defaultBlockState(), WorldsmithStructureTemplates.resolve(new BuildMaterial("minecraft:stone", Map.of()), resolver()));
    }

    @Test void nativePreflightKeepsErrorsAndDoesNotExposeInternalHostsAsAuthoringVocabulary() {
        var host = new WorldsmithAuthoringNativeHost(resolver());
        assertNotEquals(new WorldsmithAuthoringNativeHost().getIdentity(), host.getIdentity());
        String result = host.query(List.of("worldsmith:content/missing"), "", 1).toString();
        assertTrue(result.contains("error"));
        assertTrue(host.query(List.of(), "content/block/", 64).toString().contains("\"matched\":0"));
    }

    @Test void compiledPackKeepsItsOwnImmutablePlanAndCanRestoreNonLexicalSavedSlots() {
        var activeBefore=WorldBlockBindings.active();
        var base = WorldsmithPacks.builtin();
        var zinc = new CustomBlockDefinition("zinc", "Zinc", CustomBlockProfile.STONE, "a".repeat(64), 0, "");
        var amber = new CustomBlockDefinition("amber", "Amber", CustomBlockProfile.STONE, "b".repeat(64), 0, "");
        var initialLibrary = new CustomBlockLibrary(1, List.of(zinc));
        var initial = CompiledPack.scoped(new WorldsmithPack(base.getManifest(),base.getTerrain(),base.getBiomes(),base.getFeatures(),base.getComputedId(),base.getStructures(),base.getTheme(),initialLibrary,base.getCreatures(),base.getAssets()));
        var nextLibrary = new CustomBlockLibrary(1, List.of(amber,zinc));
        var nextPack = new WorldsmithPack(base.getManifest(),base.getTerrain(),base.getBiomes(),base.getFeatures(),base.getComputedId(),base.getStructures(),base.getTheme(),nextLibrary,base.getCreatures(),base.getAssets());
        var restored = CompiledPack.scoped(nextPack,initial.blockBindings());
        assertEquals(initial.blockResolver().nativeIds().get("worldsmith:content/zinc"),restored.blockResolver().nativeIds().get("worldsmith:content/zinc"));
        assertNotEquals(CompiledPack.scoped(nextPack).blockBindings(),restored.blockBindings());
        assertThrows(UnsupportedOperationException.class,()->restored.blockBindings().getBindings().clear());
        assertSame(activeBefore,WorldBlockBindings.active());
    }
}

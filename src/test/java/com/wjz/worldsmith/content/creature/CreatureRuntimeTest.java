package com.wjz.worldsmith.content.creature;

import com.wjz.worldsmith.core.content.*;
import com.wjz.worldsmith.core.serialization.WorldsmithJson;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CreatureRuntimeTest {
    private static final String HASH = "a".repeat(64);
    @BeforeAll static void bootstrapNativeAttributes() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }
    private static CreatureLibrary library() {
        return WorldsmithJson.INSTANCE.getFormat().decodeFromString(CreatureLibrary.Companion.serializer(), """
            {"creatures":[
              {"id":"stag","displayName":"Moon Stag","category":"PASSIVE","model":{"texture":"%s","bones":[{"id":"body","cubes":[{"origin":{"x":0,"y":0,"z":0},"size":{"x":8,"y":8,"z":8}}]}]},"spawn":{"biomes":["moonwood"],"weight":20,"minLight":8,"maxLight":15}},
              {"id":"guardian","displayName":"Stone Guardian","category":"HOSTILE","model":{"texture":"%s","bones":[{"id":"body","cubes":[{"origin":{"x":0,"y":0,"z":0},"size":{"x":8,"y":8,"z":8}}]}]},"spawn":{"biomes":["moonwood"],"weight":5,"maxLight":7}}
            ]}
            """.formatted(HASH, HASH));
    }

    @AfterEach void clear() { CreatureRuntime.clearClient(); CreatureRuntime.clearServer(); }

    @Test void preparingNeverActivatesClientDefinitions() {
        var prepared = CreatureRuntime.prepare(HASH, library(), Map.of("moonwood", "worldsmith:moonwood"));
        assertNull(CreatureRuntime.clientSnapshot());
        CreatureRuntime.activateClient(prepared);
        assertSame(prepared, CreatureRuntime.clientSnapshot());
        CreatureRuntime.clearClient(); assertNull(CreatureRuntime.clientSnapshot());
    }

    @Test void selectionRequiresExactBiomeBindingCategoryAndLightRange() {
        var prepared = CreatureRuntime.prepare(HASH, library(), Map.of("moonwood", "worldsmith:moonwood"));
        assertEquals(1, prepared.candidates("worldsmith:moonwood", com.wjz.worldsmith.core.content.CreatureCategory.PASSIVE, 10).size());
        assertTrue(prepared.candidates("worldsmith:moonwood", com.wjz.worldsmith.core.content.CreatureCategory.HOSTILE, 10).isEmpty());
        assertTrue(prepared.candidates("worldsmith:other", com.wjz.worldsmith.core.content.CreatureCategory.PASSIVE, 10).isEmpty());
        assertTrue(CreatureRuntime.prepare(HASH, library()).candidates("worldsmith:moonwood", com.wjz.worldsmith.core.content.CreatureCategory.PASSIVE, 10).isEmpty());
    }

    @Test void nativeSpawnEntriesUseOnlyCategoryHostsWithAggregatedWeights() {
        var prepared = CreatureRuntime.prepare(HASH, library(), Map.of("moonwood", "worldsmith:moonwood"));
        var entries = CreatureRuntime.perBiomeSpawnEntries(prepared, "worldsmith:moonwood");
        assertEquals(2, entries.size());
        assertEquals(CreatureRuntime.PASSIVE_ID, entries.getFirst().entityType());
        assertEquals(20, entries.getFirst().weight());
        assertEquals(CreatureRuntime.HOSTILE_ID, entries.getLast().entityType());
        assertEquals(5, entries.getLast().weight());
        assertTrue(CreatureRuntime.perBiomeSpawnEntries(prepared, "worldsmith:unbound").isEmpty());
    }

    @Test void snapshotIdentityAndBindingsAreValidatedBeforePublication() {
        assertThrows(IllegalArgumentException.class, () -> CreatureRuntime.prepare("mutable-draft", library()));
        assertThrows(RuntimeException.class, () -> CreatureRuntime.prepare(HASH, library(), Map.of("moonwood", "INVALID BIOME")));
        var prepared = CreatureRuntime.prepare(HASH, library());
        assertThrows(UnsupportedOperationException.class, () -> prepared.definitions().clear());
        assertThrows(UnsupportedOperationException.class, () -> prepared.biomeBindings().put("x", "worldsmith:y"));
        var definition = prepared.definitions().get("stag");
        assertThrows(UnsupportedOperationException.class, () -> definition.getModel().getBones().clear());
        assertThrows(UnsupportedOperationException.class, () -> definition.getModel().getBones().getFirst().getCubes().clear());
        assertThrows(UnsupportedOperationException.class, () -> definition.getSpawn().getBiomes().clear());
    }

    private static CreatureLibrary singleCreature(CreatureAttributes attributes, CreatureBossProfile boss) {
        var original = library().getCreatures().getLast();
        var definition = new CreatureDefinition(original.getId(), original.getDisplayName(), original.getCategory(), original.getModel(),
            attributes, original.getBehavior(), new CreatureSpawn(List.of(), 1, 1, 1, 0, 15), original.getThemeRole(), List.of(), boss);
        return new CreatureLibrary(boss == null ? 1 : 2, List.of(definition));
    }

    @Test void legacyCoreHealthLimitRemainsReadableButNativePublicationRejectsClamping() {
        var source = singleCreature(new CreatureAttributes(2048, .25, 24, 3, 0, .8F, 1.4F), null);
        assertTrue(CustomCreatureValidator.validate(source).isEmpty(), "Do not change the legacy Core contract or hash domain");
        String before = WorldsmithJson.INSTANCE.getFormat().encodeToString(CreatureLibrary.Companion.serializer(), source);
        var failure = assertThrows(IllegalArgumentException.class, () -> CreatureRuntime.prepare(HASH, source));
        assertTrue(failure.getMessage().contains("creature.native_attribute_out_of_range"));
        assertTrue(failure.getMessage().contains("attributes.health"));
        assertTrue(failure.getMessage().contains("2048.0"));
        assertTrue(failure.getMessage().contains("1024.0"));
        assertEquals(before, WorldsmithJson.INSTANCE.getFormat().encodeToString(CreatureLibrary.Companion.serializer(), source));
        assertNull(CreatureRuntime.clientSnapshot());
    }

    @Test void nativeAuditReportsEveryRequestedAttributeRatherThanOnlyHealth() {
        // Exercise the native boundary independently of the stricter portable authoring limits.
        var source = singleCreature(new CreatureAttributes(2048, 1025, 2049, 2049, 2, .8F, 1.4F), null);
        var diagnostics = CreatureRuntime.nativeAttributeDiagnostics(source);
        assertEquals(Set.of("health", "speed", "followRange", "attackDamage", "knockbackResistance"),
            diagnostics.stream().map(d -> d.getPath().substring(d.getPath().lastIndexOf('.') + 1)).collect(Collectors.toSet()));
        assertEquals(5, diagnostics.size());
    }

    @Test void nativeAuditChecksAllBossPhaseDerivedValues() {
        var boss = new CreatureBossProfile(List.of(new CreatureBossPhase("First", 1.0, 1, 1, 12, 20, 1),
            new CreatureBossPhase("Last", .5, 3, 3, 8, 16, 1.2F)));
        var source = singleCreature(new CreatureAttributes(1024, 512, 64, 1024, 1, .8F, 1.4F), boss);
        var diagnostics = CreatureRuntime.nativeAttributeDiagnostics(source);
        assertEquals(Set.of("creatures.creatures[0].boss.phases[1].speedMultiplier", "creatures.creatures[0].boss.phases[1].damageMultiplier"),
            diagnostics.stream().map(d -> d.getPath()).collect(Collectors.toSet()));
    }

    @Test void nativeBoundaryAcceptsValidBossAndKeepsExactRequestedValues() {
        var boss = new CreatureBossProfile(List.of(new CreatureBossPhase("First", 1.0),
            new CreatureBossPhase("Last", .5, 2, 2, 8, 16, 1.2F)));
        var attributes = new CreatureAttributes(1024, .5, 64, 50, 1, 4, 6);
        var prepared = CreatureRuntime.prepare(HASH, singleCreature(attributes, boss));
        var definition = prepared.definitions().get("guardian");
        assertEquals(attributes, definition.getAttributes());
        assertEquals(boss, definition.getBoss());
        assertTrue(CreatureRuntime.nativeAttributeDiagnostics(new CreatureLibrary(2, List.of(definition))).isEmpty());
    }
}

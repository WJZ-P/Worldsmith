package com.wjz.worldsmith.content.creature;

import com.wjz.worldsmith.core.content.CreatureLibrary;
import com.wjz.worldsmith.core.serialization.WorldsmithJson;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CreatureRuntimeTest {
    private static final String HASH = "a".repeat(64);
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
}

package com.wjz.worldsmith.client;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** UI metadata objects are not native generation presets; these tests do not open Minecraft screens. */
class WorldsmithWorldTypeMenuTest {
    @Test void everyPackHasADistinctCycleButtonValueAndName() {
        var oath=WorldsmithWorldTypeMenu.deferredChoice("a".repeat(64),"Oathfire");
        var provinces=WorldsmithWorldTypeMenu.deferredChoice("b".repeat(64),"Nine Provinces");
        assertNotEquals(oath,provinces);
        assertEquals("Oathfire",WorldsmithWorldTypeMenu.name(oath.preset()).getString());
        assertEquals("Nine Provinces",WorldsmithWorldTypeMenu.name(provinces.preset()).getString());
    }
    @Test void browsingDoesNotRequireNativeDimensionsAndNeverLeaksPlaceholderDimensions() {
        var choice=WorldsmithWorldTypeMenu.deferredChoice("a".repeat(64),"Oathfire");
        assertTrue(choice.preset().unwrapKey().isEmpty());
        assertTrue(choice.preset().value().overworld().isEmpty());
        assertThrows(IllegalStateException.class,()->choice.preset().value().createWorldDimensions());
        assertNull(WorldsmithWorldTypeMenu.managedId(choice.preset()));
        assertFalse(WorldsmithWorldTypeMenu.isLegacyPreset(choice.preset()));
    }
    @Test void invalidPackChoicesFailBeforeTheyCanEnterTheMenu() {
        assertThrows(IllegalArgumentException.class,()->WorldsmithWorldTypeMenu.deferredChoice("worldsmith:wasteland","Old"));
        assertThrows(IllegalArgumentException.class,()->WorldsmithWorldTypeMenu.deferredChoice("a".repeat(64),""));
    }
}

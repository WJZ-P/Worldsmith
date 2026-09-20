package com.wjz.worldsmith.ability;

import com.wjz.worldsmith.content.WorldContentRuntime;
import com.wjz.worldsmith.core.ability.*;
import com.wjz.worldsmith.core.model.WorldsmithPack;
import com.wjz.worldsmith.core.pack.WorldContentBundleIO;
import com.wjz.worldsmith.core.pack.WorldsmithPackLoader;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AbilityNativePreparationTest {
    private static final AbilityCapabilitySpec EXTENSION = new AbilityCapabilitySpec("fixture.native_note", 1,
        List.of(AbilityType.TEXT), AbilityType.BOOL, true, "Installed test capability");

    @BeforeAll static void bootstrap() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
        WorldAbilityRuntime.registerCapability(EXTENSION, (context, arguments) -> AbilityValues.bool(true));
    }

    private static WorldsmithPack pack(String source) {
        var base = WorldsmithPackLoader.loadClasspath("worldsmith/packs/ashlands");
        return WorldContentBundleIO.create("Preparation", "Native capability snapshot", base.getTerrain(), base.getBiomes(),
            base.getFeatures(), base.getStructures(), base.getTheme(), base.getBlocks(), base.getCreatures(), base.getAssets(),
            base.getItems(), base.getQuests(), null, base.getMechanics(),
            new AbilityLibrary(1, List.of(new AbilityProgramDefinition("script", "Script", source))));
    }

    private static WorldContentRuntime.Prepared prepare(WorldsmithPack pack) {
        var biomes = new LinkedHashMap<String, String>();
        pack.getBiomes().getBiomes().forEach(b -> biomes.put(b.getId(), "worldsmith:generated/" + pack.getComputedId() + "/" + b.getId()));
        return WorldContentRuntime.prepare(pack, biomes);
    }

    @Test void installedExtensionCompilesAndPublishesInTheNativeSnapshot() {
        var prepared = prepare(pack("on start { state.noted = fixture.native_note(\"hello\"); wait 2; }"));
        assertEquals(EXTENSION, WorldAbilityRuntime.capabilities().lookup(EXTENSION.getName()));
        assertEquals(1, prepared.abilities().programs().get("script").getUsedCapabilities().get(EXTENSION.getName()));
        assertEquals("Script", prepared.abilities().programName("script"));
        assertTrue(prepared.serverResources().containsKey(WorldContentRuntime.EMBEDDED_ROOT + "abilities/abilities.json"));
        assertNull(WorldAbilityRuntime.clientSnapshot());
        assertThrows(UnsupportedOperationException.class, () -> prepared.abilities().programs().clear());
    }

    @Test void absentProviderFailsBeforeAnyNativePublication() {
        assertThrows(IllegalArgumentException.class, () -> prepare(pack("on start { fixture.uninstalled(1); }")));
        assertNull(WorldContentRuntime.activeScope());
        assertNull(WorldAbilityRuntime.clientSnapshot());
    }

    @Test void duplicateOrBuiltInReplacementIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> WorldAbilityRuntime.registerCapability(EXTENSION,
            (context, arguments) -> AbilityValues.bool(false)));
        assertThrows(IllegalArgumentException.class, () -> WorldAbilityRuntime.registerCapability(
            AbilityCapabilities.standard().lookup("math.abs"), (context, arguments) -> AbilityValues.number(1)));
    }

    @Test void nativeClientLeaseOwnsTheAbilitySnapshotAndClearsIt() {
        var prepared = prepare(pack("on start { wait 2; }"));
        var client = WorldContentRuntime.beginClientTransition(prepared, null).commit();
        try { assertSame(prepared.abilities(), WorldAbilityRuntime.clientSnapshot()); }
        finally { WorldContentRuntime.beginClientTransition(null, client).commit(); }
        assertNull(WorldAbilityRuntime.clientSnapshot());
    }
}

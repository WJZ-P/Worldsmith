package com.wjz.worldsmith.content.interaction;

import com.wjz.worldsmith.core.content.WorldMechanicDefinition;
import com.wjz.worldsmith.core.content.WorldMechanicLibrary;
import com.wjz.worldsmith.core.serialization.WorldsmithJson;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.world.level.storage.SavedDataStorage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class WorldMechanicStorageTest {
    @BeforeAll static void bootstrap() { net.minecraft.SharedConstants.tryDetectVersion(); net.minecraft.server.Bootstrap.bootStrap(); }
    @TempDir Path temp;
    private static final String HASH = "c".repeat(64);
    private static WorldMechanicDefinition definition() {
        return WorldsmithJson.INSTANCE.getFormat().decodeFromString(WorldMechanicLibrary.Companion.serializer(), """
            {"mechanics":[{"id":"gate","displayName":"Gate","rules":[{"id":"open","event":"USE_BLOCK",
              "pattern":[{"offset":{},"block":{"block":"minecraft:stone"}}],
              "actions":[{"kind":"set_block","offset":{},"block":{"block":"minecraft:air"}}]}]}]}
            """).getMechanics().getFirst();
    }
    private SavedDataStorage storage() { return new SavedDataStorage(temp, DataFixers.getDataFixer(), RegistryAccess.EMPTY); }
    private Path file() { return WorldMechanicSavedData.TYPE.id().withSuffix(".dat").resolveAgainst(temp); }

    @Test void actualNativeStorageReopensSpentAnchorAndCooldown() throws Exception {
        var definition = definition(); var definitions = Map.of("gate", definition);
        try (var storage = storage()) {
            var ledger = WorldMechanicSavedData.load(storage, temp, HASH, definitions);
            ledger.plan("gate@123", definition, definition.getRules().getFirst(), 100).commit();
        }
        assertTrue(Files.size(file()) > 0);
        try (var reopened = storage()) {
            var restored = WorldMechanicSavedData.load(reopened, temp, HASH, definitions);
            assertEquals(new WorldMechanicSavedData.Progress("active", 120, 1), restored.progress("gate@123"));
            assertFalse(restored.eligible("gate@123", definition, definition.getRules().getFirst(), 10000));
        }
    }

    @Test void corruptExistingLedgerIsNotReplacedOnLoadOrClose() throws Exception {
        Files.createDirectories(file().getParent()); byte[] original = {42, 19, 0, 91, 3}; Files.write(file(), original);
        try (var storage = storage()) {
            var failure = assertThrows(IllegalStateException.class,
                () -> WorldMechanicSavedData.load(storage, temp, HASH, Map.of("gate", definition())));
            assertTrue(failure.getMessage().contains("original ledger is preserved"));
        }
        assertArrayEquals(original, Files.readAllBytes(file()));
    }

    @Test void unknownSchemaInValidNbtIsAlsoPreservedInsteadOfResettingOneShotState() throws Exception {
        var ledger = new WorldMechanicSavedData(); ledger.bind(HASH, Map.of("gate", definition()));
        var data = (CompoundTag)WorldMechanicSavedData.CODEC.encodeStart(NbtOps.INSTANCE, ledger).getOrThrow();
        data.putInt("schemaVersion", 99);
        CompoundTag root = new CompoundTag(); root.put("data", data); NbtUtils.addCurrentDataVersion(root);
        Files.createDirectories(file().getParent()); NbtIo.writeCompressed(root, file()); byte[] original = Files.readAllBytes(file());
        try (var storage = storage()) {
            assertThrows(IllegalStateException.class, () -> WorldMechanicSavedData.load(storage, temp, HASH, Map.of("gate", definition())));
        }
        assertArrayEquals(original, Files.readAllBytes(file()));
    }

    @Test void foreignScopeIsPreservedEvenWhenItParsesSuccessfully() throws Exception {
        try (var storage = storage()) { WorldMechanicSavedData.load(storage, temp, HASH, Map.of("gate", definition())); }
        byte[] original = Files.readAllBytes(file());
        try (var storage = storage()) {
            assertThrows(IllegalStateException.class, () -> WorldMechanicSavedData.load(storage, temp, "d".repeat(64), Map.of("gate", definition())));
        }
        assertArrayEquals(original, Files.readAllBytes(file()));
    }

    @Test void oneMalformedEntryRejectsTheWholeLedgerIncludingNativePartialFallback() throws Exception {
        var ledger = new WorldMechanicSavedData(); var definition = definition(); ledger.bind(HASH, Map.of("gate", definition));
        ledger.plan("gate@1", definition, definition.getRules().getFirst(), 0).commit();
        var data = (CompoundTag)WorldMechanicSavedData.CODEC.encodeStart(NbtOps.INSTANCE, ledger).getOrThrow();
        var entries = (CompoundTag)data.get("instances");
        var broken = new CompoundTag(); broken.putString("state", "active"); broken.putLong("readyAt", 20);
        entries.put("gate@2", broken); // Missing activations; DFU's map codec normally keeps gate@1 as partial success.
        var result = WorldMechanicSavedData.CODEC.parse(NbtOps.INSTANCE, data);
        assertTrue(result.error().isPresent());
        assertTrue(result.resultOrPartial(ignored -> {}).isEmpty(), "Native storage must not accept a map with a spent entry silently removed");
        var root = new CompoundTag(); root.put("data", data); NbtUtils.addCurrentDataVersion(root);
        Files.createDirectories(file().getParent()); NbtIo.writeCompressed(root, file()); byte[] original = Files.readAllBytes(file());
        try (var storage = storage()) {
            assertThrows(IllegalStateException.class, () -> WorldMechanicSavedData.load(storage, temp, HASH, Map.of("gate", definition)));
        }
        assertArrayEquals(original, Files.readAllBytes(file()));
    }
}

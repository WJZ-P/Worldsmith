package com.wjz.worldsmith.content.story;

import com.wjz.worldsmith.core.ability.AbilityValues;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.storage.SavedDataStorage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Real native SavedDataStorage and compressed files under the actual 26.2 overworld directory. */
class StoryStorageTest {
    private static final String SCOPE="c".repeat(64);
    private static final UUID PLACE=UUID.fromString("00000000-0000-0000-0000-000000000101");
    private static final UUID ANCHOR=UUID.fromString("00000000-0000-0000-0000-000000000102");
    private static final UUID ACTOR=UUID.fromString("00000000-0000-0000-0000-000000000103");
    private static final UUID PLAYER=UUID.fromString("00000000-0000-0000-0000-000000000104");
    @BeforeAll static void bootstrap(){net.minecraft.SharedConstants.tryDetectVersion();net.minecraft.server.Bootstrap.bootStrap();}
    @TempDir Path worldRoot;

    private Path dataRoot(){return StorySavedData.dataRoot(worldRoot);}
    private Path file(){return StorySavedData.TYPE.id().withSuffix(".dat").resolveAgainst(dataRoot());}
    private Path obsoleteFile(){return StorySavedData.TYPE.id().withSuffix(".dat").resolveAgainst(worldRoot.resolve("data"));}
    private SavedDataStorage storage(){return new SavedDataStorage(dataRoot(),DataFixers.getDataFixer(),RegistryAccess.EMPTY);}
    private static StorySavedData.State populated() {
        var place=new StorySavedData.Place(PLACE,"village","minecraft:overworld",new BlockPos(18,64,-23),3);
        var resident=new StorySavedData.Character(ANCHOR,"keeper",PLACE,"minecraft:overworld",new BlockPos(21,64,-20),3,ACTOR,true,true,780);
        return StorySavedData.State.empty(SCOPE).withPlace(place).withCharacter(resident)
            .withPlayer(PLAYER,new StorySavedData.PlayerMemory(Set.of(PLACE),Set.of("old_bridge"),Map.of("bread",new StorySavedData.TradeUse(2,640))))
            .withFacts(Map.of("w:bridge_fixed",AbilityValues.bool(true),"p:"+PLAYER+":trust",AbilityValues.number(4),
                "c:"+ANCHOR+":visits",AbilityValues.number(2),"l:"+PLACE+":name",AbilityValues.text("归灯村")))
            .withProjection(new StorySavedData.ProjectionReceipt(PLACE,"bridge_repaired"));
    }
    private void save(StorySavedData.State state) throws Exception {
        try(var storage=storage()) {
            var data=StorySavedData.load(storage,dataRoot(),SCOPE);
            data.replace(data.state(),state);
        }
    }
    private byte[] writeNbt(CompoundTag data) throws Exception {
        var root=new CompoundTag();root.put("data",data);NbtUtils.addCurrentDataVersion(root);
        Files.createDirectories(file().getParent());NbtIo.writeCompressed(root,file());return Files.readAllBytes(file());
    }

    @Test void actualOverworldDataRootIsTheDimensionDirectoryNotTheOldWorldRootDataPath() throws Exception {
        Path actual=DimensionType.getStorageFolder(Level.OVERWORLD,worldRoot).resolve("data");
        assertEquals(worldRoot.resolve("dimensions").resolve("minecraft").resolve("overworld").resolve("data"),actual);
        assertEquals(actual,dataRoot());assertNotEquals(worldRoot.resolve("data"),dataRoot());
        save(populated());
        assertTrue(Files.isRegularFile(file()));assertTrue(Files.size(file())>0);
        assertFalse(Files.exists(obsoleteFile()),"A legacy ROOT/data existence check would inspect a different file");
    }
    @Test void corruptExistingDimensionLedgerSurvivesBothNativeLoadFailureAndStorageClose() throws Exception {
        byte[] original={42,19,0,91,3,7};Files.createDirectories(file().getParent());Files.write(file(),original);
        assertFalse(Files.exists(obsoleteFile()),"Reproduce the old wrong-directory absence observation");
        try(var storage=storage()) {
            var failure=assertThrows(IllegalStateException.class,() -> StorySavedData.load(storage,dataRoot(),SCOPE));
            assertTrue(failure.getMessage().contains("preserved"),failure.getMessage());
        }
        assertArrayEquals(original,Files.readAllBytes(file()));assertFalse(Files.exists(obsoleteFile()));
    }
    @Test void oneMalformedFactInOtherwiseValidCompressedNbtDoesNotAcceptNativePartialRecovery() throws Exception {
        var data=(CompoundTag)StorySavedData.CODEC.encodeStart(NbtOps.INSTANCE,new StorySavedData(populated())).getOrThrow();
        var facts=(CompoundTag)data.get("facts");assertNotNull(facts);
        facts.put("w:broken",new CompoundTag()); // Valid NBT, wrong value type; other facts and identities remain valid.
        var decoded=StorySavedData.CODEC.parse(NbtOps.INSTANCE,data);
        assertTrue(decoded.error().isPresent());
        assertTrue(decoded.resultOrPartial(ignored -> {}).isEmpty(),"A DFU partial map must not silently discard a spent fact");
        byte[] original=writeNbt(data);
        assertFalse(Files.exists(obsoleteFile()));
        try(var storage=storage()) {assertThrows(IllegalStateException.class,() -> StorySavedData.load(storage,dataRoot(),SCOPE));}
        assertArrayEquals(original,Files.readAllBytes(file()));assertFalse(Files.exists(obsoleteFile()));
    }
    @Test void nonemptyForeignScopeIsPreservedAfterSuccessfulNativeDecodeAndRejectedBinding() throws Exception {
        save(populated());byte[] original=Files.readAllBytes(file());
        try(var storage=storage()) {
            assertThrows(IllegalStateException.class,() -> StorySavedData.load(storage,dataRoot(),"d".repeat(64)));
        }
        assertArrayEquals(original,Files.readAllBytes(file()));
        try(var storage=storage()) {assertEquals(populated(),StorySavedData.load(storage,dataRoot(),SCOPE).state());}
        assertArrayEquals(original,Files.readAllBytes(file()));
    }
    @Test void nativeSaveAndReopenRetainFactsDiscoveryDebtAndStableDeadResidentIdentity() throws Exception {
        var expected=populated();save(expected);byte[] original=Files.readAllBytes(file());
        try(var storage=storage()) {
            var restored=StorySavedData.load(storage,dataRoot(),SCOPE).state();assertEquals(expected,restored);
            assertEquals(AbilityValues.bool(true),restored.facts().get("w:bridge_fixed"));
            assertEquals(AbilityValues.text("归灯村"),restored.facts().get("l:"+PLACE+":name"));
            assertEquals(new BlockPos(18,64,-23),restored.places().get(PLACE).position());
            var resident=restored.characters().get(ANCHOR);
            assertEquals(ACTOR,resident.actor());assertEquals(PLACE,resident.place());assertEquals(3,resident.quarterTurns());
            assertTrue(resident.spawned());assertTrue(resident.dead());assertEquals(780,resident.availableAt());
            assertEquals(Set.of(PLACE),restored.player(PLAYER).places());assertEquals(Set.of("old_bridge"),restored.player(PLAYER).knowledge());
            assertEquals(new StorySavedData.TradeUse(2,640),restored.player(PLAYER).trades().get("bread"));
            assertEquals(Set.of(new StorySavedData.ProjectionReceipt(PLACE,"bridge_repaired")),restored.projections());
        }
        assertArrayEquals(original,Files.readAllBytes(file()),"Read-only reopen/close must not rewrite even valid history");
        assertFalse(Files.exists(obsoleteFile()));
    }
}

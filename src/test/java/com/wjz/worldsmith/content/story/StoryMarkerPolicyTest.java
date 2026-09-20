package com.wjz.worldsmith.content.story;

import com.mojang.serialization.JsonOps;
import com.wjz.worldsmith.core.story.StoryOffset;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class StoryMarkerPolicyTest {
    private static final String SCOPE="a".repeat(64);
    private static StorySavedData.Character character(int rotation,boolean spawned,boolean dead,long ready) {
        return new StorySavedData.Character(UUID.fromString("00000000-0000-0000-0000-000000000001"),"keeper",UUID.fromString("00000000-0000-0000-0000-000000000002"),
            "minecraft:overworld",new BlockPos(100,64,100),rotation,UUID.fromString("00000000-0000-0000-0000-000000000003"),spawned,dead,ready);
    }
    @Test void typedMarkerTagsRejectMissingAndConflictingIdentities() {
        var pure=StoryPlaces.identity(Set.of(StoryPlaces.MARKER,StoryPlaces.SCOPE+SCOPE,StoryPlaces.PLACE+"village"));
        assertNotNull(pure);assertEquals("village",pure.place());assertNull(pure.character());
        var resident=StoryPlaces.identity(Set.of(StoryPlaces.MARKER,StoryPlaces.SCOPE+SCOPE,StoryPlaces.PLACE+"village",StoryPlaces.CHARACTER+"keeper"));
        assertEquals("keeper",resident.character());
        assertNull(StoryPlaces.identity(Set.of(StoryPlaces.SCOPE+SCOPE,StoryPlaces.PLACE+"village")));
        assertNull(StoryPlaces.identity(Set.of(StoryPlaces.MARKER,StoryPlaces.SCOPE+SCOPE,StoryPlaces.PLACE+"village",StoryPlaces.PLACE+"other")));
        assertNull(StoryPlaces.identity(Set.of(StoryPlaces.MARKER,StoryPlaces.SCOPE+"not-a-hash",StoryPlaces.PLACE+"village")));
        assertNull(StoryPlaces.identity(Set.of(StoryPlaces.MARKER,StoryPlaces.SCOPE+SCOPE,StoryPlaces.PLACE+"village",StoryPlaces.CHARACTER+"..")));
    }
    @Test void persistedSpawnOwnershipDoesNotTreatAnUnloadedActorAsAnEmptySlot() {
        var live=character(0,true,false,0);
        assertFalse(StoryCharacters.spawnEligible(live,100000,true));
        assertFalse(StoryCharacters.spawnEligible(live,100000,false));
        assertTrue(StoryCharacters.spawnEligible(character(0,false,false,0),0,false));
        var dead=live.died(500);
        assertFalse(StoryCharacters.spawnEligible(dead,500,false));
        assertFalse(StoryCharacters.spawnEligible(dead,499,true));
        assertTrue(StoryCharacters.spawnEligible(dead,500,true));
        assertFalse(dead.respawned(UUID.randomUUID()).spawned());
    }
    @Test void routinesFollowWorldClockAndRotateTheirHomeRelativeOffsets() {
        assertTrue(StoryCharacters.inWindow(23999,23000,1000));
        assertTrue(StoryCharacters.inWindow(24000,23000,1000));
        assertTrue(StoryCharacters.inWindow(-1,23000,1000));
        assertFalse(StoryCharacters.inWindow(1000,23000,1000));
        assertFalse(StoryCharacters.inWindow(22000,23000,1000));
        assertTrue(StoryCharacters.inWindow(1200,1000,2000));
        assertFalse(StoryCharacters.inWindow(2000,1000,2000));
        assertEquals(new BlockPos(102,65,103),StoryCharacters.destination(character(0,true,false,0),new StoryOffset(2,1,3)));
        assertEquals(new BlockPos(97,65,102),StoryCharacters.destination(character(1,true,false,0),new StoryOffset(2,1,3)));
        assertEquals(new BlockPos(98,65,97),StoryCharacters.destination(character(2,true,false,0),new StoryOffset(2,1,3)));
        assertEquals(new BlockPos(103,65,98),StoryCharacters.destination(character(3,true,false,0),new StoryOffset(2,1,3)));
        assertEquals(3,StoryPlaces.quarterTurns(-90));assertEquals(1,StoryPlaces.quarterTurns(450));
        assertThrows(IllegalArgumentException.class,() -> StoryPlaces.quarterTurns(Float.NaN));
    }
    @Test void residentAttachmentStoresOnlyStableWorldAndAnchorIdentity() {
        var identity=new StoryCharacters.Identity(SCOPE,character(0,true,false,0).anchor());
        var json=StoryCharacters.Identity.CODEC.encodeStart(JsonOps.INSTANCE,identity).getOrThrow();
        assertEquals(identity,StoryCharacters.Identity.CODEC.parse(JsonOps.INSTANCE,json).getOrThrow());
        assertThrows(IllegalArgumentException.class,() -> new StoryCharacters.Identity("foreign",UUID.randomUUID()));
        assertEquals(identity.anchor(),character(0,true,false,0).died(400).anchor());
    }
}

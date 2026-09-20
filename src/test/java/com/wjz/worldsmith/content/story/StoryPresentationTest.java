package com.wjz.worldsmith.content.story;

import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class StoryPresentationTest {
    @Test void minecraftYawProducesPlayerRelativeDirections() {
        assertEquals(StoryNavigation.Bearing.AHEAD, StoryNavigation.bearing(0, 30, 0));
        assertEquals(StoryNavigation.Bearing.RIGHT, StoryNavigation.bearing(-30, 0, 0));
        assertEquals(StoryNavigation.Bearing.LEFT, StoryNavigation.bearing(30, 0, 0));
        assertEquals(StoryNavigation.Bearing.BEHIND, StoryNavigation.bearing(0, -30, 0));
        assertEquals(StoryNavigation.Bearing.AHEAD, StoryNavigation.bearing(-30, 0, 450));
        assertEquals(StoryNavigation.Bearing.ARRIVED, StoryNavigation.bearing(1, 1, 900));
    }
    @Test void distanceIncludesHeightAndNoInventedOrigin() {
        var place = new StoryProtocol.Place("tower", UUID.randomUUID(), "minecraft:overworld", 100, 70, 100, "Tower", "", "");
        assertEquals(10, StoryNavigation.distance(100.5, 60, 100.5, place));
        assertThrows(IllegalArgumentException.class, () -> StoryNavigation.bearing(Double.NaN, 0, 0));
    }
    @Test void musicHasOnePriorityWinnerAndAmbienceHasFourVoices() {
        var cues = new java.util.ArrayList<>(IntStream.range(0, 8).mapToObj(i -> StoryProtocolTest.cue("layer" + i, false, i)).toList());
        cues.add(StoryProtocolTest.cue("music_a", true, 5)); cues.add(StoryProtocolTest.cue("music_b", true, 6));
        var selected = StorySoundscapePolicy.select(cues);
        assertEquals(5, selected.size()); assertEquals(List.of("music_b"), selected.stream().filter(StoryProtocol.SoundCue::music).map(StoryProtocol.SoundCue::key).toList());
        assertEquals(.5f, StorySoundscapePolicy.ambienceScale(selected), .001f);
        assertEquals(selected, StorySoundscapePolicy.select(cues.reversed()));
    }
    @Test void repeatedSnapshotsDoNotRestartCrossfadeAndReversalIsContinuous() {
        var envelope = new StorySoundscapePolicy.Envelope();
        for (int i = 0; i < 20; i++) { envelope.target(.8f, 40); envelope.tick(); }
        assertEquals(.4f, envelope.value(), .0001f);
        envelope.target(0, 20); assertEquals(.4f, envelope.value(), .0001f);
        for (int i = 0; i < 10; i++) envelope.tick(); assertEquals(.2f, envelope.value(), .0001f);
        envelope.target(.6f, 20); envelope.tick(); assertEquals(.22f, envelope.value(), .0001f);
        envelope.target(0, 0); assertTrue(envelope.silent());
    }
    @Test void instantaneousEnvelopesAndInvalidInputsStayBounded() {
        var envelope = new StorySoundscapePolicy.Envelope(); envelope.target(1, 0); assertEquals(1, envelope.value());
        assertThrows(IllegalArgumentException.class, () -> envelope.target(Float.POSITIVE_INFINITY, 0));
        assertThrows(IllegalArgumentException.class, () -> envelope.target(.3f, -1));
    }
}

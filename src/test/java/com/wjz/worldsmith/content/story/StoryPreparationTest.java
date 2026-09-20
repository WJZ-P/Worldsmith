package com.wjz.worldsmith.content.story;

import com.wjz.worldsmith.content.WorldBlockBindings;
import com.wjz.worldsmith.content.creature.CreatureRuntime;
import com.wjz.worldsmith.content.item.CustomItemRuntime;
import com.wjz.worldsmith.core.content.CustomBlockBindings;
import com.wjz.worldsmith.core.content.CustomBlockLibrary;
import com.wjz.worldsmith.core.content.CustomItemLibrary;
import com.wjz.worldsmith.core.content.CreatureLibrary;
import com.wjz.worldsmith.core.model.WorldsmithPack;
import com.wjz.worldsmith.core.pack.WorldsmithPackLoader;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class StoryPreparationTest {
    private static WorldsmithPack pack;
    private static String own, foreign;
    @BeforeAll static void prepareFixture() {
        net.minecraft.SharedConstants.tryDetectVersion(); net.minecraft.server.Bootstrap.bootStrap();
        pack = WorldsmithPackLoader.loadClasspath("worldsmith/packs/ashlands"); own = pack.getManifest().getId();
        foreign = own.equals("0".repeat(64)) ? "1".repeat(64) : "0".repeat(64);
    }
    @Test void matchingResolversPrepareWithoutPublishingAClientWorld() {
        var before = WorldStoryRuntime.clientSnapshot();
        var prepared = WorldStoryRuntime.prepare(pack, items(own), creatures(own), blocks(own));
        assertEquals(own, prepared.scope()); assertSame(before, WorldStoryRuntime.clientSnapshot());
    }
    @Test void foreignItemResolverIsRejectedBeforePreparation() {
        assertThrows(IllegalArgumentException.class, () -> WorldStoryRuntime.prepare(pack, items(foreign), creatures(own), blocks(own)));
    }
    @Test void foreignCreatureResolverIsRejectedBeforePreparation() {
        assertThrows(IllegalArgumentException.class, () -> WorldStoryRuntime.prepare(pack, items(own), creatures(foreign), blocks(own)));
    }
    @Test void foreignBlockResolverIsRejectedBeforePreparation() {
        assertThrows(IllegalArgumentException.class, () -> WorldStoryRuntime.prepare(pack, items(own), creatures(own), blocks(foreign)));
    }
    @Test void mutuallyConsistentResolversFromAnotherWorldAreStillRejected() {
        assertThrows(IllegalArgumentException.class, () -> WorldStoryRuntime.prepare(pack, items(foreign), creatures(foreign), blocks(foreign)));
    }
    private static CustomItemRuntime.Snapshot items(String scope) { return CustomItemRuntime.prepare(scope, new CustomItemLibrary()); }
    private static CreatureRuntime.Snapshot creatures(String scope) { return CreatureRuntime.prepare(scope, new CreatureLibrary()); }
    private static WorldBlockBindings.Resolver blocks(String scope) { return WorldBlockBindings.resolver(CustomBlockBindings.plan(scope, new CustomBlockLibrary(), null)); }
}

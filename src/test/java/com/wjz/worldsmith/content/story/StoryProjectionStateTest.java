package com.wjz.worldsmith.content.story;

import com.wjz.worldsmith.content.WorldBlockBindings;
import com.wjz.worldsmith.core.content.CustomBlockBindings;
import com.wjz.worldsmith.core.content.CustomBlockLibrary;
import com.wjz.worldsmith.core.story.*;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.StairBlock;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class StoryProjectionStateTest {
    private static final String SCOPE="a".repeat(64);
    @BeforeAll static void bootstrap(){net.minecraft.SharedConstants.tryDetectVersion();net.minecraft.server.Bootstrap.bootStrap();}
    private static WorldBlockBindings.Resolver bindings(){return WorldBlockBindings.resolver(CustomBlockBindings.plan(SCOPE,new CustomBlockLibrary()));}
    @Test void defaultsAndPropertiesResolveToExactNativeStatesAndFollowActualMarkerRotation() {
        var state=StoryProjections.resolve(new StoryBlockState("minecraft:oak_stairs",Map.of("facing","north")),bindings());
        assertEquals(Blocks.OAK_STAIRS.defaultBlockState().setValue(StairBlock.FACING,Direction.NORTH),state);
        assertEquals(Direction.EAST,state.rotate(Rotation.CLOCKWISE_90).getValue(StairBlock.FACING));
        var place=new StorySavedData.Place(UUID.randomUUID(),"village","minecraft:overworld",new BlockPos(40,70,-30),1);
        assertEquals(new BlockPos(38,73,-30),StoryProjections.position(place,new BlockPos(0,3,2)));
        assertThrows(IllegalArgumentException.class,() -> StoryProjections.resolve(new StoryBlockState("minecraft:stone",Map.of("facing","north")),bindings()));
        assertThrows(IllegalArgumentException.class,() -> StoryProjections.resolve(new StoryBlockState("minecraft:oak_stairs",Map.of("facing","up")),bindings()));
    }
    @Test void nativeProjectionCompilationRejectsDataFluidsFallingAndSemanticallyIdenticalStates() {
        for(String forbidden:List.of("minecraft:chest","minecraft:water","minecraft:sand","minecraft:torch","minecraft:oak_door",
            "minecraft:sponge","minecraft:tnt","minecraft:carved_pumpkin","minecraft:redstone_lamp","minecraft:grass_block")) {
            var projection=new StoryProjection("lamp","village",StoryCondition.Always.INSTANCE,List.of(
                new StoryBlockChange(new StoryOffset(),new StoryBlockState("minecraft:stone"),new StoryBlockState(forbidden))));
            assertThrows(IllegalArgumentException.class,() -> StoryProjections.prepare(projection,bindings()),forbidden);
        }
        var identical=new StoryProjection("lamp","village",StoryCondition.Always.INSTANCE,List.of(
            new StoryBlockChange(new StoryOffset(),new StoryBlockState("minecraft:oak_stairs"),
                new StoryBlockState("minecraft:oak_stairs",Map.of("facing",StairBlock.FACING.getName(Blocks.OAK_STAIRS.defaultBlockState().getValue(StairBlock.FACING)))))));
        assertThrows(IllegalArgumentException.class,() -> StoryProjections.prepare(identical,bindings()));
    }
    @Test void staticConstructionAndAirOutcomesRemainSupportedWithoutPlacementSideEffects() {
        for(String stable:List.of("minecraft:sea_lantern","minecraft:amethyst_block","minecraft:chiseled_stone_bricks","minecraft:glass","minecraft:oak_log","minecraft:air")) {
            var projection=new StoryProjection("lamp","village",StoryCondition.Always.INSTANCE,List.of(
                new StoryBlockChange(new StoryOffset(),new StoryBlockState("minecraft:stone"),new StoryBlockState(stable))));
            assertDoesNotThrow(() -> StoryProjections.prepare(projection,bindings()),stable);
        }
    }
    @Test void unloadedPairsSpendOnlyTheBoundedScanAndDoNotExhaustLocalApplicationSlots() {
        var attempts=new ArrayList<Integer>();var inspected=new ArrayList<Integer>();int cursor=0;
        for(int tick=0;tick<3;tick++)cursor=StoryProjections.scan(130,cursor,index -> {inspected.add(index);return index==129;},attempts::add);
        assertEquals(List.of(129),attempts,"One local loaded projection behind 129 unloaded pairs must be reached within ceil(N/64) ticks");
        assertEquals(192,inspected.size());
        assertEquals(62,cursor);
    }
    @Test void twoPersistentLoadedConflictsNeverStarveTheRestOfTheRing() {
        var attempts=new ArrayList<Integer>();int cursor=0;
        for(int tick=0;tick<4;tick++)cursor=StoryProjections.scan(7,cursor,index -> true,attempts::add);
        assertEquals(List.of(0,1,2,3,4,5,6,0),attempts);
        assertEquals(1,cursor);
        assertEquals(0,StoryProjections.scan(0,0,index -> {throw new AssertionError();},index -> {throw new AssertionError();}));
    }
}

package com.wjz.worldsmith.ability;

import com.wjz.worldsmith.content.WorldBlockBindings;
import com.wjz.worldsmith.content.WorldsmithCustomBlocks;
import com.wjz.worldsmith.core.ability.AbilityValues;
import com.wjz.worldsmith.core.content.CustomBlockBindings;
import com.wjz.worldsmith.core.examples.MaterialFamilyFactory;
import com.wjz.worldsmith.worldgen.WorldsmithTestBootstrap;
import java.util.Map;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.RotatedPillarBlock;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AbilityMaterialResolutionTest {
    @BeforeAll static void bootstrap(){WorldsmithTestBootstrap.bootStrapCustomBlocks();}
    private static WorldBlockBindings.Resolver blocks(){return WorldBlockBindings.resolver(CustomBlockBindings.plan("a".repeat(64),MaterialFamilyFactory.create().getBlocks()));}
    @Test void scriptsResolveTheSameControlledFacingAndImmutableLightAsNativePlacement(){
        var value=AbilityWorldGameplay.resolve(blocks(),"worldsmith:content/"+MaterialFamilyFactory.LAMP_LIT,Map.of("facing",AbilityValues.text("west")));
        assertEquals(Direction.WEST,value.getValue(WorldsmithCustomBlocks.FACING));
        assertEquals(13,value.getValue(WorldsmithCustomBlocks.LIGHT));
        assertTrue(value.getValue(WorldsmithCustomBlocks.ORIENTED));
    }
    @Test void scriptsCannotOverrideDefinitionModeLightOrFixedMaterialAxes(){
        var blocks=blocks();
        assertThrows(IllegalArgumentException.class,() -> AbilityWorldGameplay.resolve(blocks,"worldsmith:content/"+MaterialFamilyFactory.LAMP_LIT,Map.of("light",AbilityValues.text("0"))));
        assertThrows(IllegalArgumentException.class,() -> AbilityWorldGameplay.resolve(blocks,"worldsmith:content/"+MaterialFamilyFactory.HEARTH_TIMBER,Map.of("facing",AbilityValues.text("west"))));
        assertThrows(IllegalArgumentException.class,() -> AbilityWorldGameplay.resolve(blocks,"worldsmith:content/"+MaterialFamilyFactory.LAMP_LIT,Map.of("facing",AbilityValues.number(2))));
        assertThrows(IllegalArgumentException.class,() -> AbilityWorldGameplay.resolve(blocks,"worldsmith:content/block/stone/00",Map.of()));
    }
    @Test void ordinaryNativePropertyResolutionRemainsAvailable(){
        var value=AbilityWorldGameplay.resolve(blocks(),"minecraft:oak_log",Map.of("axis",AbilityValues.text("z")));
        assertEquals(Direction.Axis.Z,value.getValue(RotatedPillarBlock.AXIS));
    }
}

package com.wjz.worldsmith.worldgen;

import com.wjz.worldsmith.content.WorldBlockBindings;
import com.wjz.worldsmith.content.WorldsmithCustomBlocks;
import com.wjz.worldsmith.core.content.CustomBlockBindings;
import com.wjz.worldsmith.core.examples.MaterialFamilyFactory;
import com.wjz.worldsmith.core.structure.BuildMaterial;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

final class WorldsmithBlockAppearanceTest {
    @BeforeAll static void bootstrap() { WorldsmithTestBootstrap.bootStrapCustomBlocks(); }
    private static WorldBlockBindings.Resolver resolver() { return WorldBlockBindings.resolver(CustomBlockBindings.plan("materials",MaterialFamilyFactory.create().getBlocks())); }
    @Test void horizontalFaceSurvivesAuthoringResolutionRotationAndMirroring() {
        var blocks=resolver();String id="worldsmith:content/"+MaterialFamilyFactory.WAYSTONE;
        var north=WorldsmithStructureTemplates.resolve(new BuildMaterial(id,Map.of("facing","north")),blocks);
        assertTrue(north.getValue(WorldsmithCustomBlocks.ORIENTED));assertEquals(Direction.NORTH,north.getValue(WorldsmithCustomBlocks.FACING));
        assertEquals(Direction.EAST,north.rotate(Rotation.CLOCKWISE_90).getValue(WorldsmithCustomBlocks.FACING));
        assertEquals(Direction.SOUTH,north.mirror(Mirror.LEFT_RIGHT).getValue(WorldsmithCustomBlocks.FACING));
        for(var face:Map.of("north",Direction.NORTH,"east",Direction.EAST,"south",Direction.SOUTH,"west",Direction.WEST).entrySet())
            assertEquals(face.getValue(),blocks.resolve(id,Map.of("facing",face.getKey())).getValue(WorldsmithCustomBlocks.FACING));
        assertThrows(IllegalArgumentException.class,()->blocks.resolve(id,Map.of("facing","up")));
        assertThrows(IllegalArgumentException.class,()->blocks.resolve(id,Map.of("light","15")));
        assertThrows(IllegalArgumentException.class,()->blocks.resolve(id,Map.of("oriented","false")));
    }
    @Test void fixedMaterialsKeepNativeTextureAxesThroughTemplateTransforms() {
        var blocks=resolver();String id="worldsmith:content/"+MaterialFamilyFactory.HEARTH_TIMBER;var state=blocks.resolve(id);
        assertFalse(state.getValue(WorldsmithCustomBlocks.ORIENTED));
        for(Rotation rotation:Rotation.values())assertEquals(state,state.rotate(rotation));
        for(Mirror mirror:Mirror.values())assertEquals(state,state.mirror(mirror));
        assertThrows(IllegalArgumentException.class,()->blocks.resolve(id,Map.of("facing","east")));
    }
    @Test void repairUsesActualDifferentBlockAndLightWhileKeepingFacingContract() {
        var blocks=resolver();var dark=blocks.resolve("worldsmith:content/"+MaterialFamilyFactory.LAMP_UNLIT,Map.of("facing","west"));
        var lit=blocks.resolve("worldsmith:content/"+MaterialFamilyFactory.LAMP_LIT,Map.of("facing","west"));
        assertNotEquals(dark.getBlock(),lit.getBlock());assertEquals(0,dark.getLightEmission());assertEquals(13,lit.getLightEmission());
        assertEquals(Direction.WEST,lit.getValue(WorldsmithCustomBlocks.FACING));assertTrue(lit.getValue(WorldsmithCustomBlocks.ORIENTED));
    }
    @Test void authoringQueryExposesOnlyDeclaredFacingInsteadOfReservedHostProperties() {
        var query = new WorldsmithAuthoringNativeHost(resolver()).query(java.util.List.of("worldsmith:content/"+MaterialFamilyFactory.WAYSTONE),"",1);
        var json = com.google.gson.JsonParser.parseString(query.toString()).getAsJsonObject();
        var properties = json.getAsJsonArray("entries").get(0).getAsJsonObject().getAsJsonObject("properties");
        assertEquals(java.util.Set.of("facing"),properties.keySet());assertEquals(4,properties.getAsJsonArray("facing").size());
        var fixed = new WorldsmithAuthoringNativeHost(resolver()).query(java.util.List.of("worldsmith:content/"+MaterialFamilyFactory.HEARTH_TIMBER),"",1);
        assertTrue(com.google.gson.JsonParser.parseString(fixed.toString()).getAsJsonObject().getAsJsonArray("entries").get(0).getAsJsonObject().getAsJsonObject("properties").isEmpty());
    }
}

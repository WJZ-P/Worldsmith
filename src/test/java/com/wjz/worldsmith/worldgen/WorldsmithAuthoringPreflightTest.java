package com.wjz.worldsmith.worldgen;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.JsonParser;
import com.wjz.worldsmith.core.draw.*;
import com.wjz.worldsmith.core.structure.*;
import java.util.*;
import org.junit.jupiter.api.*;

final class WorldsmithAuthoringPreflightTest {
    @BeforeAll static void boot(){WorldsmithTestBootstrap.bootStrap();}
    @Test void invalidStateQueriesStillReturnTheAllowedNativeVocabulary(){
        var host=new WorldsmithAuthoringNativeHost();
        var result=JsonParser.parseString(host.query(List.of("minecraft:cobblestone_wall[north=true]","minecraft:glowstone"),"",8).toString()).getAsJsonObject();
        var entries=result.getAsJsonArray("entries");assertTrue(entries.get(0).getAsJsonObject().has("error"));
        assertTrue(entries.get(0).getAsJsonObject().getAsJsonObject("properties").getAsJsonArray("north").toString().contains("tall"));
        assertEquals(15,entries.get(1).getAsJsonObject().get("lightEmission").getAsInt());
    }
    @Test void preflightCollectsPositionsForPairsAndFalseLightWithoutWritingNbt(){
        var voxels=List.of(new StructureVoxel(new BuildPos(1,1,1),new BuildMaterial("minecraft:oak_door",Map.of("half","lower"))),new StructureVoxel(new BuildPos(3,1,1),new BuildMaterial("minecraft:stone",Map.of())));
        var light=new StructureLighting(StructureLightingMode.EXTERIOR_ONLY,List.of(),List.of(new StructureLightSource(new BuildPos(3,1,1),15)),8);
        var g=new CompiledStructure("checks",new BuildPos(5,4,5),new BuildPos(0,0,0),voxels,List.of(),2,List.of(),List.of(),light);
        var errors=new WorldsmithAuthoringNativeHost().inspect(g);
        assertEquals(Set.of("NATIVE_PAIRED_BLOCK","NATIVE_LIGHT_EMISSION"),errors.stream().map(d->d.getCode()).collect(java.util.stream.Collectors.toSet()));
        assertTrue(errors.stream().allMatch(d->d.getPosition()!=null&&d.getExpected()!=null&&d.getActual()!=null));
        assertThrows(IllegalArgumentException.class,()->WorldsmithStructureTemplates.encode(g));
    }
}

import com.wjz.worldsmith.authoring.*;
import com.wjz.worldsmith.core.draw.*;

/** Small working settlement: an open market, a timber home and an asymmetric forge porch. */
public final class HamletBuildings implements StructureProgram {
    @Override public AuthoredStructure generate(AuthoringContext a) {
        return switch(a.parameters().getOrDefault("kind","moon_market")) {
            case "moon_market" -> market(a);case "forester_lodge" -> lodge(a);case "smithy" -> smithy(a);
            default -> throw new IllegalArgumentException("Unknown silverpine-hamlet target");
        };
    }
    private AuthoredStructure market(AuthoringContext a) {
        var c=EclipseCraft.begin(a,15,15,13);
        EclipseCraft.fill(c,EclipseCraft.MOON,-2,2,-15,2,2,15);EclipseCraft.fill(c,EclipseCraft.MOON,-15,2,-2,15,2,2);
        for(int x:new int[]{-9,9})for(int z:new int[]{-9,9}) {
            for(int dx:new int[]{-3,3})for(int dz:new int[]{-2,2})c.pen("minecraft:stripped_spruce_log").fill(Box.of(x+dx,3,z+dz,x+dx,7,z+dz));
            c.pen(x<0?"minecraft:orange_wool":"minecraft:cyan_wool").fill(Box.of(x-4,8,z-3,x+4,8,z+3));
            c.pen("minecraft:dark_oak_slab").fill(Box.of(x-4,9,z-3,x+4,9,z+3));
            c.pen("minecraft:barrel[facing=up]").set(x-2,3,z);c.pen("minecraft:crafting_table").set(x+2,3,z);
            a.lightFixture("stall_"+x+"_"+z,new Vec3i(x,7,z),BlockStateRef.of("minecraft:sea_lantern"),15);
        }
        EclipseCraft.fill(c,EclipseCraft.BRONZE,-2,3,-2,2,3,2);c.pen("minecraft:water").fill(Box.of(-1,3,-1,1,3,1));
        EclipseCraft.fill(c,EclipseCraft.MOON,0,4,0,0,8,0);a.lightFixture("market_star",new Vec3i(0,9,0),BlockStateRef.of("minecraft:sea_lantern"),15);
        EclipseCraft.chest(a,-11,9,"worldsmith:item/moon_shard",4);
        EclipseCraft.exit(a,"east",15,0,"EAST");EclipseCraft.exit(a,"west",-15,0,"WEST");EclipseCraft.exit(a,"south",0,15,"SOUTH");
        a.component("working_stalls",Box.of(-13,3,-12,13,9,12));return a.snapshot();
    }
    private AuthoredStructure lodge(AuthoringContext a) {
        var c=EclipseCraft.begin(a,12,10,23);
        EclipseCraft.boxRoom(a,"forester_home",-7,-6,7,6,11,BlockStateRef.of("minecraft:oak_planks"),BlockStateRef.of("minecraft:spruce_planks"));
        for(int x:new int[]{-8,8})for(int z:new int[]{-7,7})c.pen("minecraft:stripped_spruce_log").fill(Box.of(x,3,z,x,11,z));
        for(int side:new int[]{-1,1})for(int z:new int[]{-3,2})EclipseCraft.window(c,side*8,6,z,2,false);
        EclipseCraft.gable(c,9,-8,8,12,"minecraft:spruce_planks");
        c.pen("minecraft:cobblestone").fill(Box.of(5,10,3,6,22,4));
        c.pen("minecraft:white_bed[facing=south,part=foot]").set(-5,3,3);c.pen("minecraft:white_bed[facing=south,part=head]").set(-5,3,4);
        c.pen("minecraft:crafting_table").set(5,3,-4);c.pen("minecraft:bookshelf").fill(Box.of(-6,3,-5,-3,4,-5));
        EclipseCraft.chest(a,5,4,"worldsmith:item/moon_shard",4);
        EclipseCraft.exit(a,"entry",0,7,"SOUTH");EclipseCraft.lightFloor(a,"home_lamps",-7,-6,7,6);
        return a.snapshot();
    }
    private AuthoredStructure smithy(AuthoringContext a) {
        var c=EclipseCraft.begin(a,12,11,20);
        EclipseCraft.boxRoom(a,"smith_workroom",-8,-7,3,6,11,BlockStateRef.of("minecraft:stone_bricks"),BlockStateRef.of("minecraft:polished_andesite"));
        // The roof is broken into a closed workshop and a lower open side canopy.
        c.pen("minecraft:dark_oak_planks").fill(Box.of(-10,12,-9,5,12,8));
        for(int i=0;i<5;i++)c.pen("minecraft:dark_oak_planks").fill(Box.of(-10+i,13+i,-9,5-i,13+i,8));
        for(int z:new int[]{-7,7})c.pen("minecraft:stripped_dark_oak_log").fill(Box.of(10,3,z,10,8,z));
        c.pen("minecraft:dark_oak_slab").fill(Box.of(4,9,-8,11,9,8));
        c.pen("air").fill(Box.of(4,3,-2,4,6,2));
        c.pen("minecraft:blast_furnace[facing=east]").set(-7,3,-5);c.pen("minecraft:furnace[facing=east]").set(-7,3,-3);
        c.pen("minecraft:anvil[facing=north]").set(7,3,0);c.pen("minecraft:smithing_table").set(1,3,-5);
        EclipseCraft.fill(c,EclipseCraft.BRONZE,-8,11,-7,-6,19,-5);
        EclipseCraft.window(c,-9,6,-2,3,false);EclipseCraft.chest(a,-7,5,"worldsmith:item/bronze_seal",1);
        a.lightFixture("forge_canopy",new Vec3i(7,8,0),BlockStateRef.of("minecraft:sea_lantern"),15);
        EclipseCraft.exit(a,"entry",0,7,"SOUTH");EclipseCraft.lightFloor(a,"smith_lamps",-8,-7,3,6);
        return a.snapshot();
    }
}

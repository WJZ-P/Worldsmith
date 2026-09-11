import com.wjz.worldsmith.authoring.*;
import com.wjz.worldsmith.core.draw.*;

/** Independent destinations have their own role: early supplies, mineral work and a broken road lookout. */
public final class WaysideBuildings implements StructureProgram {
    @Override public AuthoredStructure generate(AuthoringContext a) {
        return switch(a.parameters().getOrDefault("kind","pilgrim_shrine")) {
            case "pilgrim_shrine" -> shrine(a);case "crystal_quarry" -> quarry(a);case "fallen_watchtower" -> watchtower(a);
            default -> throw new IllegalArgumentException("Unknown wayside target");
        };
    }
    private AuthoredStructure shrine(AuthoringContext a) {
        var c=EclipseCraft.begin(a,7,7,19);
        for(int x:new int[]{-4,4})for(int z:new int[]{-4,4}) {
            EclipseCraft.fill(c,EclipseCraft.MOON,x,3,z,x,4,z);EclipseCraft.fill(c,EclipseCraft.SLATE,x,5,z,x,13,z);
            EclipseCraft.fill(c,EclipseCraft.BRONZE,x,14,z,x,16,z);
        }
        EclipseCraft.parapet(c,-4,-4,4,4,13,EclipseCraft.BRONZE);
        EclipseCraft.fill(c,EclipseCraft.MOON,-1,3,-1,1,4,1);EclipseCraft.fill(c,EclipseCraft.GLASS,0,5,0,0,9,0);
        a.lightFixture("pilgrim_star",new Vec3i(0,10,0),BlockStateRef.of("minecraft:sea_lantern"),15);
        // Eight guaranteed stored shards are independent of the mob-drops game rule.
        EclipseCraft.chest(a,-3,3,"worldsmith:item/moon_shard",8);
        EclipseCraft.exit(a,"north",0,-7,"NORTH");a.component("open_star_altar",Box.of(-4,3,-4,4,16,4));return a.snapshot();
    }
    private AuthoredStructure quarry(AuthoringContext a) {
        var c=EclipseCraft.begin(a,14,11,15);
        for(int x:new int[]{-11,-6,6,11})for(int z:new int[]{-8,-3,5}) {
            int h=4+Math.floorMod(x*3+z,5);c.pen("minecraft:tuff").fill(Box.of(x-1,3,z-1,x+1,h,z+1));
            EclipseCraft.fill(c,EclipseCraft.MOON,x,h+1,z,x,h+3,z);
        }
        for(int x:new int[]{7,12})for(int z:new int[]{4,9})c.pen("minecraft:stripped_spruce_log").fill(Box.of(x,3,z,x,8,z));
        c.pen("minecraft:spruce_slab").fill(Box.of(6,9,3,13,9,10));c.pen("minecraft:stonecutter[facing=north]").set(9,3,7);
        a.lightFixture("quarry_lamp",new Vec3i(10,8,6),BlockStateRef.of("minecraft:sea_lantern"),15);
        EclipseCraft.chest(a,8,5,"worldsmith:item/moon_shard",12);
        EclipseCraft.exit(a,"north",0,-11,"NORTH");a.component("open_crystal_working",Box.of(-12,3,-9,13,12,10));return a.snapshot();
    }
    private AuthoredStructure watchtower(AuthoringContext a) {
        var c=EclipseCraft.begin(a,9,9,28);
        EclipseCraft.boxRoom(a,"road_lookout",-4,-4,4,4,18,BlockStateRef.of("minecraft:mossy_stone_bricks"),EclipseCraft.ROAD);
        for(int x:new int[]{-5,5})for(int z:new int[]{-5,5})EclipseCraft.fill(c,EclipseCraft.SLATE,x,3,z,x,24,z);
        EclipseCraft.parapet(c,-5,-5,5,5,24,EclipseCraft.MOON);
        c.pen("air").fill(Box.of(1,15,-5,5,27,1));
        for(int x:new int[]{-7,-2,4,7})c.pen("minecraft:cobblestone").fill(Box.of(x,3,-7,x,4,-6));
        EclipseCraft.window(c,-5,8,-1,3,false);EclipseCraft.window(c,-1,8,5,3,true);
        EclipseCraft.chest(a,-3,3,"worldsmith:item/bronze_seal",1);
        EclipseCraft.exit(a,"north",0,-5,"NORTH");EclipseCraft.lightFloor(a,"lookout_lamps",-4,-4,4,4);
        a.component("broken_crown",Box.of(-5,15,-5,5,25,5));return a.snapshot();
    }
}

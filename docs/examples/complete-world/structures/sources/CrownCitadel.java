import com.wjz.worldsmith.authoring.*;
import com.wjz.worldsmith.core.draw.*;

/** A tall, lit royal keep with a separate barbican and two inhabited bell towers in its assembly. */
public final class CrownCitadel implements StructureProgram {
    @Override public AuthoredStructure generate(AuthoringContext a) {
        return switch(a.parameters().getOrDefault("kind","crown_keep")) {
            case "crown_keep" -> keep(a);
            case "crown_gate" -> gate(a);
            case "crown_belltower" -> tower(a);
            default -> throw new IllegalArgumentException("Unknown crown-citadel target");
        };
    }
    private AuthoredStructure keep(AuthoringContext a) {
        var c=EclipseCraft.begin(a,23,24,44);
        EclipseCraft.boxRoom(a,"great_hall",-13,-15,13,15,17,EclipseCraft.MOON,BlockStateRef.of("minecraft:polished_deepslate"));
        // Low great-hall roof + a rear stone keep, not a warehouse roof with a floating glass box.
        EclipseCraft.fill(c,EclipseCraft.MOON,-2,2,-21,2,2,15);
        for(int z=-14;z<=14;z+=7)for(int side:new int[]{-1,1}) {
            EclipseCraft.fill(c,EclipseCraft.SLATE,side*15,3,z-1,side*15,20,z+1);
            EclipseCraft.fill(c,EclipseCraft.BRONZE,side*16,3,z-1,side*16,6,z+1);
        }
        // Windows occupy the clear bays BETWEEN buttresses; their glass is one cell behind the facade.
        for(int z:new int[]{-12,-5,2,9})for(int side:new int[]{-1,1}) {
            c.pen("air").fill(Box.of(side*14,6,z,side*14,11,z+2));
            EclipseCraft.window(c,side*13,7,z,3,false);
            EclipseCraft.fill(c,EclipseCraft.BRONZE,side*14,5,z-1,side*14,5,z+3);
            EclipseCraft.fill(c,EclipseCraft.SLATE,side*14,12,z-1,side*14,12,z+3);
        }
        for(int x:new int[]{-11,9}) {
            c.pen("air").fill(Box.of(x,6,-16,x+2,11,-16));
            EclipseCraft.window(c,x,7,-15,3,true);
            EclipseCraft.fill(c,EclipseCraft.BRONZE,x-1,5,-17,x+3,5,-17);
            EclipseCraft.fill(c,EclipseCraft.SLATE,x-1,12,-17,x+3,12,-17);
        }
        ring(c,-15,-17,15,17,6,EclipseCraft.BRONZE);
        ring(c,-16,-18,16,18,16,EclipseCraft.MOON);
        ring(c,-17,-19,17,19,17,EclipseCraft.SLATE);
        for(int x:new int[]{-14,14})for(int z:new int[]{-16,16}) {
            EclipseCraft.fill(c,EclipseCraft.SLATE,x,3,z,x,17,z);
            for(int y:new int[]{4,8,12,16})EclipseCraft.fill(c,EclipseCraft.BRONZE,x,y,z,x,y,z);
        }
        for(int rise=0;rise<=8;rise++) {
            int x=16-rise*2;c.pen("minecraft:deepslate_tiles").fill(Box.of(-x,18+rise,-18,x,18+rise,18));
            if(x>0)for(int side:new int[]{-1,1})c.pen("minecraft:polished_blackstone_brick_stairs[facing="+(side<0?"east":"west")+"]")
                .fill(Box.of(side*x,18+rise,-18,side*x,18+rise,18));
        }
        // A recessed rose slit belongs to the low north gable, above the deep entrance porch.
        c.pen("air").fill(Box.of(-2,20,-18,2,23,-18));
        EclipseCraft.fill(c,EclipseCraft.GLASS,-2,20,-17,2,23,-17);
        EclipseCraft.fill(c,EclipseCraft.BRONZE,0,20,-18,0,23,-18);
        // The keep tower rises from the same foundation through the hall's roof.
        EclipseCraft.fill(c,EclipseCraft.MOON,-6,3,4,6,39,17);
        a.room("high_keep_chamber",Box.of(-5,3,5,5,38,16),BlockStateRef.of("minecraft:polished_deepslate"));
        for(int x:new int[]{-6,6})for(int z:new int[]{4,17})EclipseCraft.fill(c,EclipseCraft.SLATE,x,3,z,x,40,z);
        for(int y:new int[]{15,27,38})ring(c,-7,3,7,18,y,EclipseCraft.BRONZE);
        for(int side:new int[]{-1,1})for(int z:new int[]{7,13}) {
            c.pen("air").fill(Box.of(side*6,29,z,side*6,35,z+1));
            EclipseCraft.fill(c,EclipseCraft.GLASS,side*5,29,z,side*5,35,z+1);
        }
        for(int x:new int[]{-3,2}) {
            c.pen("air").fill(Box.of(x,29,4,x+1,35,4));
            EclipseCraft.fill(c,EclipseCraft.GLASS,x,29,5,x+1,35,5);
        }
        EclipseCraft.parapet(c,-7,3,7,18,40,EclipseCraft.SLATE);
        for(int x:new int[]{-3,3})for(int z:new int[]{8,13})EclipseCraft.fill(c,EclipseCraft.SLATE,x,40,z,x,41,z);
        EclipseCraft.fill(c,EclipseCraft.BRONZE,-3,42,8,3,42,13);
        a.lightFixture("crown_beacon",new Vec3i(0,37,10),BlockStateRef.of("minecraft:sea_lantern"),15);
        c.pen("air").fill(Box.of(-2,3,4,2,8,5));
        // Five-wide stepped arch with porch depth, rather than a three-high hole in a blank elevation.
        EclipseCraft.fill(c,EclipseCraft.SLATE,-4,3,-20,4,11,-17);
        c.pen("air").fill(Box.of(-2,3,-20,2,7,-15));
        c.pen("air").fill(Box.of(-1,8,-20,1,8,-15));c.pen("air").fill(Box.of(0,9,-20,0,9,-15));
        for(int x:new int[]{-4,4})EclipseCraft.fill(c,EclipseCraft.BRONZE,x,3,-20,x,10,-20);
        EclipseCraft.fill(c,EclipseCraft.MOON,-5,11,-21,5,11,-16);
        EclipseCraft.fill(c,EclipseCraft.MOON,-3,3,14,3,3,16);
        EclipseCraft.fill(c,EclipseCraft.BRONZE,-1,4,15,1,5,16);
        for(int z:new int[]{-8,-3,3})for(int x:new int[]{-9,9})c.pen("minecraft:dark_oak_stairs[facing="+(x<0?"east":"west")+"]").set(x,3,z);
        EclipseCraft.chest(a,11,12,"worldsmith:item/bronze_seal",2);
        EclipseCraft.parapet(c,-23,-24,23,24,3,EclipseCraft.SLATE);
        EclipseCraft.exit(a,"north",0,-16,"NORTH");EclipseCraft.exit(a,"east",14,0,"EAST");EclipseCraft.exit(a,"west",-14,0,"WEST");
        EclipseCraft.lightFloor(a,"hall_moon_tiles",-13,-15,13,15);
        EclipseCraft.lightFloor(a,"keep_chamber_lamps",-5,5,5,16);
        // Range-four candidates plus the 2.4-wide / 4.95-tall Boss stay inside this clear hall.
        // Check the actual final voxels, rather than trusting a room label or moving a spawn into a wall.
        for(int x=-6;x<=6;x++)for(int z=-12;z<=0;z++) {
            if(c.get(new Vec3i(x,2,z)).map(b->b.state().isAir()).orElse(true))
                throw new IllegalStateException("Boss encounter needs a floor at "+x+",2,"+z);
            for(int y=3;y<=9;y++)if(!c.get(new Vec3i(x,y,z)).map(b->b.state().isAir()).orElse(false))
                throw new IllegalStateException("Boss encounter clearance blocked at "+x+","+y+","+z);
        }
        a.bossSpawner(new Vec3i(0,3,-6),"darkstar_gatekeeper",2400,16,4);
        a.component("darkstar_encounter_clearance",Box.of(-6,3,-12,6,9,0));
        a.component("royal_silhouette",Box.of(-16,3,-18,16,43,18));
        return a.snapshot();
    }
    private static void ring(DrawCanvas c,int x0,int z0,int x1,int z1,int y,BlockStateRef material) {
        EclipseCraft.fill(c,material,x0,y,z0,x1,y,z0);EclipseCraft.fill(c,material,x0,y,z1,x1,y,z1);
        EclipseCraft.fill(c,material,x0,y,z0,x0,y,z1);EclipseCraft.fill(c,material,x1,y,z0,x1,y,z1);
    }
    private AuthoredStructure gate(AuthoringContext a) {
        var c=EclipseCraft.begin(a,13,12,26);
        EclipseCraft.boxRoom(a,"barbican",-9,-8,9,8,13,EclipseCraft.SLATE,EclipseCraft.ROAD);
        for(int side:new int[]{-1,1}) {
            EclipseCraft.fill(c,EclipseCraft.MOON,side*11-1,3,-10,side*11+1,20,10);
            for(int z:new int[]{-10,10})EclipseCraft.fill(c,EclipseCraft.BRONZE,side*11,21,z,side*11,24,z);
        }
        EclipseCraft.parapet(c,-10,-9,10,9,14,EclipseCraft.MOON);
        for(int x=-7;x<=7;x+=7)EclipseCraft.window(c,x,7,-9,2,true);
        EclipseCraft.exit(a,"entry",0,9,"SOUTH");EclipseCraft.exit(a,"outer",0,-9,"NORTH");
        EclipseCraft.lightFloor(a,"barbican_lamps",-9,-8,9,8);
        a.component("barbican_twin_pylons",Box.of(-12,3,-10,12,24,10));
        return a.snapshot();
    }
    private AuthoredStructure tower(AuthoringContext a) {
        var c=EclipseCraft.begin(a,10,10,39);
        EclipseCraft.boxRoom(a,"bell_keeper_lobby",-6,-6,6,6,13,EclipseCraft.MOON,BlockStateRef.of("minecraft:spruce_planks"));
        for(int x:new int[]{-7,7})for(int z:new int[]{-7,7})EclipseCraft.fill(c,EclipseCraft.SLATE,x,3,z,x,30,z);
        for(int y:new int[]{14,24,30})EclipseCraft.parapet(c,-7,-7,7,7,y,EclipseCraft.BRONZE);
        for(int side:new int[]{-1,1})for(int y:new int[]{17,26}) {
            EclipseCraft.window(c,side*7,y,-2,5,false);
            EclipseCraft.window(c,-2,y,side*7,5,true);
        }
        // Hollow upper lantern: visible bells and light, without falsely declaring an unreachable occupied floor.
        for(int x:new int[]{-5,5})for(int z:new int[]{-5,5})EclipseCraft.fill(c,EclipseCraft.SLATE,x,14,z,x,34,z);
        c.pen("minecraft:bell[attachment=ceiling,facing=north]").set(0,31,0);
        EclipseCraft.fill(c,EclipseCraft.BRONZE,-1,32,-1,1,32,1);
        for(int rise=0;rise<5;rise++)EclipseCraft.fill(c,EclipseCraft.SLATE,-6+rise,33+rise,-6+rise,6-rise,33+rise,6-rise);
        a.lightFixture("bell_lantern",new Vec3i(2,31,2),BlockStateRef.of("minecraft:sea_lantern"),15);
        EclipseCraft.chest(a,-5,5,"worldsmith:item/moon_shard",4);
        EclipseCraft.exit(a,"entry",0,7,"SOUTH");EclipseCraft.lightFloor(a,"lobby_lamps",-6,-6,6,6);
        return a.snapshot();
    }
}

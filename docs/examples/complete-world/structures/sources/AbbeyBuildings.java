import com.wjz.worldsmith.authoring.*;
import com.wjz.worldsmith.core.draw.*;

/** The star abbey is an arcaded court with a library and a hollow octagonal observatory. */
public final class AbbeyBuildings implements StructureProgram {
    @Override public AuthoredStructure generate(AuthoringContext a) {
        return switch(a.parameters().getOrDefault("kind","star_cloister")) {
            case "star_cloister" -> cloister(a);case "star_archive" -> archive(a);case "moon_observatory" -> observatory(a);
            default -> throw new IllegalArgumentException("Unknown star-abbey target");
        };
    }
    private AuthoredStructure cloister(AuthoringContext a) {
        var c=EclipseCraft.begin(a,18,18,21);
        EclipseCraft.boxRoom(a,"north_gallery",-15,-15,15,-10,11,EclipseCraft.MOON,EclipseCraft.ROAD);
        EclipseCraft.boxRoom(a,"west_gallery",-15,-8,-10,14,11,EclipseCraft.SLATE,EclipseCraft.ROAD);
        EclipseCraft.boxRoom(a,"east_gallery",10,-8,15,14,11,EclipseCraft.SLATE,EclipseCraft.ROAD);
        for(int z:new int[]{-6,-1,4,9})for(int x:new int[]{-9,9})c.pen("air").fill(Box.of(x,3,z,x,7,z+2));
        for(int x:new int[]{-12,-6,0,6,12})c.pen("air").fill(Box.of(x-1,3,-9,x+1,7,-9));
        for(int x:new int[]{-16,16})for(int z:new int[]{-5,3,11})EclipseCraft.window(c,x,6,z,2,false);
        // Recessed north windows and alternating stone piers break up the blank outer gallery.
        for(int x:new int[]{-12,-4,4,12}) {
            EclipseCraft.window(c,x-1,6,-16,3,true);
            EclipseCraft.fill(c,EclipseCraft.BRONZE,x-2,5,-17,x+2,5,-17);
            EclipseCraft.fill(c,EclipseCraft.SLATE,x-2,10,-17,x+2,10,-17);
        }
        for(int x:new int[]{-15,-8,0,8,15}) {
            EclipseCraft.fill(c,EclipseCraft.SLATE,x,3,-17,x,11,-17);
            EclipseCraft.fill(c,EclipseCraft.BRONZE,x,3,-17,x,4,-17);
        }
        for(int x:new int[]{-17,17})for(int z:new int[]{-8,2,9,14}) {
            EclipseCraft.fill(c,EclipseCraft.MOON,x,3,z,x,11,z);
            EclipseCraft.fill(c,EclipseCraft.BRONZE,x,3,z,x,4,z);
        }
        // Thin cornice + a shallow stepped roof retain the original U-shaped open court.
        EclipseCraft.fill(c,EclipseCraft.BRONZE,-17,11,-17,17,11,-17);
        for(int x:new int[]{-17,17})EclipseCraft.fill(c,EclipseCraft.BRONZE,x,11,-16,x,11,16);
        for(int rise=0;rise<=2;rise++) {
            c.pen("minecraft:deepslate_tiles").fill(Box.of(-17+rise,12+rise,-17+rise,17-rise,12+rise,-9-rise));
            c.pen("minecraft:deepslate_tiles").fill(Box.of(-17+rise,12+rise,-9,-9-rise,12+rise,16-rise));
            c.pen("minecraft:deepslate_tiles").fill(Box.of(9+rise,12+rise,-9,17-rise,12+rise,16-rise));
        }
        for(int x:new int[]{-16,16})for(int z:new int[]{-16,15})EclipseCraft.fill(c,EclipseCraft.BRONZE,x,3,z,x,15,z);
        // A three-dimensional armillary sculpture leaves all four approaches open around it.
        for(int v=-6;v<=6;v++)for(int w=-6;w<=6;w++)if(Math.abs(v*v+w*w-36)<=5) {
            EclipseCraft.fill(c,EclipseCraft.BRONZE,v,11+w,0,v,11+w,0);
            EclipseCraft.fill(c,EclipseCraft.BRONZE,0,11+w,v,0,11+w,v);
        }
        EclipseCraft.fill(c,EclipseCraft.MOON,-1,3,-1,1,8,1);a.lightFixture("armillary_heart",new Vec3i(0,11,0),BlockStateRef.of("minecraft:sea_lantern"),15);
        EclipseCraft.exit(a,"east",16,0,"EAST");EclipseCraft.exit(a,"west",-16,0,"WEST");EclipseCraft.exit(a,"south",0,18,"SOUTH");
        EclipseCraft.lightFloor(a,"north_gallery_lamps",-15,-15,15,-10);EclipseCraft.lightFloor(a,"west_gallery_lamps",-15,-8,-10,14);EclipseCraft.lightFloor(a,"east_gallery_lamps",10,-8,15,14);
        return a.snapshot();
    }
    private AuthoredStructure archive(AuthoringContext a) {
        var c=EclipseCraft.begin(a,11,10,25);
        EclipseCraft.boxRoom(a,"reading_hall",-8,-7,8,7,12,EclipseCraft.MOON,BlockStateRef.of("minecraft:dark_oak_planks"));
        for(int x:new int[]{-7,7})for(int z:new int[]{-5,0,5})c.pen("minecraft:bookshelf").fill(Box.of(x,3,z-1,x,7,z+1));
        for(int side:new int[]{-1,1})for(int z:new int[]{-4,3})EclipseCraft.window(c,side*9,7,z,3,false);
        EclipseCraft.gable(c,10,-9,9,13,"minecraft:dark_oak_planks");
        c.pen("minecraft:lectern[facing=north]").set(0,3,4);c.pen("minecraft:cartography_table").set(5,3,-5);
        EclipseCraft.fill(c,EclipseCraft.BRONZE,-1,24,-1,1,24,1);
        EclipseCraft.chest(a,6,6,"worldsmith:item/moon_shard",6);
        EclipseCraft.exit(a,"entry",0,8,"SOUTH");EclipseCraft.lightFloor(a,"reading_lamps",-8,-7,8,7);
        return a.snapshot();
    }
    private AuthoredStructure observatory(AuthoringContext a) {
        var c=EclipseCraft.begin(a,12,12,35);
        for(int x=-9;x<=9;x++)for(int z=-9;z<=9;z++)if(Math.abs(x)+Math.abs(z)<=16) {
            boolean edge=Math.abs(x)==9||Math.abs(z)==9||Math.abs(x)+Math.abs(z)>=15;
            if(edge)EclipseCraft.fill(c,EclipseCraft.SLATE,x,3,z,x,21,z);
            else EclipseCraft.fill(c,EclipseCraft.MOON,x,2,z,x,2,z);
        }
        a.room("observatory_floor",Box.of(-5,3,-5,5,19,5),EclipseCraft.MOON);
        for(int side:new int[]{-1,1})for(int y:new int[]{8,15}) {
            EclipseCraft.window(c,side*9,y,-2,5,false);EclipseCraft.window(c,-2,y,side*9,5,true);
        }
        for(int y=22;y<=31;y++) {
            int r=Math.max(1,(int)Math.ceil(Math.sqrt(Math.max(0,81-(y-22)*(y-22)))));
            for(int x=-r;x<=r;x++)for(int z=-r;z<=r;z++)if(x*x+z*z<=r*r&&x*x+z*z>=(r-1)*(r-1))EclipseCraft.fill(c,EclipseCraft.GLASS,x,y,z,x,y,z);
        }
        EclipseCraft.fill(c,EclipseCraft.BRONZE,0,32,0,0,34,0);
        EclipseCraft.fill(c,EclipseCraft.BRONZE,3,3,3,3,6,3);EclipseCraft.fill(c,EclipseCraft.SLATE,2,6,-4,4,8,4);
        EclipseCraft.fill(c,EclipseCraft.GLASS,2,6,-5,4,8,-5);
        EclipseCraft.chest(a,-4,4,"worldsmith:item/bronze_seal",1);
        EclipseCraft.exit(a,"entry",0,9,"SOUTH");EclipseCraft.lightFloor(a,"observatory_lamps",-5,-5,5,5);
        a.lightFixture("dome_lamp",new Vec3i(0,23,0),BlockStateRef.of("minecraft:sea_lantern"),15);
        return a.snapshot();
    }
}

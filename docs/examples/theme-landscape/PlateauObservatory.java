import com.wjz.worldsmith.authoring.*;
import com.wjz.worldsmith.core.draw.*;
import java.util.List;

/** One authored response to a quiet, wind-exposed plateau; not a universal building preset. */
public final class PlateauObservatory implements StructureProgram {
    private static final BlockStateRef STONE=BlockStateRef.of("minecraft:stone_bricks");
    private static final BlockStateRef ROOF=BlockStateRef.of("minecraft:deepslate_tiles");
    private static final BlockStateRef WOOD=BlockStateRef.of("minecraft:dark_oak_planks");
    private static final BlockStateRef GLASS=BlockStateRef.of("minecraft:gray_stained_glass");

    @Override public AuthoredStructure generate(AuthoringContext a) {
        boolean refined=!a.parameters().getOrDefault("study","refined").equals("massing");
        var c=a.canvas(Box.of(-24,0,-18,24,28,20));a.origin(new Vec3i(0,0,0));
        a.material("foundation",STONE).material("roof",ROOF).material("timber",WOOD);
        c.pen("air").fill(c.bounds());
        // Three shallow retaining steps, clipped corners and an arrival spur, not a deep flat fill.
        for(int y=0;y<=2;y++) {
            int inset=y==0?0:1;
            fill(c,STONE,-23+inset,y,-16+inset,21-inset,y,17-inset);
            c.pen("air").fill(Box.of(19,y,14,21,y,17));
        }
        fill(c,STONE,3,0,15,7,2,20);

        // The tall datum and the low service wing frame an intentionally empty SE court.
        fill(c,STONE,-17,3,-13,-5,18,-1);
        a.room("tower_lower",Box.of(-15,3,-11,-7,9,-3),WOOD);
        a.room("tower_upper",Box.of(-15,11,-11,-7,17,-3),WOOD);
        a.room("sky_deck",Box.of(-16,19,-12,-6,22,-2),ROOF);
        fill(c,STONE,1,3,-13,19,8,-3);
        a.room("service_room",Box.of(3,3,-11,17,6,-5),WOOD);
        // Long, low roof and its restrained high-side clerestory contrast with the tower crown.
        fill(c,ROOF,0,8,-14,20,8,-2);
        fill(c,ROOF,0,9,-14,20,9,-10);
        for(int x=3;x<=17;x+=4) {
            c.pen("air").fill(Box.of(x,7,-13,x+1,7,-13));
            fill(c,GLASS,x,7,-12,x+1,7,-12);
        }

        // Tower crown: low parapets, a west stair gate and a light, open instrument shelter.
        fill(c,ROOF,-18,18,-14,-4,18,0);
        for(int x:new int[]{-17,-5})fill(c,STONE,x,19,-13,x,20,-1);
        for(int z:new int[]{-13,-1})fill(c,STONE,-17,19,z,-5,20,z);
        c.pen("air").fill(Box.of(-17,19,-2,-17,22,-1));
        for(int x:new int[]{-14,-8})for(int z:new int[]{-10,-4})fill(c,WOOD,x,19,z,x,24,z);
        fill(c,ROOF,-15,25,-11,-7,25,-3);
        c.pen("minecraft:deepslate_tile_slab[type=bottom]").fill(Box.of(-16,25,-12,-6,25,-12));
        c.pen("minecraft:deepslate_tile_slab[type=bottom]").fill(Box.of(-16,25,-2,-6,25,-2));
        fill(c,WOOD,-12,19,-8,-12,20,-8); // Offset instrument stand, not the access destination.
        fill(c,ROOF,-12,21,-9,-12,21,-7);

        // A real two-flight, two-block-wide stair serves every occupied storey.
        for(int i=0;i<8;i++) {
            int firstZ=-2-i, secondZ=-10+i;
            fill(c,STONE,-19,2+i,firstZ,-18,2+i,firstZ);
            fill(c,STONE,-22,10+i,secondZ,-21,10+i,secondZ);
            c.pen("minecraft:stone_brick_stairs[facing=north,half=bottom,shape=straight]").fill(Box.of(-19,3+i,firstZ,-18,3+i,firstZ));
            c.pen("minecraft:stone_brick_stairs[facing=south,half=bottom,shape=straight]").fill(Box.of(-22,11+i,secondZ,-21,11+i,secondZ));
            fill(c,STONE,-20,3+i,firstZ,-20,4+i,firstZ);
            fill(c,STONE,-23,11+i,secondZ,-23,12+i,secondZ);
        }
        fill(c,STONE,-22,10,-11,-18,10,-10);
        fill(c,STONE,-22,18,-2,-17,18,-1);
        // Genuine doors through two-block walls; no entrance metadata is used to hide a sealed room.
        c.pen("air").fill(Box.of(-12,3,-3,-10,6,-1));
        c.pen("air").fill(Box.of(-17,11,-10,-15,14,-9));
        c.pen("air").fill(Box.of(8,3,-5,10,5,-3));
        // Two recessed front bays and long slit windows limit glare without indiscriminate ornament.
        for(int y:new int[]{5,13})for(int x:new int[]{-15,-8})recessNorthSouth(c,x,y,-1,2,false);
        for(int x:new int[]{4,13})recessNorthSouth(c,x,4,-3,3,false);
        if(refined) {
            // First clay review exposes the blank north wall. Repair its depth, not its colour noise.
            for(int y:new int[]{6,14})recessNorthSouth(c,-14,y,-13,7,true);
            for(int x:new int[]{-17,-5})fill(c,WOOD,x,3,-14,x,17,-14);
            for(int y:new int[]{6,13}) {
                c.pen("air").fill(Box.of(-5,y,-10,-5,y+2,-6));
                fill(c,GLASS,-6,y,-10,-6,y+2,-6);
            }
        }
        // Arrival is a legible turn, not a mandatory straight ceremonial axis across the whole site.
        route(c,4,6,6,20);route(c,-19,6,6,8);route(c,-12,-1,-10,8);
        route(c,8,-3,10,8);route(c,-19,-1,-18,8);
        fill(c,ROOF,-12,2,6,10,2,8);
        a.entrance("arrival",new Vec3i(5,3,15),"SOUTH",ROOF,3);
        a.protect(Box.of(-3,3,4,18,10,15));
        // Separate actual vegetation/access clearance intent from variation protection.
        // The two empty subregions exclude the floor, arrival fixture and threshold furniture.
        a.keepClear(Box.of(-3,3,4,2,10,15));
        a.keepClear(Box.of(4,3,4,18,10,15));
        if(refined) {
            // Second review: give the bare path a human-scale threshold without filling the court.
            for(int x:new int[]{6,12})fill(c,WOOD,x,3,2,x,6,2);
            fill(c,WOOD,6,6,2,12,6,2);
            c.pen("minecraft:deepslate_tile_slab[type=bottom]").fill(Box.of(5,7,-2,13,7,3));
            c.pen("minecraft:dark_oak_slab[type=bottom]").fill(Box.of(14,3,1,16,3,1));
            fill(c,WOOD,3,3,0,4,3,1); // A pack/instrument preparation table beside, not across, the route.
            a.hangingLightFixture("threshold_lamp",new Vec3i(7,5,0),new Vec3i(7,7,0));
            a.component("arrival_threshold",Box.of(3,3,-2,16,7,3));
        }

        // Furniture stays off the named centres and circulation lines.
        fill(c,WOOD,-14,3,-10,-13,3,-8);
        fill(c,WOOD,14,3,-10,16,3,-10);
        a.container(new Vec3i(15,3,-6),BlockStateRef.parse("minecraft:barrel[facing=up]"),List.of(new AuthoringContext.Item(0,"minecraft:paper",12)));
        // Real beam-supported lamps per storey; never a universal floor-light grid.
        for(int y:new int[]{7,15})for(int x:new int[]{-14,-8})
            a.hangingLightFixture("tower_lamp_"+x+"_"+y,new Vec3i(x,y,-7),new Vec3i(x,y+3,-7));
        for(int x:new int[]{5,10,15})a.hangingLightFixture("service_lamp_"+x,new Vec3i(x,6,-8),new Vec3i(x,7,-8));
        for(int x:new int[]{-14,-8})a.hangingLightFixture("deck_lamp_"+x,new Vec3i(x,23,-7),new Vec3i(x,25,-7));
        a.lightFixture("stair_turn",new Vec3i(-20,11,-11),BlockStateRef.of("minecraft:lantern"),15);
        a.lightFixture("arrival_lamp",new Vec3i(3,3,12),BlockStateRef.of("minecraft:lantern"),15);
        a.component("main_tower",Box.of(-18,3,-14,-4,25,0));
        a.component("low_service_wing",Box.of(0,3,-14,20,9,-2));
        a.component("open_court",Box.of(-3,3,4,18,10,15));
        a.component("switchback_stair",Box.of(-23,3,-11,-17,20,-1));
        a.component("arrival_route",Box.of(-19,2,6,10,5,20));
        return a.snapshot();
    }

    private static void fill(DrawCanvas c,BlockStateRef state,int x0,int y0,int z0,int x1,int y1,int z1) {
        c.pen(Brush.solid(state)).fill(Box.of(x0,y0,z0,x1,y1,z1));
    }
    private static void route(DrawCanvas c,int x0,int z0,int x1,int z1) {
        fill(c,ROOF,x0,2,z0,x1,2,z1);
    }
    private static void recessNorthSouth(DrawCanvas c,int x,int y,int z,int width,boolean north) {
        c.pen("air").fill(Box.of(x,y,z,x+width-1,y+2,z));
        fill(c,GLASS,x,y,z+(north?1:-1),x+width-1,y+2,z+(north?1:-1));
    }
}

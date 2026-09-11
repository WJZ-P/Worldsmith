import com.wjz.worldsmith.authoring.*;
import com.wjz.worldsmith.core.draw.*;
import java.util.*;

/** Small world-specific craft vocabulary; geometry and access/light metadata are authored together. */
public final class EclipseCraft {
    private EclipseCraft() {}
    public static final BlockStateRef FOUNDATION=BlockStateRef.of("minecraft:deepslate_bricks");
    public static final BlockStateRef MOON=BlockStateRef.of("worldsmith:content/moonstone");
    public static final BlockStateRef SLATE=BlockStateRef.of("worldsmith:content/runed_slate");
    public static final BlockStateRef BRONZE=BlockStateRef.of("worldsmith:content/bronze_masonry");
    public static final BlockStateRef GLASS=BlockStateRef.of("worldsmith:content/star_glass");
    public static final BlockStateRef ROAD=BlockStateRef.of("minecraft:stone_bricks");
    public static DrawCanvas begin(AuthoringContext a,int rx,int rz,int height) {
        var c=a.canvas(Box.of(-rx,0,-rz,rx,height-1,rz));a.origin(new Vec3i(0,0,0));
        c.pen("air").fill(Box.of(-rx,3,-rz,rx,height-1,rz));
        a.material("foundation",FOUNDATION).material("road",ROAD).material("stair",BlockStateRef.of("minecraft:stone_brick_stairs"));
        c.pen(Brush.solid(FOUNDATION)).fill(Box.of(-rx,0,-rz,rx,2,rz));
        c.pen(Brush.solid(ROAD)).fill(Box.of(-rx,2,-rz,rx,2,rz));
        return c;
    }
    public static void fill(DrawCanvas c,BlockStateRef block,int x0,int y0,int z0,int x1,int y1,int z1) {
        c.pen(Brush.solid(block)).fill(Box.of(x0,y0,z0,x1,y1,z1));
    }
    public static void boxRoom(AuthoringContext a,String id,int x0,int z0,int x1,int z1,int ceiling,BlockStateRef wall,BlockStateRef floor) {
        var c=a.canvas();fill(c,wall,x0-1,3,z0-1,x1+1,ceiling,z1+1);
        a.room(id,Box.of(x0,3,z0,x1,ceiling-1,z1),floor);
    }
    public static void lightFloor(AuthoringContext a,String id,int x0,int z0,int x1,int z1) {
        Set<Integer> xs=new LinkedHashSet<>(),zs=new LinkedHashSet<>();for(int x=x0;x<=x1;x+=5)xs.add(x);xs.add(x1);
        for(int z=z0;z<=z1;z+=5)zs.add(z);zs.add(z1);int i=0;
        for(int x:xs)for(int z:zs)if(a.canvas().get(new Vec3i(x,3,z)).map(b->b.state().isAir()).orElse(false))
            a.lightFixture(id+"_"+(i++),new Vec3i(x,2,z),BlockStateRef.of("minecraft:sea_lantern"),15);
    }
    public static void exit(AuthoringContext a,String id,int x,int z,String facing) {
        var b=a.canvas().bounds();var c=a.canvas();
        int ex=facing.equals("EAST")?b.max().x():facing.equals("WEST")?b.min().x():x;
        int ez=facing.equals("NORTH")?b.min().z():facing.equals("SOUTH")?b.max().z():z;
        int dx=facing.equals("NORTH")||facing.equals("SOUTH")?1:0,dz=dx==0?1:0;
        c.pen("air").fill(Box.of(Math.min(x,ex)-dx,3,Math.min(z,ez)-dz,Math.max(x,ex)+dx,5,Math.max(z,ez)+dz));
        fill(c,ROAD,Math.min(x,ex)-dx,2,Math.min(z,ez)-dz,Math.max(x,ex)+dx,2,Math.max(z,ez)+dz);
        a.entrance(id,new Vec3i(x,3,z),facing,ROAD,3);
    }
    public static void gable(DrawCanvas c,int halfWidth,int z0,int z1,int baseY,String roof) {
        for(int rise=0;rise<=halfWidth;rise++) {
            int x=halfWidth-rise;
            c.pen(roof).fill(Box.of(-x,baseY+rise,z0,x,baseY+rise,z1));
            if(x>0)for(int side:new int[]{-1,1})c.pen("minecraft:polished_blackstone_brick_stairs[facing="+(side<0?"east":"west")+"]")
                .fill(Box.of(side*x,baseY+rise,z0,side*x,baseY+rise,z1));
        }
    }
    public static void parapet(DrawCanvas c,int x0,int z0,int x1,int z1,int y,BlockStateRef block) {
        fill(c,block,x0,y,z0,x1,y,z0);fill(c,block,x0,y,z1,x1,y,z1);fill(c,block,x0,y,z0,x0,y,z1);fill(c,block,x1,y,z0,x1,y,z1);
        for(int x=x0;x<=x1;x+=2){fill(c,block,x,y+1,z0,x,y+1,z0);fill(c,block,x,y+1,z1,x,y+1,z1);}
        for(int z=z0;z<=z1;z+=2){fill(c,block,x0,y+1,z,x0,y+1,z);fill(c,block,x1,y+1,z,x1,y+1,z);}
    }
    public static void chest(AuthoringContext a,int x,int z,String item,int count) {
        a.container(new Vec3i(x,3,z),BlockStateRef.parse("minecraft:chest[facing=north]"),List.of(new AuthoringContext.Item(0,item,count),new AuthoringContext.Item(4,"minecraft:bread",4),new AuthoringContext.Item(8,"minecraft:torch",8)));
    }
    public static void window(DrawCanvas c,int x,int y,int z,int width,boolean alongX) {
        for(int i=0;i<width;i++)fill(c,GLASS,x+(alongX?i:0),y,z+(alongX?0:i),x+(alongX?i:0),y+3,z+(alongX?0:i));
    }
}

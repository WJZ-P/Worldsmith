import com.wjz.worldsmith.core.draw.*;
import com.wjz.worldsmith.authoring.*;

/** A composable teaching example, not a production architectural style preset. */
public final class Pavilion implements StructureProgram {
    public AuthoredStructure generate(AuthoringContext a) {
        String kind=a.parameters().getOrDefault("kind","grand");
        int n=kind.equals("grand")?19:kind.equals("court")?8:7;
        int h=kind.equals("grand")?35:kind.equals("court")?10:12;
        var c=a.canvas(Box.of(-n,0,-n,n,h-1,n));a.origin(new Vec3i(0,0,0));
        a.material("foundation",Materials.stone());
        c.pen(Brush.solid(Materials.stone())).fill(Box.of(-n,0,-n,n,3,n));
        a.room("hall",Box.of(-n+1,4,-n+1,n-1,h-3,n-1),Materials.stone());
        for(int x:new int[]{-n+2,n-4})for(int z:new int[]{-n+2,n-4})
            c.pen(Brush.solid(Materials.stone())).fill(Box.of(x,4,z,x+2,h-3,z+2));
        c.pen(Materials.roof()).fill(Box.of(-n,h-2,-n,n,h-2,n));
        a.component("roof",Box.of(-n,h-2,-n,n,h-2,n));
        var points=new java.util.LinkedHashSet<Vec3i>();
        for(int x=-n+2;x<n;x+=5)for(int z=-n+2;z<n;z+=5)
            if(c.get(new Vec3i(x,4,z)).orElseThrow().state().isAir())points.add(new Vec3i(x,3,z));
        // Floor lights under solid columns do not illuminate the surrounding room.
        // Perimeter strips cover the narrow passages behind the corner columns.
        for(int t=-n+1;t<n;t+=4) {
            points.add(new Vec3i(t,3,-n+1));points.add(new Vec3i(t,3,n-1));
            points.add(new Vec3i(-n+1,3,t));points.add(new Vec3i(n-1,3,t));
        }
        points.add(new Vec3i(n-1,3,n-1));
        int index=0;for(var point:points)a.lightFixture("floor_light_"+(index++),point,BlockStateRef.of("glowstone"),15);
        a.entrance("north",new Vec3i(0,4,-n+1),"NORTH",Materials.stone(),3);
        a.entrance("east",new Vec3i(n-1,4,0),"EAST",Materials.stone(),3);
        return a.snapshot();
    }
}

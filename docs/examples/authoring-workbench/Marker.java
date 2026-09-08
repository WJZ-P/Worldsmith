import com.wjz.worldsmith.core.draw.*;
import com.wjz.worldsmith.authoring.*;
public final class Marker implements StructureProgram {
    public AuthoredStructure generate(AuthoringContext a) {
        var c=a.canvas(Box.of(-4,0,-4,4,7,4));a.origin(new Vec3i(0,0,0));
        c.pen("stone_bricks").fill(Box.of(-4,0,-4,4,2,4));
        c.pen("air").fill(Box.of(-4,3,-4,4,7,4));
        for(int x:new int[]{-3,3})for(int z:new int[]{-3,3})c.pen("stone_bricks").fill(Box.of(x,3,z,x,5,z));
        c.pen("dark_oak_planks").fill(Box.of(-4,6,-4,4,6,4));a.component("roof",Box.of(-4,6,-4,4,6,4));
        return a.snapshot();
    }
}

import com.wjz.worldsmith.core.draw.*;

/** Field-shape demonstration only: every world should author its own design. */
public final class CourtyardProgram implements DrawProgram {
    public DrawStructure generate(DrawContext context) {
        String kind = context.parameters().getOrDefault("kind", "grand");
        int n = switch (kind) { case "grand" -> 19; case "court" -> 8; case "wing" -> 7; default -> 4; };
        int h = switch (kind) { case "grand" -> 35; case "court" -> 10; case "wing" -> 12; default -> 8; };
        var canvas = context.canvas(Box.of(-n, 0, -n, n, h - 1, n));
        canvas.pen("stone_bricks").fill(Box.of(-n, 0, -n, n, 3, n));
        canvas.pen("air").fill(Box.of(-n, 4, -n, n, h - 1, n));
        for (int x : new int[] {-n + 2, n - 4})
            for (int z : new int[] {-n + 2, n - 4})
                canvas.pen("stone_bricks").fill(Box.of(x, 4, z, x + 2, h - 3, z + 2));
        canvas.pen("dark_oak_planks").fill(Box.of(-n, h - 2, -n, n, h - 2, n));
        canvas.anchor("north_entry", new Vec3i(0, 4, -n));
        return canvas.snapshot();
    }
}

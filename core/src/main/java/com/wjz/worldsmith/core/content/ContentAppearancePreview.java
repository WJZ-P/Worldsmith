package com.wjz.worldsmith.core.content;

import java.awt.*;
import java.awt.geom.AffineTransform;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;

/** Actual PNG sampling for material review, never a Minecraft screenshot or an aesthetic score. */
public final class ContentAppearancePreview {
    public static final String VERSION = "worldsmith-content-appearance-v1";
    private static final Color PAPER = new Color(0xf1eee7), INK = new Color(0x263247), MUTED = new Color(0x667082);
    private ContentAppearancePreview() {}
    public record Result(byte[] png, Map<String, Object> metadata) {
        public Result { png = png.clone(); metadata = Map.copyOf(metadata); }
        @Override public byte[] png() { return png.clone(); }
    }

    public static Result block(CustomBlockDefinition definition, Map<String, byte[]> assets) throws IOException {
        if (!CustomBlockValidation.validate(new CustomBlockLibrary(2, List.of(definition))).isEmpty())
            throw new IllegalArgumentException("Block definition must pass appearance validation before preview");
        var appearance = definition.getAppearance();
        Map<String, BufferedImage> decoded = new LinkedHashMap<>();
        Map<String, Object> metrics = new LinkedHashMap<>();
        for (String hash : appearance.assetIds()) {
            BufferedImage texture = decode(hash, assets.get(hash)); decoded.put(hash, texture); metrics.put(hash, metrics(texture));
        }
        BufferedImage image = canvas(1440, 1150); Graphics2D g = graphics(image);
        heading(g, "MATERIAL / " + definition.getDisplayName(), definition.getId() + "  ·  " + definition.getProfile() + "  ·  " + appearance.getOrientation() + "  ·  light " + definition.getLight());
        panel(g, 24, 106, 440, 408, "NORTH + EAST + UP / ACTUAL PNG");
        cube(g, appearance, decoded, 244, 316, 146, false);
        panel(g, 480, 106, 440, 408, "SOUTH + WEST + UP / ACTUAL PNG");
        cube(g, appearance, decoded, 700, 316, 146, true);
        panel(g, 936, 106, 480, 408, "LOCAL FACE PLANES / UV ROTATION APPLIED");
        String[] names = {"up", "down", "north", "south", "west", "east"};
        for (int i = 0; i < names.length; i++) {
            var face = appearance.faces().get(names[i]); int x = 954 + i % 3 * 150, y = 160 + i / 3 * 162;
            checker(g, x, y, 132, 132); g.drawImage(oriented(decoded.get(face.getTextureAsset()), face.getQuarterTurns()), x, y, 132, 132, null);
            label(g, names[i] + "  " + face.getQuarterTurns() * 90 + "°", x, y - 8, 13, INK);
        }
        panel(g, 24, 532, 664, 498, "3 × 3 NORTH TILE / REPETITION AND SEAMS");
        BufferedImage front = oriented(decoded.get(appearance.getNorth().getTextureAsset()), appearance.getNorth().getQuarterTurns());
        checker(g, 43, 583, 435, 435);
        for (int y = 0; y < 3; y++) for (int x = 0; x < 3; x++) g.drawImage(front, 43 + x * 145, 583 + y * 145, 145, 145, null);
        label(g, "Native texel sizes", 496, 610, 13, INK);
        for (int i = 0; i < 3; i++) { int size = 16 << i; checker(g, 498, 636 + i * 105, size, size); g.drawImage(front, 498, 636 + i * 105, size, size, null); label(g, size + " px", 576, 650 + i * 105, 12, MUTED); }
        panel(g, 704, 532, 712, 498, "FACE NET / LABELLED LOCAL COORDINATES");
        String[] net = {"up", "west", "north", "east", "south", "down"};
        int[][] at = {{1,0},{0,1},{1,1},{2,1},{3,1},{1,2}};
        for (int i = 0; i < net.length; i++) {
            var face = appearance.faces().get(net[i]); int x = 728 + at[i][0] * 166, y = 594 + at[i][1] * 136;
            checker(g, x, y, 122, 122); g.drawImage(oriented(decoded.get(face.getTextureAsset()), face.getQuarterTurns()), x, y, 122, 122, null);
            label(g, net[i] + " / " + face.getTextureAsset().substring(0, 8), x, y - 6, 11, MUTED);
        }
        label(g, "Particle: " + appearance.getParticle().substring(0, 16), 1080, 1009, 12, MUTED);
        footer(g, 1070, "Offline texture sampling · simplified studio lighting · no native culling, translucency sorting or gameplay claim.");
        footer(g, 1095, "Tile edge deltas and alpha coverage are inspection metrics, not automatic artistic-quality or seamlessness judgments.");
        g.dispose();
        Map<String, Object> metadata = base("block", definition.getId(), image);
        metadata.put("orientation", appearance.getOrientation().name()); metadata.put("faces", faceMetadata(appearance));
        metadata.put("particle", appearance.getParticle()); metadata.put("assets", metrics); metadata.put("sampling", "nearest; native cube face UV planes; declared clockwise quarter turns");
        return new Result(encode(image), metadata);
    }

    /** Bounded contact sheet keeps icons comparable at actual 16/32 px and on both inventory-like backgrounds. */
    public static Result items(List<CustomItemDefinition> definitions, Map<String, byte[]> assets) throws IOException {
        if (definitions.isEmpty() || definitions.size() > 8) throw new IllegalArgumentException("Preview 1 through 8 item icons together");
        BufferedImage image = canvas(1200, 150 + definitions.size() * 230); Graphics2D g = graphics(image);
        heading(g, "ITEM ICONS / SILHOUETTE REVIEW", "Actual PNGs · native-scale samples and nearest magnification · no inventory renderer simulation");
        Map<String, Object> metrics = new LinkedHashMap<>();
        for (int i = 0; i < definitions.size(); i++) {
            var definition = definitions.get(i); BufferedImage texture = decode(definition.getTextureAsset(), assets.get(definition.getTextureAsset()));
            int y = 112 + i * 230;
            label(g, definition.getDisplayName(), 26, y + 24, 18, INK); label(g, definition.getId(), 26, y + 49, 12, MUTED);
            label(g, texture.getWidth() + " px / " + definition.getTextureAsset().substring(0, 12), 26, y + 73, 12, MUTED);
            for (int background = 0; background < 2; background++) {
                int x = 340 + background * 420; g.setColor(new Color(background == 0 ? 0x242938 : 0xe1d8c8)); g.fillRoundRect(x, y, 406, 208, 12, 12);
                int[] sizes = {16, 32, 128}; int[] offsets = {28, 96, 224};
                for (int k = 0; k < sizes.length; k++) {
                    int size = sizes[k]; g.drawImage(texture, x + offsets[k], y + (170 - size) / 2, size, size, null);
                    label(g, size + " px", x + offsets[k] - 2, y + 186, 12, background == 0 ? Color.WHITE : INK);
                }
            }
            metrics.put(definition.getId(), Map.of("texture", definition.getTextureAsset(), "metrics", metrics(texture)));
        }
        footer(g, image.getHeight() - 13, "Offline icon review only. Wearable armor UVs and native held-item transforms are separate validation targets."); g.dispose();
        Map<String, Object> metadata = base("items", definitions.stream().map(CustomItemDefinition::getId).toList(), image);
        metadata.put("items", metrics); metadata.put("sampling", "nearest on opaque light and dark backgrounds");
        return new Result(encode(image), metadata);
    }

    /** Shared by contact sheets and tests: exact clockwise rotation, preserving RGBA and dimensions. */
    public static BufferedImage oriented(BufferedImage source, int quarterTurns) {
        if (quarterTurns < 0 || quarterTurns > 3 || source.getWidth() != source.getHeight()) throw new IllegalArgumentException("Square face and 0..3 turns required");
        int n = source.getWidth(); BufferedImage output = new BufferedImage(n, n, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < n; y++) for (int x = 0; x < n; x++) {
            int sx = x, sy = y;
            for (int turn = 0; turn < quarterTurns; turn++) { int old = sx; sx = sy; sy = n - 1 - old; }
            output.setRGB(x, y, source.getRGB(sx, sy));
        }
        return output;
    }
    private static void cube(Graphics2D g, BlockAppearance appearance, Map<String, BufferedImage> assets, int cx, int cy, double scale, boolean back) {
        // Vertex order exactly matches native FaceInfo: 0/1/2/3 map to TL/BL/BR/TR before UV rotation.
        Map<String, double[][]> faces = new LinkedHashMap<>();
        faces.put("up", new double[][]{{0,1,0},{0,1,1},{1,1,1},{1,1,0}});
        if (!back) {
            faces.put("east", new double[][]{{1,1,1},{1,0,1},{1,0,0},{1,1,0}});
            faces.put("north", new double[][]{{1,1,0},{1,0,0},{0,0,0},{0,1,0}});
        } else {
            faces.put("west", new double[][]{{0,1,0},{0,0,0},{0,0,1},{0,1,1}});
            faces.put("south", new double[][]{{0,1,1},{0,0,1},{1,0,1},{1,1,1}});
        }
        for (var entry : faces.entrySet()) {
            double[][] p = new double[4][2];
            for (int i = 0; i < 4; i++) {
                double x = entry.getValue()[i][0] - .5, y = entry.getValue()[i][1] - .5, z = entry.getValue()[i][2] - .5;
                if (back) { x = -x; z = -z; }
                p[i][0] = cx - (x + z) * scale; p[i][1] = cy + (.5 * x - y - .5 * z) * scale;
            }
            var face = appearance.faces().get(entry.getKey()); BufferedImage texture = oriented(assets.get(face.getTextureAsset()), face.getQuarterTurns());
            Path2D clip = new Path2D.Double(); clip.moveTo(p[0][0], p[0][1]); for (int i = 1; i < 4; i++) clip.lineTo(p[i][0], p[i][1]); clip.closePath();
            Shape previous = g.getClip(); g.clip(clip); int n = texture.getWidth();
            g.drawImage(texture, new AffineTransform((p[3][0]-p[0][0])/n, (p[3][1]-p[0][1])/n, (p[1][0]-p[0][0])/n, (p[1][1]-p[0][1])/n, p[0][0], p[0][1]), null);
            g.setColor(new Color(0, 0, 0, entry.getKey().equals("up") ? 0 : entry.getKey().equals("east") || entry.getKey().equals("west") ? 28 : 48)); g.fill(clip);
            g.setClip(previous); g.setColor(new Color(0x738094)); g.draw(clip);
        }
    }
    private static Map<String, Object> faceMetadata(BlockAppearance appearance) {
        Map<String, Object> result = new LinkedHashMap<>(); appearance.faces().forEach((name, face) -> result.put(name, Map.of("texture", face.getTextureAsset(), "quarterTurns", face.getQuarterTurns()))); return result;
    }
    private static BufferedImage decode(String hash, byte[] bytes) throws IOException {
        if (bytes == null || bytes.length > 1024 * 1024) throw new IllegalArgumentException("Missing or oversized preview PNG: " + hash);
        var size = ContentAssetValidation.INSTANCE.verify(new ContentAsset(hash, hash, "image/png", (long)bytes.length, ContentAssetValidation.INSTANCE.path(hash)), bytes);
        if (size.getWidth() != size.getHeight() || size.getWidth() < 16 || size.getWidth() > 256 || (size.getWidth() & (size.getWidth() - 1)) != 0)
            throw new IllegalArgumentException("Material and icon previews require native square power-of-two PNGs, 16..256");
        return ImageIO.read(new ByteArrayInputStream(bytes));
    }
    private static Map<String, Object> metrics(BufferedImage image) {
        int width = image.getWidth(), height = image.getHeight(), transparent = 0, partial = 0; long horizontal = 0, vertical = 0;
        for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) { int alpha = image.getRGB(x,y) >>> 24; if (alpha == 0) transparent++; else if (alpha < 255) partial++; }
        for (int y = 0; y < height; y++) horizontal += rgbaDelta(image.getRGB(0,y), image.getRGB(width-1,y));
        for (int x = 0; x < width; x++) vertical += rgbaDelta(image.getRGB(x,0), image.getRGB(x,height-1));
        return Map.of("width",width,"height",height,"transparentFraction",transparent/(double)(width*height),"partialAlphaFraction",partial/(double)(width*height),
            "leftRightMeanRgbaDelta", horizontal/(double)(height*4), "topBottomMeanRgbaDelta", vertical/(double)(width*4));
    }
    private static int rgbaDelta(int a,int b) { int n=0; for(int shift=0;shift<32;shift+=8)n+=Math.abs((a>>>shift&255)-(b>>>shift&255)); return n; }
    private static Map<String,Object> base(String kind,Object id,BufferedImage image) { Map<String,Object> result=new LinkedHashMap<>(); result.put("renderer",VERSION);result.put("kind",kind);result.put("content",id);result.put("minecraftScreenshot",false);result.put("aestheticQualityValidated",false);result.put("width",image.getWidth());result.put("height",image.getHeight());return result; }
    private static BufferedImage canvas(int width,int height) { var image=new BufferedImage(width,height,BufferedImage.TYPE_INT_ARGB);var g=image.createGraphics();g.setColor(PAPER);g.fillRect(0,0,width,height);g.dispose();return image; }
    private static Graphics2D graphics(BufferedImage image) { var g=image.createGraphics();g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,RenderingHints.VALUE_TEXT_ANTIALIAS_ON);return g; }
    private static void heading(Graphics2D g,String title,String subtitle) {label(g,title,26,44,26,INK);label(g,subtitle,27,75,13,MUTED);}
    private static void panel(Graphics2D g,int x,int y,int w,int h,String title) {g.setColor(Color.WHITE);g.fillRoundRect(x,y,w,h,14,14);label(g,title,x+16,y+27,12,MUTED);}
    private static void label(Graphics2D g,String text,int x,int y,int size,Color color) {g.setColor(color);g.setFont(new Font(Font.DIALOG,Font.PLAIN,size));g.drawString(text,x,y);}
    private static void footer(Graphics2D g,int y,String text) {label(g,text,27,y,12,MUTED);}
    private static void checker(Graphics2D g,int x,int y,int w,int h) {Shape old=g.getClip();g.clipRect(x,y,w,h);for(int yy=0;yy<h;yy+=8)for(int xx=0;xx<w;xx+=8){g.setColor(new Color((xx/8+yy/8)%2==0?0xd9dee4:0xf8f9fb));g.fillRect(x+xx,y+yy,8,8);}g.setClip(old);}
    private static byte[] encode(BufferedImage image) throws IOException {ByteArrayOutputStream bytes=new ByteArrayOutputStream();if(!ImageIO.write(image,"png",bytes))throw new IOException("PNG encoder unavailable");return bytes.toByteArray();}
}

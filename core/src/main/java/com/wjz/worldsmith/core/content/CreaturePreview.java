package com.wjz.worldsmith.core.content;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.io.*;
import java.util.*;
import java.util.List;
import javax.imageio.ImageIO;

/**
 * Bounded CPU authoring preview: native box-UV topology, parent-relative cuboids, shared poses and
 * per-pixel depth. This is an offline model rendering, not a Minecraft screenshot or combat simulation.
 */
public final class CreaturePreview {
    public static final List<String> VIEWS = List.of("isometric", "isometric_back", "front", "back", "left", "right", "top");
    public static final List<String> POSES = CreaturePose.POSES;
    public static final String RENDERER_VERSION = "worldsmith-creature-preview-v2";
    private static final int MAX_SIZE = 1600;
    private static final long MAX_RASTER_WORK = 120_000_000L;
    private static final Color INK = new Color(0x263048);
    private static final Color MUTED = new Color(0x657084);
    private static final V3 LIGHT = new V3(-.42, .80, -.43).normalized();
    private CreaturePreview() {}

    public record Options(String view, String pose, int width, int height, float tick, float headYaw, float headPitch,
                          long seed, boolean transparent, boolean clay, boolean showBounds, boolean labels, int bossPhase) {
        public Options(String view, String pose, int width, int height, float tick, float headYaw, float headPitch,
                       long seed, boolean transparent, boolean clay, boolean showBounds, boolean labels) {
            this(view,pose,width,height,tick,headYaw,headPitch,seed,transparent,clay,showBounds,labels,0);
        }
        public Options {
            if (!VIEWS.contains(view) || !POSES.contains(pose)) throw new IllegalArgumentException("Unsupported creature view or pose");
            if (width < 256 || height < 256 || width > MAX_SIZE || height > MAX_SIZE) throw new IllegalArgumentException("Preview dimensions must be 256 through 1600 pixels");
            if (!Float.isFinite(tick) || Math.abs(tick) > 1_000_000 || !Float.isFinite(headYaw) || Math.abs(headYaw) > 180 || !Float.isFinite(headPitch) || Math.abs(headPitch) > 90)
                throw new IllegalArgumentException("Preview time and head angles must be finite and bounded");
            if (bossPhase < 0 || bossPhase > 2) throw new IllegalArgumentException("Boss preview phase must be 0..2");
        }
        public static Options defaults(String view, String pose) { return new Options(view, pose, 1280, 1000, 12, 0, 0, 0, false, false, false, true); }
        public Options withSize(int width, int height) { return new Options(view, pose, width, height, tick, headYaw, headPitch, seed, transparent, clay, showBounds, labels,bossPhase); }
        public Options withPose(String pose) { return new Options(view, pose, width, height, tick, headYaw, headPitch, seed, transparent, clay, showBounds, labels,bossPhase); }
        public Options withView(String view) { return new Options(view, pose, width, height, tick, headYaw, headPitch, seed, transparent, clay, showBounds, labels,bossPhase); }
        public Options withTime(float tick) { return new Options(view, pose, width, height, tick, headYaw, headPitch, seed, transparent, clay, showBounds, labels,bossPhase); }
        public Options withHead(float yaw, float pitch) { return new Options(view, pose, width, height, tick, yaw, pitch, seed, transparent, clay, showBounds, labels,bossPhase); }
        public Options withBossPhase(int phase) { return new Options(view,pose,width,height,tick,headYaw,headPitch,seed,transparent,clay,showBounds,labels,phase); }
        public Options withPresentation(boolean transparent, boolean clay, boolean bounds, boolean labels) {
            return new Options(view, pose, width, height, tick, headYaw, headPitch, seed, transparent, clay, bounds, labels,bossPhase);
        }
    }

    public record Result(byte[] png, Map<String, Object> metadata) {
        public Result { png = png.clone(); metadata = Map.copyOf(metadata); }
        public byte[] png() { return png.clone(); }
    }

    public static byte[] png(CreatureDefinition definition, byte[] texture, String view, String pose) throws IOException {
        return png(definition, texture, Options.defaults(view, pose));
    }
    public static byte[] png(CreatureDefinition definition, byte[] texture, Options options) throws IOException {
        return render(definition, texture, options).png();
    }

    public static Result render(CreatureDefinition definition, byte[] actualPng, Options options) throws IOException {
        CreatureDefinition frozen = validate(definition, actualPng);
        BufferedImage texture = ImageIO.read(new ByteArrayInputStream(actualPng));
        Bounds frame = comparisonFrame(frozen, options);
        Scene scene = scene(frozen, texture, options, frame);
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("renderer", RENDERER_VERSION); metadata.put("kind", "offline_model_preview"); metadata.put("minecraftScreenshot", false);
        metadata.put("creatureId", frozen.getId()); metadata.put("textureSha256", frozen.getModel().getTexture());
        metadata.put("textureWidth", texture.getWidth()); metadata.put("textureHeight", texture.getHeight());
        metadata.put("view", options.view); metadata.put("pose", options.pose); metadata.put("projection", "orthographic");
        metadata.put("width", options.width); metadata.put("height", options.height); metadata.put("boneCount", frozen.getModel().getBones().size());
        metadata.put("cubeCount", cubeCount(frozen)); metadata.put("visibleFaces", scene.visibleFaces); metadata.put("shadedPixels", scene.shadedPixels);
        metadata.put("frameModelUnits", List.of(frame.min.x, frame.min.y, frame.min.z, frame.max.x, frame.max.y, frame.max.z));
        metadata.put("cameraYawDegrees", scene.camera.yaw); metadata.put("cameraElevationDegrees", scene.camera.elevation);
        metadata.put("pixelsPerModelUnit", scene.camera.scale); metadata.put("sharedPoseEvaluator", "CreaturePose"); metadata.put("uvMapping", "native_box_uv");
        metadata.put("boss",frozen.getBoss()!=null);
        if(frozen.getBoss()!=null) {
            var phase=frozen.getBoss().getPhases().get(options.bossPhase);
            metadata.put("bossPhase",options.bossPhase);metadata.put("bossPhaseName",phase.getName());
            metadata.put("declaredPhaseParameters",Map.of("movementSpeed",frozen.getAttributes().getSpeed()*phase.getSpeedMultiplier(),
                "attackDamage",frozen.getAttributes().getAttackDamage()*phase.getDamageMultiplier(),"windupTicks",phase.getWindupTicks(),
                "recoveryTicks",phase.getRecoveryTicks(),"poseIntensity",phase.getPoseIntensity()));
        }
        metadata.put("cutoutAlphaThreshold", 26); metadata.put("limitations", List.of("Simplified studio illumination", "No Minecraft render pipeline", "No server AI or combat simulation"));
        return new Result(encode(scene.image), metadata);
    }

    /** Seven fixed views, all five poses and the actual atlas, in one bounded authoring contact sheet. */
    public static byte[] sheet(CreatureDefinition definition, byte[] actualPng) throws IOException {
        return sheet(definition,actualPng,0);
    }
    public static byte[] sheet(CreatureDefinition definition, byte[] actualPng, int bossPhase) throws IOException {
        CreatureDefinition frozen = validate(definition, actualPng);
        BufferedImage texture = ImageIO.read(new ByteArrayInputStream(actualPng));
        Options base = Options.defaults("isometric", "idle").withSize(374, 290).withPresentation(false, false, false, false).withBossPhase(bossPhase);
        poseFrame(frozen,base);
        Bounds frame = comparisonFrame(frozen, base);
        BufferedImage sheet = new BufferedImage(1600, 1160, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = sheet.createGraphics(); quality(g); g.setColor(new Color(0xF5F5F7)); g.fillRect(0, 0, 1600, 1160);
        g.setColor(INK); g.setFont(font(30, true)); g.drawString(frozen.getDisplayName()
            +(frozen.getBoss()==null?"":" — "+frozen.getBoss().getPhases().get(bossPhase).getName()), 34, 46);
        g.setColor(MUTED); g.setFont(font(13, false));
        g.drawString("CREATURE AUTHORING  /  FIXED CAMERAS  /  SHARED RUNTIME POSES  /  ACTUAL TEXTURE", 35, 69);
        List<String[]> cells = List.of(new String[]{"isometric", "idle"}, new String[]{"front", "idle"}, new String[]{"back", "idle"}, new String[]{"isometric_back", "idle"},
            new String[]{"left", "idle"}, new String[]{"right", "idle"}, new String[]{"top", "idle"}, new String[]{"isometric", "walk"},
            new String[]{"isometric", "windup"}, new String[]{"isometric", "strike"}, new String[]{"isometric", "recovery"});
        for (int i = 0; i < 12; i++) {
            int x = 34 + (i % 4) * 394, y = 91 + (i / 4) * 337;
            g.setColor(Color.WHITE); g.fillRoundRect(x, y, 374, 321, 10, 10);
            g.setColor(INK); g.setFont(font(12, true));
            if (i < cells.size()) {
                var choice = cells.get(i); Options options = base.withView(choice[0]).withPose(choice[1]);
                g.drawString(choice[0].toUpperCase(Locale.ROOT).replace('_', ' ') + "  /  " + choice[1].toUpperCase(Locale.ROOT), x + 13, y + 20);
                g.drawImage(scene(frozen, texture, options, frame).image, x, y + 29, null);
            } else {
                g.drawString("SOURCE UV ATLAS  /  " + texture.getWidth() + " × " + texture.getHeight(), x + 13, y + 20);
                int fit = Math.min(270, Math.min(342, 270)), ax = x + (374 - fit) / 2, ay = y + 37;
                checker(g, ax, ay, fit, fit); g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
                double scale = Math.min(fit / (double)texture.getWidth(), fit / (double)texture.getHeight());
                int tw = (int)Math.round(texture.getWidth() * scale), th = (int)Math.round(texture.getHeight() * scale);
                g.drawImage(texture, ax + (fit - tw) / 2, ay + (fit - th) / 2, tw, th, null);
            }
        }
        g.setColor(MUTED); g.setFont(font(13, false));
        g.drawString(frozen.getId() + "  ·  " + frozen.getModel().getBones().size() + " bones / " + cubeCount(frozen) + " cubes  ·  texture " + frozen.getModel().getTexture().substring(0, 16), 35, 1132);
        g.drawString("Offline textured model preview — not a Minecraft screenshot, animation capture or combat verification.", 35, 1152);
        g.dispose(); return encode(sheet);
    }

    /** Debug image only: overlays unfold rectangles on the exact texture; never a replacement skin asset. */
    public static byte[] uvDebug(CreatureDefinition definition, byte[] actualPng) throws IOException {
        CreatureDefinition frozen = validate(definition, actualPng);
        BufferedImage texture = ImageIO.read(new ByteArrayInputStream(actualPng));
        BufferedImage output = new BufferedImage(1500, 1160, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = output.createGraphics(); quality(g); g.setColor(new Color(0xF4F5F7)); g.fillRect(0, 0, 1500, 1160);
        g.setColor(INK); g.setFont(font(27, true)); g.drawString(frozen.getDisplayName() + " — UV atlas debug", 30, 45);
        g.setColor(MUTED); g.setFont(font(13, false)); g.drawString("Actual PNG + native box UV regions. This annotated image is not an importable texture.", 31, 69);
        double scale = Math.min(1024.0 / texture.getWidth(), 1024.0 / texture.getHeight());
        int width = (int)Math.round(texture.getWidth() * scale), height = (int)Math.round(texture.getHeight() * scale);
        int ox = 30, oy = 94; checker(g, ox, oy, width, height);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR); g.drawImage(texture, ox, oy, width, height, null);
        g.setStroke(new BasicStroke(1.2F));
        Map<String, UvIsland> islands = new LinkedHashMap<>(); int cubeIndex = 0;
        for (var bone : frozen.getModel().getBones()) for (var cube : bone.getCubes()) {
            cubeIndex++;
            String key = cube.getUv().getU() + "/" + cube.getUv().getV() + "/" + cube.getSize().getX() + "/" + cube.getSize().getY() + "/" + cube.getSize().getZ();
            UvIsland existing = islands.get(key);
            if (existing == null) islands.put(key, new UvIsland(cubeIndex, cube, new ArrayList<>(List.of(bone.getId()))));
            else existing.bones.add(bone.getId());
        }
        int legend = 0;
        for (var island : islands.values()) {
            Color colour = Color.getHSBColor((island.index * .618034F) % 1, .72F, .86F);
            g.setColor(colour);
            String[] faceNames = {"TOP", "BOTTOM", "L", "FRONT", "R", "BACK"};
            var rects = uvRects(island.cube);
            for (int f = 0; f < rects.length; f++) {
                var rect = rects[f]; int x = ox + (int)Math.round(rect[0] * scale), y = oy + (int)Math.round(rect[1] * scale);
                int w = (int)Math.round(rect[2] * scale), h = (int)Math.round(rect[3] * scale); g.drawRect(x, y, w, h);
                if (w >= 38 && h >= 18) {
                    g.setFont(font(10, true)); String label = "#" + island.index + " " + faceNames[f];
                    g.setColor(new Color(0xEFFFFFFF, true)); g.fillRect(x + 1, y + 1, Math.min(w - 2, g.getFontMetrics().stringWidth(label) + 4), 13);
                    g.setColor(colour); g.drawString(label, x + 3, y + 11);
                }
            }
            if (legend < 61) {
                int y = 111 + legend * 16; g.setColor(colour); g.fillRect(1082, y - 9, 8, 8);
                g.setFont(font(11, false)); g.setColor(INK);
                String label = "#" + island.index + " " + String.join(" + ", island.bones);
                if (label.length() > 48) label = label.substring(0, 45) + "...";
                g.drawString(label, 1099, y); legend++;
            }
        }
        g.setColor(MUTED); g.setFont(font(12, false));
        g.drawString("Top = model -Y; front = model -Z. Shared islands intentionally reuse the same pixels.", 31, 1137);
        g.drawString("SHA-256 " + frozen.getModel().getTexture(), 31, 1154);
        if (islands.size() > 61) g.drawString("+ " + (islands.size() - 61) + " islands; inspect the UV layout JSON.", 1082, 1124);
        g.dispose(); return encode(output);
    }

    public static Map<String, Object> capabilities() {
        return Map.of("renderer", RENDERER_VERSION, "views", VIEWS, "poses", POSES, "maximumFrameDimension", MAX_SIZE,
            "projection", "orthographic", "occlusion", "per-pixel z-buffer", "texture", "verified PNG with native box UV and nearest sampling",
            "runtimePoseParity", "shared CreaturePose evaluator", "minecraftScreenshot", false);
    }

    private static CreatureDefinition validate(CreatureDefinition definition, byte[] png) {
        Objects.requireNonNull(definition); Objects.requireNonNull(png);
        var library = new CreatureLibrary(definition.getBoss()==null?1:2, List.of(definition)); var diagnostics = CustomCreatureValidator.validate(library);
        if (!diagnostics.isEmpty()) throw new IllegalArgumentException("Invalid creature preview input: " + diagnostics.stream().limit(8).toList());
        String hash = definition.getModel().getTexture();
        var size = ContentAssetValidation.INSTANCE.verify(new ContentAsset(hash, hash, "image/png", (long)png.length, "assets/" + hash + ".png"), png);
        if (size.getWidth() != definition.getModel().getTextureWidth() || size.getHeight() != definition.getModel().getTextureHeight())
            throw new IllegalArgumentException("Actual PNG dimensions differ from the model's UV atlas");
        return CustomCreatureValidator.freeze(library).getCreatures().getFirst();
    }

    private static Scene scene(CreatureDefinition definition, BufferedImage texture, Options options, Bounds frame) {
        BufferedImage image = new BufferedImage(options.width, options.height, BufferedImage.TYPE_INT_ARGB);
        Camera camera = Camera.forView(options, frame);
        Graphics2D g = image.createGraphics(); quality(g);
        if (!options.transparent) {
            g.setPaint(new GradientPaint(0, 0, new Color(0xF0F0F3), 0, options.height, new Color(0xDCE0E6))); g.fillRect(0, 0, options.width, options.height);
            ScreenPoint floor = camera.project(new V3(0, 0, 0));
            double shadowWidth = Math.min(options.width * .55, Math.max(10, definition.getAttributes().getWidth() * 16) * camera.scale * 1.8);
            double shadowHeight = Math.max(5, shadowWidth * .15);
            for (int i = 14; i > 0; i--) {
                double multiplier = 1 + i * .055;
                g.setColor(new Color(58, 64, 85, 3)); g.fillOval((int)(floor.x - shadowWidth * multiplier / 2), (int)(floor.y - shadowHeight * multiplier / 2),
                    (int)(shadowWidth * multiplier), (int)(shadowHeight * multiplier));
            }
        }
        g.dispose();
        int[] pixels = ((DataBufferInt)image.getRaster().getDataBuffer()).getData(); double[] depth = new double[pixels.length]; Arrays.fill(depth, Double.NEGATIVE_INFINITY);
        List<Face> faces = faces(definition, poseFrame(definition,options));
        long[] work = {0, 0}; int visible = 0;
        for (Face face : faces) {
            if (face.normal.dot(camera.near) <= 1e-8) continue;
            visible++;
            double shade = .60 + .40 * Math.max(0, face.normal.dot(LIGHT));
            Projected[] projected = new Projected[4];
            for (int i = 0; i < 4; i++) { Vertex v = face.vertices[i]; projected[i] = new Projected(camera.project(v.position), v.u, v.v); }
            raster(projected[0], projected[1], projected[2], face, texture, shade, options.clay, pixels, depth, options.width, options.height, work);
            raster(projected[0], projected[2], projected[3], face, texture, shade, options.clay, pixels, depth, options.width, options.height, work);
        }
        g = image.createGraphics(); quality(g);
        if (options.showBounds) drawCollision(g, definition, camera);
        if (options.labels) {
            g.setColor(INK); g.setFont(font(Math.max(18, options.width / 39), true)); g.drawString(definition.getDisplayName(), 34, 47);
            g.setColor(MUTED); g.setFont(font(12, false));
            g.drawString(options.view.toUpperCase(Locale.ROOT).replace('_', ' ') + "   /   " + options.pose.toUpperCase(Locale.ROOT) + "   /   " + (options.clay ? "CLAY GEOMETRY" : "ACTUAL TEXTURE")
                + (definition.getBoss()==null?"":"   /   BOSS PHASE "+(options.bossPhase+1)), 35, 69);
            g.drawLine(34, options.height - 65, options.width - 34, options.height - 65);
            g.drawString(definition.getId() + "  ·  " + definition.getModel().getBones().size() + " bones  /  " + cubeCount(definition) + " cubes  ·  "
                + texture.getWidth() + " × " + texture.getHeight() + " PNG  ·  " + definition.getModel().getTexture().substring(0, 12), 35, options.height - 39);
            g.drawString("Offline textured model preview — not a Minecraft screenshot or combat verification.", 35, options.height - 18);
        }
        g.dispose(); return new Scene(image, camera, visible, work[1]);
    }

    private static void raster(Projected a, Projected b, Projected c, Face face, BufferedImage texture, double shade, boolean clay,
                               int[] pixels, double[] depth, int width, int height, long[] work) {
        double area = edge(a.p.x, a.p.y, b.p.x, b.p.y, c.p.x, c.p.y); if (Math.abs(area) < 1e-8) return;
        int minX = Math.max(0, (int)Math.floor(Math.min(a.p.x, Math.min(b.p.x, c.p.x))));
        int maxX = Math.min(width - 1, (int)Math.ceil(Math.max(a.p.x, Math.max(b.p.x, c.p.x))));
        int minY = Math.max(0, (int)Math.floor(Math.min(a.p.y, Math.min(b.p.y, c.p.y))));
        int maxY = Math.min(height - 1, (int)Math.ceil(Math.max(a.p.y, Math.max(b.p.y, c.p.y))));
        if (minX > maxX || minY > maxY) return;
        work[0] += (long)(maxX - minX + 1) * (maxY - minY + 1);
        if (work[0] > MAX_RASTER_WORK) throw new IllegalArgumentException("Creature preview raster work budget exceeded; reduce image dimensions or overlapping geometry");
        double minU = Arrays.stream(face.vertices).mapToDouble(v -> v.u).min().orElseThrow(), maxU = Arrays.stream(face.vertices).mapToDouble(v -> v.u).max().orElseThrow();
        double minV = Arrays.stream(face.vertices).mapToDouble(v -> v.v).min().orElseThrow(), maxV = Arrays.stream(face.vertices).mapToDouble(v -> v.v).max().orElseThrow();
        for (int y = minY; y <= maxY; y++) for (int x = minX; x <= maxX; x++) {
            double w0 = edge(b.p.x, b.p.y, c.p.x, c.p.y, x + .5, y + .5) / area;
            double w1 = edge(c.p.x, c.p.y, a.p.x, a.p.y, x + .5, y + .5) / area;
            double w2 = 1 - w0 - w1; if (w0 < -1e-9 || w1 < -1e-9 || w2 < -1e-9) continue;
            double z = w0 * a.p.depth + w1 * b.p.depth + w2 * c.p.depth; int index = y * width + x;
            if (z <= depth[index] + 1e-8) continue;
            int rgb = 0xFFC1C5CD;
            if (!clay) {
                double u = Math.max(minU, Math.min(maxU - 1e-7, w0 * a.u + w1 * b.u + w2 * c.u));
                double v = Math.max(minV, Math.min(maxV - 1e-7, w0 * a.v + w1 * b.v + w2 * c.v));
                rgb = texture.getRGB(Math.max(0, Math.min(texture.getWidth() - 1, (int)Math.floor(u))), Math.max(0, Math.min(texture.getHeight() - 1, (int)Math.floor(v))));
                if ((rgb >>> 24) < 26) continue;
            }
            depth[index] = z; pixels[index] = shade(rgb, shade); work[1]++;
        }
    }

    /** Exact 26.2 ModelPart.Cube topology. Coordinates remain in model units until camera projection. */
    private static List<Face> faces(CreatureDefinition definition, CreaturePose.Frame frame) {
        List<Face> result = new ArrayList<>(); Map<String, Transform> transforms = transforms(definition, frame);
        for (var bone : definition.getModel().getBones()) {
            Transform transform = transforms.get(bone.getId());
            for (var cube : bone.getCubes()) {
                var origin = cube.getOrigin(); var size = cube.getSize();
                double x0 = origin.getX(), x1 = x0 + size.getX(), y0 = origin.getY(), y1 = y0 + size.getY(), z0 = origin.getZ(), z1 = z0 + size.getZ();
                if (cube.getMirror()) { double swap = x0; x0 = x1; x1 = swap; }
                V3[] corners = {new V3(x0, y0, z0), new V3(x1, y0, z0), new V3(x1, y1, z0), new V3(x0, y1, z0),
                    new V3(x0, y0, z1), new V3(x1, y0, z1), new V3(x1, y1, z1), new V3(x0, y1, z1)};
                int[][] indices = {{5, 4, 0, 1}, {2, 3, 7, 6}, {0, 4, 7, 3}, {1, 0, 3, 2}, {5, 1, 2, 6}, {4, 5, 6, 7}};
                V3[] normals = {new V3(0, -1, 0), new V3(0, 1, 0), new V3(-1, 0, 0), new V3(0, 0, -1), new V3(1, 0, 0), new V3(0, 0, 1)};
                double[][] rects = uvRects(cube);
                for (int f = 0; f < 6; f++) {
                    double[] rect = rects[f]; double u0 = rect[0], u1 = rect[0] + rect[2];
                    double v0 = f == 1 ? rect[1] + rect[3] : rect[1], v1 = f == 1 ? rect[1] : rect[1] + rect[3];
                    double[] us = {u1, u0, u0, u1}, vs = {v0, v0, v1, v1}; Vertex[] vertices = new Vertex[4];
                    for (int i = 0; i < 4; i++) vertices[i] = new Vertex(transform.point(corners[indices[f][i]]).physical(), us[i], vs[i]);
                    if (cube.getMirror()) { Vertex v = vertices[0]; vertices[0] = vertices[3]; vertices[3] = v; v = vertices[1]; vertices[1] = vertices[2]; vertices[2] = v; }
                    V3 normal = normals[f]; if (cube.getMirror()) normal = new V3(-normal.x, normal.y, normal.z);
                    normal = transform.direction(normal); normal = new V3(normal.x, -normal.y, normal.z).normalized();
                    result.add(new Face(vertices, normal));
                }
            }
        }
        return result;
    }

    /** Rectangles use positive dimensions; the model bottom face has reversed V order in topology. */
    private static double[][] uvRects(CreatureCube cube) {
        double u = cube.getUv().getU(), v = cube.getUv().getV(), w = cube.getSize().getX(), h = cube.getSize().getY(), d = cube.getSize().getZ();
        return new double[][]{{u + d, v, w, d}, {u + d + w, v, w, d}, {u, v + d, d, h},
            {u + d, v + d, w, h}, {u + d + w, v + d, d, h}, {u + d + w + d, v + d, w, h}};
    }

    private static Map<String, Transform> transforms(CreatureDefinition definition, CreaturePose.Frame frame) {
        Map<String, Transform> result = new HashMap<>(); var pending = new ArrayList<>(definition.getModel().getBones());
        while (!pending.isEmpty()) {
            int before = pending.size();
            for (var iterator = pending.iterator(); iterator.hasNext();) {
                var bone = iterator.next(); if (bone.getParent() != null && !result.containsKey(bone.getParent())) continue;
                Transform local = Transform.bone(bone, CreaturePose.rotation(bone, frame));
                result.put(bone.getId(), bone.getParent() == null ? local : result.get(bone.getParent()).multiply(local)); iterator.remove();
            }
            if (before == pending.size()) throw new IllegalArgumentException("Invalid creature hierarchy");
        }
        return result;
    }

    /** One shared frame across all standard action poses and a sampled complete gait cycle. */
    private static Bounds comparisonFrame(CreatureDefinition definition, Options options) {
        Bounds frame = new Bounds();
        int phases=definition.getBoss()==null?1:definition.getBoss().getPhases().size();
        for(int phase=0;phase<phases;phase++)for (String pose : POSES) for (int sample = 0; sample < 17; sample++) {
            float tick = sample == 16 ? options.tick : (float)(sample * Math.PI * 2 / (16 * (pose.equals("walk") ? .6662 : .08)));
            for (var face : faces(definition, poseFrame(definition,options.withPose(pose).withTime(tick).withBossPhase(phase))))
                for (var vertex : face.vertices) frame.include(vertex.position);
        }
        if (options.showBounds) for (var point : collisionCorners(definition)) frame.include(point);
        if (!frame.valid()) throw new IllegalArgumentException("Creature preview has no finite geometry");
        return frame;
    }

    private static CreaturePose.Frame poseFrame(CreatureDefinition definition,Options options) {
        return CreaturePose.withBossPhase(CreaturePose.preview(options.pose,options.tick,options.headYaw,options.headPitch,options.seed),definition,options.bossPhase);
    }

    private static void drawCollision(Graphics2D g, CreatureDefinition definition, Camera camera) {
        V3[] box = collisionCorners(definition); g.setColor(new Color(22, 116, 160, 185));
        g.setStroke(new BasicStroke(1.25F, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10, new float[]{5, 4}, 0));
        for (int[] edge : new int[][]{{0,1},{1,2},{2,3},{3,0},{4,5},{5,6},{6,7},{7,4},{0,4},{1,5},{2,6},{3,7}}) {
            var a = camera.project(box[edge[0]]); var b = camera.project(box[edge[1]]); g.drawLine((int)a.x, (int)a.y, (int)b.x, (int)b.y);
        }
    }
    private static V3[] collisionCorners(CreatureDefinition definition) {
        double w = definition.getAttributes().getWidth() * 8, h = definition.getAttributes().getHeight() * 16;
        return new V3[]{new V3(-w,0,-w),new V3(w,0,-w),new V3(w,0,w),new V3(-w,0,w),new V3(-w,h,-w),new V3(w,h,-w),new V3(w,h,w),new V3(-w,h,w)};
    }

    private record V3(double x, double y, double z) {
        V3 add(V3 b) { return new V3(x + b.x, y + b.y, z + b.z); }
        V3 subtract(V3 b) { return new V3(x - b.x, y - b.y, z - b.z); }
        V3 physical() { return new V3(x, 24 - y, z); }
        double dot(V3 b) { return x * b.x + y * b.y + z * b.z; }
        V3 normalized() { double length = Math.sqrt(dot(this)); return new V3(x / length, y / length, z / length); }
    }
    private static final class Bounds {
        V3 min = new V3(Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY);
        V3 max = new V3(Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY);
        void include(V3 p) { min = new V3(Math.min(min.x,p.x),Math.min(min.y,p.y),Math.min(min.z,p.z)); max = new V3(Math.max(max.x,p.x),Math.max(max.y,p.y),Math.max(max.z,p.z)); }
        boolean valid() { return Double.isFinite(min.x + min.y + min.z + max.x + max.y + max.z); }
        V3 centre() { return new V3((min.x+max.x)/2,(min.y+max.y)/2,(min.z+max.z)/2); }
        double radius() { V3 delta = max.subtract(min); return Math.max(4, Math.sqrt(delta.dot(delta)) / 2); }
    }
    private record Vertex(V3 position, double u, double v) {}
    private record Face(Vertex[] vertices, V3 normal) {}
    private record Projected(ScreenPoint p, double u, double v) {}
    private record ScreenPoint(double x, double y, double depth) {}
    private record Scene(BufferedImage image, Camera camera, int visibleFaces, long shadedPixels) {}
    private record UvIsland(int index, CreatureCube cube, List<String> bones) {}

    private record Camera(V3 right, V3 up, V3 near, V3 target, double scale, double centreX, double centreY, double yaw, double elevation) {
        static Camera forView(Options options, Bounds frame) {
            double yaw = switch (options.view) { case "back" -> 180; case "left" -> -90; case "right" -> 90; case "isometric" -> 35; case "isometric_back" -> 215; default -> 0; };
            double elevation = options.view.equals("top") ? 90 : options.view.startsWith("isometric") ? 22 : 0;
            double a = Math.toRadians(yaw), e = Math.toRadians(elevation), ca = Math.cos(a), sa = Math.sin(a), ce = Math.cos(e), se = Math.sin(e);
            V3 right = new V3(ca, 0, sa), near = new V3(sa*ce,se,-ca*ce), up = new V3(-sa*se,ce,ca*se);
            double top = options.labels ? 95 : 12, bottom = options.labels ? 86 : 12;
            double availableWidth = options.width - (options.labels ? 88 : 28), availableHeight = options.height - top - bottom;
            V3 half = frame.max.subtract(frame.min); half = new V3(half.x / 2, half.y / 2, half.z / 2);
            double spanX = 2 * (Math.abs(right.x) * half.x + Math.abs(right.y) * half.y + Math.abs(right.z) * half.z);
            double spanY = 2 * (Math.abs(up.x) * half.x + Math.abs(up.y) * half.y + Math.abs(up.z) * half.z);
            double scale = Math.min(availableWidth / Math.max(8, spanX), availableHeight / Math.max(8, spanY)) / 1.055;
            return new Camera(right,up,near,frame.centre(),scale,options.width/2.0,top+availableHeight/2,yaw,elevation);
        }
        ScreenPoint project(V3 p) { V3 relative = p.subtract(target); return new ScreenPoint(centreX+relative.dot(right)*scale,centreY-relative.dot(up)*scale,relative.dot(near)); }
    }
    private record Transform(double[] r, V3 translation) {
        static Transform bone(CreatureBone bone, CreaturePose.Rotation rotation) {
            double cx=Math.cos(rotation.x()),sx=Math.sin(rotation.x()),cy=Math.cos(rotation.y()),sy=Math.sin(rotation.y()),cz=Math.cos(rotation.z()),sz=Math.sin(rotation.z());
            var p=bone.getPivot();
            return new Transform(new double[]{cz*cy,cz*sy*sx-sz*cx,cz*sy*cx+sz*sx,sz*cy,sz*sy*sx+cz*cx,sz*sy*cx-cz*sx,-sy,cy*sx,cy*cx},new V3(p.getX(),p.getY(),p.getZ()));
        }
        V3 direction(V3 p) { return new V3(r[0]*p.x+r[1]*p.y+r[2]*p.z,r[3]*p.x+r[4]*p.y+r[5]*p.z,r[6]*p.x+r[7]*p.y+r[8]*p.z); }
        V3 point(V3 p) { return direction(p).add(translation); }
        Transform multiply(Transform child) { double[] product=new double[9]; for(int row=0;row<3;row++)for(int col=0;col<3;col++)for(int k=0;k<3;k++)product[row*3+col]+=r[row*3+k]*child.r[k*3+col]; return new Transform(product,point(child.translation)); }
    }

    private static int cubeCount(CreatureDefinition definition) { return definition.getModel().getBones().stream().mapToInt(b -> b.getCubes().size()).sum(); }
    private static double edge(double ax,double ay,double bx,double by,double px,double py) { return (bx-ax)*(py-ay)-(by-ay)*(px-ax); }
    private static int shade(int rgb,double brightness) { int r=(int)Math.round(((rgb>>16)&255)*brightness),g=(int)Math.round(((rgb>>8)&255)*brightness),b=(int)Math.round((rgb&255)*brightness); return 0xFF000000|(Math.min(255,r)<<16)|(Math.min(255,g)<<8)|Math.min(255,b); }
    private static Font font(int size,boolean bold) { return new Font(Font.SANS_SERIF,bold?Font.BOLD:Font.PLAIN,size); }
    private static void quality(Graphics2D g) { g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON); g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,RenderingHints.VALUE_TEXT_ANTIALIAS_ON); }
    private static void checker(Graphics2D g,int x,int y,int width,int height) { for(int cy=0;cy<height;cy+=16)for(int cx=0;cx<width;cx+=16){g.setColor(new Color(((cx/16+cy/16)&1)==0?0xECEFF3:0xD9DFE6));g.fillRect(x+cx,y+cy,Math.min(16,width-cx),Math.min(16,height-cy));} }
    private static byte[] encode(BufferedImage image) throws IOException { var output=new ByteArrayOutputStream(); if(!ImageIO.write(image,"png",output))throw new IOException("No PNG encoder available"); return output.toByteArray(); }
}

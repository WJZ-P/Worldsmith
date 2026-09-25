package com.wjz.worldsmith.worldgen;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.wjz.worldsmith.core.model.Anchor;
import com.wjz.worldsmith.core.model.AnchorClimateBias;
import com.wjz.worldsmith.core.model.AnchorPlacement;
import com.wjz.worldsmith.core.model.AnchorRelief;
import com.wjz.worldsmith.core.model.ReliefDistribution;
import com.wjz.worldsmith.core.model.TerrainShape;
import java.awt.Color;
import java.awt.Font;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import javax.imageio.ImageIO;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.RandomState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Reproducible two-dimensional noise-field diagrams, never world/chunk generation. */
class WorldsmithThemeHeightfieldTest {
	private static final int GRID = 129, SEA_LEVEL = 63;
	private static final long SEED = 0x574F524C44534D49L;
	private static final double COLOR_MIN = -40, COLOR_MAX = 240;
	private record Theme(String id, String title, int halfSpan, TerrainShape.Procedural shape, String geometryLabel) {}
	private record Field(Theme theme, double[][] heights, double minimum, double maximum, int belowSea) {}

	@BeforeAll static void bootstrapRegistries() { WorldsmithTestBootstrap.bootStrap(); }

	@Test
	void threeThemeHeightfieldsShareAnAbsoluteColorScaleWithoutPretendingToBeGameViews() throws Exception {
		assertEquals(SEA_LEVEL, WorldsmithPacks.builtin().getTerrain().getSeaLevel(), "Update the shared reference legend if the fixture sea level changes");
		List<Theme> themes = List.of(
			new Theme("gentle_mesa", "01 / Gentle observation mesa", 768,
				shape(.90, 1.8, .25, .55, new ReliefDistribution(.75, .25, 0, .18),
					new Anchor("observation_mesa", new AnchorPlacement.Fixed(0, 0), 420,
						new AnchorRelief.Mesa(112, .60, 2), 1,
						new AnchorClimateBias(1, null, null, .6, .6, null))),
				"Mesa Y112 / radius420 / top0.60 / texture2"),
			new Theme("storm_archipelago", "02 / Broken storm archipelago", 1024,
				shape(.24, .30, .85, 1.1, new ReliefDistribution(.25, .35, .40, .06)),
				"No anchors / rough coast0.85 / blend0.06"),
			new Theme("caldera_basin", "03 / Single caldera basin", 768,
				shape(.92, 1.8, .20, .65, new ReliefDistribution(.80, .20, 0, .16),
					new Anchor("caldera", new AnchorPlacement.Fixed(0, 0), 520,
						new AnchorRelief.Caldera(78, 164, .27, .66, 2), 1,
						new AnchorClimateBias(1, null, null, .65, -.16, null))),
				"Floor78 / rim164 / radius520 / radii0.27,0.66")
		);
		List<Field> fields = new ArrayList<>();
		JsonArray reports = new JsonArray();
		StringBuilder csv = new StringBuilder("theme,seed,x,z,uncarved_uninterpolated_surface_y,below_sea_reference\n");
		for (int i = 0; i < themes.size(); i++) {
			Theme theme = themes.get(i);
			RandomState state = WorldsmithTerrainSamplingTest.state(theme.shape,
				WorldsmithPacks.builtin().getBiomes().getSpatial(), (char) ('c' + i), SEED);
			double[][] heights = new double[GRID][GRID];
			double minimum = Double.POSITIVE_INFINITY, maximum = Double.NEGATIVE_INFINITY;
			int belowSea = 0;
			int step = theme.halfSpan * 2 / (GRID - 1);
			for (int iz = 0; iz < GRID; iz++) for (int ix = 0; ix < GRID; ix++) {
				int x = -theme.halfSpan + ix * step, z = -theme.halfSpan + iz * step;
				double height = surface(state, x, z);
				heights[iz][ix] = height;
				minimum = Math.min(minimum, height); maximum = Math.max(maximum, height);
				if (height < SEA_LEVEL) belowSea++;
				csv.append(String.format(Locale.ROOT, "%s,%d,%d,%d,%.9f,%s%n", theme.id, SEED, x, z, height, height < SEA_LEVEL));
			}
			Field field = new Field(theme, heights, minimum, maximum, belowSea); fields.add(field);
			JsonObject report = new JsonObject();
			report.addProperty("theme", theme.id); report.addProperty("seed", Long.toString(SEED));
			report.addProperty("halfSpanBlocks", theme.halfSpan); report.addProperty("stepBlocks", step);
			report.addProperty("gridSide", GRID); report.addProperty("samples", GRID * GRID);
			report.addProperty("minimumSurfaceY", minimum); report.addProperty("maximumSurfaceY", maximum);
			report.addProperty("belowSeaReferenceSamples", belowSea);
			report.addProperty("landRatio", theme.shape.getLandRatio()); report.addProperty("continentScale", theme.shape.getContinentScale());
			report.addProperty("coastRoughness", theme.shape.getCoastRoughness()); report.addProperty("verticalScale", theme.shape.getVerticalScale());
			report.addProperty("transitionWidth", theme.shape.getRelief().getTransitionWidth());
			JsonArray weights = new JsonArray(); weights.add(theme.shape.getRelief().getFlats());
			weights.add(theme.shape.getRelief().getHighlands()); weights.add(theme.shape.getRelief().getPeaks());
			report.add("familyWeightsFlatsHighlandsPeaks", weights);
			report.addProperty("anchorGeometry", theme.geometryLabel); reports.add(report);
		}
		assertEquals(49_923, themes.size() * GRID * GRID);
		// Authored level interiors are real field outcomes, while the islands
		// receive no aesthetic score or fixed water-share pass criterion.
		assertEquals(112.0, fields.get(0).heights[64][64], 2.1);
		assertEquals(78.0, fields.get(2).heights[64][64], 2.1);
		JsonObject report = new JsonObject();
		report.addProperty("sampledColumns", themes.size() * GRID * GRID);
		report.addProperty("seed", Long.toString(SEED)); report.addProperty("seaLevelReference", SEA_LEVEL);
		report.addProperty("commonColorMinimumY", COLOR_MIN); report.addProperty("commonColorMaximumY", COLOR_MAX);
		report.addProperty("colorValuesOutsideRangeClamped", true);
		report.addProperty("minecraftStarted", false); report.addProperty("chunksGenerated", false);
		report.addProperty("interpolatedChunks", false); report.addProperty("fluidOccupancyMeasured", false);
		report.addProperty("spawnOrBuildingPlacementVerified", false);
		report.addProperty("method", "Binary bracket of uncarved density-field zero plus the final adjacent vertical-sample interpolation. No horizontal noise-cell interpolation.");
		report.addProperty("limitations", "Three local examples at one seed, not broad theme-aesthetic validation. Blue means field height below the sea-level reference, not actual fluid. Storm is a design label, not simulated weather. No materials, caves, bands, structures or entities are rendered.");
		report.add("themes", reports);
		Path output = Path.of("build", "terrain-relief-audit").toAbsolutePath(); Files.createDirectories(output);
		Files.writeString(output.resolve("theme-heightfields.csv"), csv, StandardCharsets.UTF_8);
		Files.writeString(output.resolve("theme-heightfields.json"), new GsonBuilder().setPrettyPrinting().create().toJson(report), StandardCharsets.UTF_8);
		draw(output.resolve("theme-heightfields.png"), fields);
		System.out.println("theme heightfields: columns=" + themes.size() * GRID * GRID + ", files=" + output);
	}

	private static TerrainShape.Procedural shape(double land, double scale, double coast, double vertical,
		ReliefDistribution relief, Anchor... anchors) {
		var base = WorldsmithTerrainSamplingTest.shape(land, scale, coast, relief.getFlats(), relief.getHighlands(), relief.getPeaks(), vertical, 0);
		return new TerrainShape.Procedural(land, scale, coast, relief, vertical, base.getCaves(), base.getHydrology(), List.of(), List.of(anchors));
	}

	private static double surface(RandomState state, int x, int z) {
		int low = -64, high = 319;
		while (high - low > 1) {
			int middle = (low + high) >> 1;
			double density = state.router().depth().compute(new DensityFunction.SinglePointContext(x, middle, z));
			assertTrue(Double.isFinite(density));
			if (density > 0) low = middle; else high = middle;
		}
		double below = state.router().depth().compute(new DensityFunction.SinglePointContext(x, low, z));
		double above = state.router().depth().compute(new DensityFunction.SinglePointContext(x, high, z));
		assertTrue(Double.isFinite(below) && Double.isFinite(above) && below > 0 && above <= 0);
		double height = low + below / (below - above);
		assertTrue(Double.isFinite(height) && height >= -64 && height <= 319);
		return height;
	}

	private static void draw(Path path, List<Field> fields) throws Exception {
		BufferedImage image = new BufferedImage(1536, 940, BufferedImage.TYPE_INT_RGB);
		var g = image.createGraphics();
		try {
			g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			g.setColor(new Color(0x15232C)); g.fillRect(0, 0, 1536, 940);
			g.setColor(new Color(0xECF3F3)); g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 27));
			g.drawString("Worldsmith / three theme heightfields", 48, 46);
			g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 16)); g.setColor(new Color(0xAEC3CA));
			g.drawString("Seed 0x574F524C44534D49 / 129 x 129 samples per panel / north = -Z (top) / common absolute-Y colors", 48, 81);
			for (int panel = 0; panel < fields.size(); panel++) {
				Field field = fields.get(panel); Theme theme = field.theme; int left = 48 + panel * 504;
				g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 20)); g.setColor(new Color(0xE5EEEE));
				g.drawString(theme.title, left, 127);
				for (int z = 0; z < GRID; z++) for (int x = 0; x < GRID; x++) {
					g.setColor(color(field.heights[z][x]));
					int x0 = left + x * 432 / GRID, x1 = left + (x + 1) * 432 / GRID;
					int z0 = 160 + z * 432 / GRID, z1 = 160 + (z + 1) * 432 / GRID;
					g.fillRect(x0, z0, x1 - x0, z1 - z0);
				}
				g.setColor(new Color(0x6D858F)); g.drawRect(left - 1, 159, 433, 433);
				g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 15)); g.setColor(new Color(0xBDD0D4));
				g.drawString("x/z = +/-" + theme.halfSpan + " blocks / step " + theme.halfSpan * 2 / (GRID - 1), left, 621);
				g.drawString(String.format(Locale.ROOT, "land %.2f / continent %.2f / vertical %.2f",
					theme.shape.getLandRatio(), theme.shape.getContinentScale(), theme.shape.getVerticalScale()), left, 646);
				g.drawString(theme.geometryLabel, left, 671);
				g.drawString(String.format(Locale.ROOT, "sampled surface Y %.1f .. %.1f", field.minimum, field.maximum), left, 696);
				g.drawString(String.format(Locale.ROOT, "below sea reference: %.1f%% (not measured water)", field.belowSea * 100.0 / (GRID * GRID)), left, 721);
			}
			g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 15)); g.setColor(new Color(0xE3EDED));
			g.drawString("Shared absolute-Y color scale (outside range clamped)", 48, 762);
			for (int x = 0; x < 640; x++) { g.setColor(color(COLOR_MIN + (COLOR_MAX - COLOR_MIN) * x / 639)); g.fillRect(48 + x, 779, 1, 20); }
			g.setColor(new Color(0xBACBD0));
			for (int y : new int[] {-40, 0, 63, 100, 160, 240}) g.drawString(Integer.toString(y),
				48 + (int) ((y - COLOR_MIN) / (COLOR_MAX - COLOR_MIN) * 639) - 10, 824);
			g.drawString("Blue = height below Y63 reference, not actual fluid occupancy.", 756, 788);
			g.drawString("Pixel colors show altitude only, not biome materials or weather.", 756, 813);
			g.setColor(new Color(0xEDBF83));
			g.drawString("Uninterpolated native density fields, not generated chunks or Minecraft screenshots. No client/server was started.", 48, 873);
			g.drawString("Three local examples do not validate every theme, spawn, walkability or building placement. No caves, bands, structures or entities are shown.", 48, 902);
		} finally { g.dispose(); }
		assertTrue(ImageIO.write(image, "png", path.toFile()));
		var restored = ImageIO.read(path.toFile()); assertNotNull(restored);
		assertEquals(1536, restored.getWidth()); assertEquals(940, restored.getHeight());
	}

	private static Color color(double y) {
		double value = Math.clamp(y, COLOR_MIN, COLOR_MAX);
		if (value < SEA_LEVEL) return blend(0x16324D, 0x68ADC3, (value - COLOR_MIN) / (SEA_LEVEL - COLOR_MIN));
		if (value < 140) return blend(0x65886C, 0xB7AE84, (value - SEA_LEVEL) / (140 - SEA_LEVEL));
		return blend(0xB7AE84, 0xF0E7D6, (value - 140) / (COLOR_MAX - 140));
	}

	private static Color blend(int from, int to, double alpha) {
		Color a = new Color(from), b = new Color(to);
		return new Color((int) Math.round(a.getRed() + alpha * (b.getRed() - a.getRed())),
			(int) Math.round(a.getGreen() + alpha * (b.getGreen() - a.getGreen())),
			(int) Math.round(a.getBlue() + alpha * (b.getBlue() - a.getBlue())));
	}
}

package com.wjz.worldsmith.worldgen;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.RenderingHints;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import com.wjz.worldsmith.core.model.ReliefDistribution;
import com.wjz.worldsmith.core.model.TerrainShape;
import javax.imageio.ImageIO;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.RandomState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Seeded noise-field diagnostics, not game/chunk generation or a game screenshot. */
class WorldsmithReliefTransitionTest {
	private record Sample(int x, int z, double height, double erosion, double jump) {}

	@BeforeAll
	static void bootstrapRegistries() {
		WorldsmithTestBootstrap.bootStrap();
	}

	@Test
	void hardLandformBoundariesProduceMeasurableOneBlockHeightJumps() throws Exception {
		RandomState state = WorldsmithTerrainSamplingTest.state(
			shape(0.0), 'd');
		List<Sample> samples = scan(state);
		Sample steepest = samples.stream().max(java.util.Comparator.comparingDouble(Sample::jump)).orElseThrow();
		int crossings = 0;
		double crossingJumps = 0.0;
		for (int i = 1; i < samples.size(); i++) {
			Sample a = samples.get(i - 1), b = samples.get(i);
			if (a.z == b.z && (a.erosion > 0.05) != (b.erosion > 0.05)) {
				crossings++;
				crossingJumps += b.jump;
			}
		}
		assertTrue(crossings > 10, "audit needs real flat/highland boundaries");
		assertTrue(steepest.jump > 20.0, "explicit hard relief should preserve a sharp boundary option");
		assertTrue(samples.stream().noneMatch(s -> s.erosion < -0.375), "zero-share peak biome leaked through a noise tail");
		Path output = Path.of("build", "terrain-relief-audit").toAbsolutePath();
		Files.createDirectories(output);
		StringBuilder csv = new StringBuilder("x,z,uncarved_uninterpolated_surface_y,erosion,one_block_height_jump\n");
		for (Sample sample : samples) csv.append(String.format(Locale.ROOT, "%d,%d,%.9f,%.9f,%.9f%n",
			sample.x, sample.z, sample.height, sample.erosion, sample.jump));
		Files.writeString(output.resolve("sharp-boundaries.csv"), csv, StandardCharsets.UTF_8);
		Files.writeString(output.resolve("sharp-boundaries.metrics.json"), String.format(Locale.ROOT,
			"{\"samples\":%d,\"boundaryCrossings\":%d,\"meanBoundaryJump\":%.9f,\"maxOneBlockJump\":%.9f,\"at\":{\"x\":%d,\"z\":%d},\"minecraftStarted\":false,\"noiseFieldOnly\":true,\"interpolatedChunks\":false}%n",
			samples.size(), crossings, crossingJumps / crossings, steepest.jump, steepest.x, steepest.z), StandardCharsets.UTF_8);
		draw(output.resolve("sharp-boundaries.png"), samples, steepest, "SHARP / transitionWidth = 0");
		System.out.println("relief transition audit: max=" + steepest.jump + ", mean boundary=" + crossingJumps / crossings
			+ ", crossings=" + crossings + ", at=" + steepest.x + "," + steepest.z + ", files=" + output);
	}

	@Test
	void themeSelectedSoftBoundariesRemoveWallsWithoutIntroducingDisabledPeakBiomes() throws Exception {
		List<Sample> sharp = scan(WorldsmithTerrainSamplingTest.state(shape(0.0), 'e'));
		List<Sample> soft = scan(WorldsmithTerrainSamplingTest.state(shape(0.12), 'f'));
		Sample sharpest = sharp.stream().max(java.util.Comparator.comparingDouble(Sample::jump)).orElseThrow();
		Sample softest = soft.stream().max(java.util.Comparator.comparingDouble(Sample::jump)).orElseThrow();
		assertTrue(softest.jump < sharpest.jump * 0.25, "soft blend failed to reduce the boundary wall: " + softest.jump + " vs " + sharpest.jump);
		assertTrue(soft.stream().noneMatch(s -> s.erosion < -0.375), "disabled peak biome appeared in soft transition");
		Path output = Path.of("build", "terrain-relief-audit").toAbsolutePath();
		Files.createDirectories(output);
		StringBuilder csv = new StringBuilder("x,z,sharp_surface_y,soft_surface_y,sharp_jump,soft_jump,erosion\n");
		for (int i = 0; i < sharp.size(); i++) {
			Sample a = sharp.get(i), b = soft.get(i);
			assertTrue(Math.abs(a.erosion - b.erosion) < 1.0E-9, "height transition changed statistical biome identity");
			csv.append(String.format(Locale.ROOT, "%d,%d,%.9f,%.9f,%.9f,%.9f,%.9f%n", a.x, a.z, a.height, b.height, a.jump, b.jump, b.erosion));
		}
		Files.writeString(output.resolve("transition-comparison.csv"), csv, StandardCharsets.UTF_8);
		Files.writeString(output.resolve("transition-comparison.metrics.json"), String.format(Locale.ROOT,
			"{\"samples\":%d,\"sharpTransitionWidth\":0,\"softTransitionWidth\":0.12,\"sharpMaxOneBlockJump\":%.9f,\"softMaxOneBlockJump\":%.9f,\"disabledPeakSamples\":0,\"minecraftStarted\":false,\"interpolatedChunks\":false}%n",
			sharp.size(), sharpest.jump, softest.jump), StandardCharsets.UTF_8);
		draw(output.resolve("soft-boundaries.png"), soft, sharpest, "SMOOTH / transitionWidth = 0.12");
		drawComparison(output.resolve("transition-comparison.png"), sharp, soft, sharpest);
		System.out.println("relief transition comparison: sharp=" + sharpest.jump + ", soft=" + softest.jump + ", files=" + output);
	}

	private static TerrainShape.Procedural shape(double width) {
		var base = WorldsmithTerrainSamplingTest.shape(1.0, 1.0, 0.0, 0.5, 0.5, 0.0, 1.0, 0.0);
		return new TerrainShape.Procedural(base.getLandRatio(), base.getContinentScale(), base.getCoastRoughness(),
			new ReliefDistribution(.5, .5, 0, width), base.getVerticalScale(), base.getCaves(), base.getHydrology(), base.getBands(), base.getAnchors());
	}

	private static void drawComparison(Path file, List<Sample> sharp, List<Sample> smooth, Sample centre) throws Exception {
		List<Sample> sharpSection = sharp.stream().filter(s -> s.z == centre.z && Math.abs(s.x - centre.x) <= 96).toList();
		List<Sample> smoothSection = smooth.stream().filter(s -> s.z == centre.z && Math.abs(s.x - centre.x) <= 96).toList();
		assertTrue(sharpSection.size() == smoothSection.size());
		for (int i = 0; i < sharpSection.size(); i++) {
			assertTrue(sharpSection.get(i).x == smoothSection.get(i).x && sharpSection.get(i).z == smoothSection.get(i).z);
		}
		double low = Math.floor(Math.min(sharpSection.stream().mapToDouble(Sample::height).min().orElseThrow(),
			smoothSection.stream().mapToDouble(Sample::height).min().orElseThrow()) / 10.0) * 10.0 - 10;
		double high = Math.ceil(Math.max(sharpSection.stream().mapToDouble(Sample::height).max().orElseThrow(),
			smoothSection.stream().mapToDouble(Sample::height).max().orElseThrow()) / 10.0) * 10.0 + 10;
		int first = sharpSection.getFirst().x, last = sharpSection.getLast().x;
		BufferedImage image = new BufferedImage(1280, 720, BufferedImage.TYPE_INT_RGB);
		var g = image.createGraphics();
		try {
			g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			g.setColor(new Color(0x15232C)); g.fillRect(0, 0, 1280, 720);
			g.setColor(new Color(0xE2EBED)); g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 24));
			g.drawString("Same-scale relief transition comparison / 50% flat + 50% highland", 64, 49);
			g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 15));
			g.setColor(new Color(0xACBDC4));
			g.drawString("Identical seeded section at z=" + centre.z + " / shared X window and Y range / zero disabled peak samples", 64, 82);
			Color[] colors = {new Color(0xF2AA78), new Color(0x92D9D2)};
			String[] labels = {"SHARP / width 0", "SMOOTH / width 0.12"};
			for (int i = 0; i < 2; i++) {
				int x = 96 + i * 270;
				g.setColor(colors[i]); g.setStroke(new BasicStroke(3f)); g.drawLine(x, 110, x + 33, 110);
				g.drawString(labels[i], x + 44, 115);
			}
			for (double y = low; y <= high; y += 10) {
				int py = (int) (570 - (y - low) / (high - low) * 420);
				g.setColor(new Color(0x34444E)); g.setStroke(new BasicStroke(1f)); g.drawLine(96, py, 1210, py);
				g.setColor(new Color(0xACBDC4)); g.drawString(String.format(Locale.ROOT, "%.0f", y), 49, py + 5);
			}
			for (int series = 0; series < 2; series++) {
				List<Sample> section = series == 0 ? sharpSection : smoothSection;
				Path2D.Double line = new Path2D.Double();
				for (int i = 0; i < section.size(); i++) {
					Sample sample = section.get(i);
					double x = 96.0 + (sample.x - first) / (double) (last - first) * 1114;
					double y = 570.0 - (sample.height - low) / (high - low) * 420;
					if (i == 0) line.moveTo(x, y); else line.lineTo(x, y);
				}
				g.setColor(colors[series]); g.setStroke(new BasicStroke(series == 0 ? 2.3f : 2.8f)); g.draw(line);
			}
			g.setColor(new Color(0xACBDC4)); g.drawString("x=" + first, 96, 601); g.drawString("x=" + last, 1122, 601);
			g.drawString(String.format(Locale.ROOT, "Whole-audit maximum adjacent-block jump: sharp %.2f / smooth %.2f blocks",
				sharp.stream().mapToDouble(Sample::jump).max().orElseThrow(), smooth.stream().mapToDouble(Sample::jump).max().orElseThrow()), 96, 629);
			g.setColor(new Color(0xEDBF83));
			g.drawString("Uninterpolated density-field evidence, not generated chunks, a game screenshot or proof of walkability.", 64, 667);
			g.drawString("No client/server started. Both lines use the same axes; horizontal and vertical block scales differ.", 64, 692);
		} finally { g.dispose(); }
		assertTrue(ImageIO.write(image, "png", file.toFile()));
		var readback = ImageIO.read(file.toFile());
		assertTrue(readback != null && readback.getWidth() == 1280 && readback.getHeight() == 720);
	}

	private static List<Sample> scan(RandomState state) {
		List<Sample> samples = new ArrayList<>();
		for (int z : new int[] {-768, -384, 0, 384, 768}) {
			double previous = Double.NaN;
			for (int x = -2048; x <= 2048; x++) {
				// Depth includes the native floor/ceiling slides before its clamp.
				// Find its zero rather than assuming a particular fixed-Y gain.
				int low = -64, high = 319;
				while (high - low > 1) {
					int middle = (low + high) >> 1;
					if (state.router().depth().compute(new DensityFunction.SinglePointContext(x, middle, z)) > 0.0) low = middle;
					else high = middle;
				}
				double below = state.router().depth().compute(new DensityFunction.SinglePointContext(x, low, z));
				double above = state.router().depth().compute(new DensityFunction.SinglePointContext(x, high, z));
				assertTrue(below > 0.0 && above <= 0.0, "a sealed single-surface fixture must bracket its zero");
				double height = low + below / (below - above);
				var at = new DensityFunction.SinglePointContext(x, low, z);
				double jump = Double.isNaN(previous) ? 0.0 : Math.abs(height - previous);
				samples.add(new Sample(x, z, height, state.router().erosion().compute(at), jump));
				previous = height;
			}
		}
		return samples;
	}

	private static void draw(Path file, List<Sample> samples, Sample steepest, String mode) throws Exception {
		List<Sample> section = samples.stream().filter(s -> s.z == steepest.z && Math.abs(s.x - steepest.x) <= 96).toList();
		BufferedImage image = new BufferedImage(1280, 720, BufferedImage.TYPE_INT_RGB);
		var g = image.createGraphics();
		try {
			g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			g.setColor(new Color(0x15232C)); g.fillRect(0, 0, 1280, 720);
			g.setColor(new Color(0xE2EBED)); g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 24));
			g.drawString("50% flat + 50% highland / " + mode, 64, 52);
			g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 16));
			g.drawString(String.format(Locale.ROOT, "Seeded density-field section z=%d / dataset maximum adjacent-block jump %.2f blocks", steepest.z,
				samples.stream().mapToDouble(Sample::jump).max().orElseThrow()), 64, 87);
			double low = Math.floor(section.stream().mapToDouble(Sample::height).min().orElseThrow() / 10.0) * 10.0 - 10;
			double high = Math.ceil(section.stream().mapToDouble(Sample::height).max().orElseThrow() / 10.0) * 10.0 + 10;
			int first = section.getFirst().x, last = section.getLast().x;
			for (double y = low; y <= high; y += 10) {
				int py = (int) (570 - (y - low) / (high - low) * 440);
				g.setColor(new Color(0x34444E)); g.drawLine(96, py, 1210, py);
				g.setColor(new Color(0xACBDC4)); g.drawString(String.format(Locale.ROOT, "%.0f", y), 49, py + 5);
			}
			Path2D.Double line = new Path2D.Double();
			for (int i = 0; i < section.size(); i++) {
				Sample s = section.get(i);
				double x = 96.0 + (s.x - first) / (double) (last - first) * 1114;
				double y = 570.0 - (s.height - low) / (high - low) * 440;
				if (i == 0) line.moveTo(x, y); else line.lineTo(x, y);
			}
			g.setStroke(new BasicStroke(2.5f)); g.setColor(new Color(0xD5E8B3)); g.draw(line);
			g.setColor(new Color(0xACBDC4)); g.drawString("x=" + first, 96, 600); g.drawString("x=" + last, 1122, 600);
			g.setColor(new Color(0xEDBF83));
			g.drawString("Raw 1-block density-field samples, before native noise-cell interpolation; not a game screenshot.", 64, 651);
			g.drawString("No client/server started. No caves, rivers, anchors or terrain bands. Horizontal and vertical scales differ.", 64, 679);
		} finally { g.dispose(); }
		assertTrue(ImageIO.write(image, "png", file.toFile()));
	}
}

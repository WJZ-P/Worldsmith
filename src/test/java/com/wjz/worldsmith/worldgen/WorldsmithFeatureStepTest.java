package com.wjz.worldsmith.worldgen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.wjz.worldsmith.core.model.VegetationRecipe;
import java.util.EnumSet;
import java.util.Set;
import net.minecraft.world.level.levelgen.GenerationStep;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The decoration step is ordering rather than position, and getting it wrong is
 * invisible until a world is generated: ore cut after the surface settles finds
 * nothing to cut, and a layer that settles first is buried by what follows.
 */
class WorldsmithFeatureStepTest {
	@BeforeAll
	static void bootstrap() {
		WorldsmithTestBootstrap.bootStrap();
	}

	@Test
	void everyRecipeNamesAStep() {
		for (VegetationRecipe recipe : VegetationRecipe.values()) {
			assertNotNull(WorldsmithVegetation.step(recipe), recipe + " has no decoration step");
		}
	}

	@Test
	void undergroundRecipesRunBeforeAnythingStandsOnTheGround() {
		Set<GenerationStep.Decoration> underground = EnumSet.of(
			WorldsmithVegetation.step(VegetationRecipe.ORE_VEIN),
			WorldsmithVegetation.step(VegetationRecipe.CAVE_PATCH)
		);

		for (GenerationStep.Decoration step : underground) {
			assertEquals(
				true,
				step.ordinal() < GenerationStep.Decoration.VEGETAL_DECORATION.ordinal(),
				step + " has to run before vegetation, which stands on what it leaves"
			);
		}
	}

	@Test
	void aSurfaceLayerSettlesLast() {
		assertEquals(
			GenerationStep.Decoration.TOP_LAYER_MODIFICATION,
			WorldsmithVegetation.step(VegetationRecipe.SURFACE_LAYER)
		);
		assertEquals(
			true,
			GenerationStep.Decoration.TOP_LAYER_MODIFICATION.ordinal()
				> WorldsmithVegetation.step(VegetationRecipe.DEAD_TREE).ordinal(),
			"a layer that settles before the trunks would be buried by them"
		);
	}

	@Test
	void aBoulderIsAChangeToTheLandRatherThanSomethingGrowingOnIt() {
		assertEquals(
			GenerationStep.Decoration.LOCAL_MODIFICATIONS,
			WorldsmithVegetation.step(VegetationRecipe.BOULDER)
		);
	}
}

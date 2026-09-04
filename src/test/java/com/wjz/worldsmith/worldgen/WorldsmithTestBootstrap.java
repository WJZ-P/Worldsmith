package com.wjz.worldsmith.worldgen;

import com.wjz.worldsmith.Worldsmith;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import net.minecraft.SharedConstants;
import net.minecraft.core.Holder;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.Bootstrap;

/** Reproduces Fabric's registry-registration window for plain JVM tests. */
final class WorldsmithTestBootstrap {
	private static boolean bootstrapped;

	private WorldsmithTestBootstrap() {
	}

	@SuppressWarnings({"rawtypes", "unchecked"})
	static synchronized void bootStrap() {
		if (bootstrapped) {
			return;
		}
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
		MappedRegistry<?> materialConditions = (MappedRegistry<?>)BuiltInRegistries.MATERIAL_CONDITION;
		MappedRegistry<?> densityFunctions = (MappedRegistry<?>)BuiltInRegistries.DENSITY_FUNCTION_TYPE;
		MappedRegistry<?> trunkPlacers = (MappedRegistry<?>)BuiltInRegistries.TRUNK_PLACER_TYPE;
		MappedRegistry<?> foliagePlacers = (MappedRegistry<?>)BuiltInRegistries.FOLIAGE_PLACER_TYPE;
		MappedRegistry<?> placementModifiers = (MappedRegistry<?>)BuiltInRegistries.PLACEMENT_MODIFIER_TYPE;
		try {
			Field frozen = MappedRegistry.class.getDeclaredField("frozen");
			frozen.setAccessible(true);
			frozen.setBoolean(materialConditions, false);
			frozen.setBoolean(densityFunctions, false);
			frozen.setBoolean(trunkPlacers, false);
			frozen.setBoolean(foliagePlacers, false);
			frozen.setBoolean(placementModifiers, false);
			WorldsmithWorldgen.initialize();
			Method bindValue = Holder.Reference.class.getDeclaredMethod("bindValue", Object.class);
			bindValue.setAccessible(true);
			bind(materialConditions, Registries.MATERIAL_CONDITION, "hydrology",
				WorldsmithHydrologyConditionSource.CODEC, bindValue);
			bind(materialConditions, Registries.MATERIAL_CONDITION, "anchor",
				WorldsmithAnchorConditionSource.CODEC, bindValue);
			bind(densityFunctions, Registries.DENSITY_FUNCTION_TYPE, "anchor_point",
				WorldsmithAnchorFields.Point.CODEC.codec(), bindValue);
			bind(densityFunctions, Registries.DENSITY_FUNCTION_TYPE, "anchor_grid",
				WorldsmithAnchorFields.Grid.CODEC.codec(), bindValue);
			bind(densityFunctions, Registries.DENSITY_FUNCTION_TYPE, "anchor_line",
				WorldsmithAnchorFields.Line.CODEC.codec(), bindValue);
			bind(trunkPlacers, Registries.TRUNK_PLACER_TYPE, "shaped_trunk",
				WorldsmithTreePlacerTypes.trunk(), bindValue);
			bind(foliagePlacers, Registries.FOLIAGE_PLACER_TYPE, "shaped_foliage",
				WorldsmithTreePlacerTypes.foliage(), bindValue);
			bind(placementModifiers, Registries.PLACEMENT_MODIFIER_TYPE, "height_range_filter",
				WorldsmithPlacementModifierTypes.heightRangeFilter(), bindValue);
			// Fabric performs the ordinary final freeze after mod registration.
			// The plain test registry was already frozen once, so restore the flag
			// directly instead of rebuilding its already-bound tag set.
			frozen.setBoolean(materialConditions, true);
			frozen.setBoolean(densityFunctions, true);
			frozen.setBoolean(trunkPlacers, true);
			frozen.setBoolean(foliagePlacers, true);
			frozen.setBoolean(placementModifiers, true);
		} catch (ReflectiveOperationException failure) {
			throw new IllegalStateException("Could not open the test registries", failure);
		}
		bootstrapped = true;
	}

	/**
	 * A registry frozen once will not re-bind its holders, so the value has to be
	 * pushed into the existing reference directly.
	 */
	@SuppressWarnings({"rawtypes", "unchecked"})
	private static void bind(
		MappedRegistry<?> registry,
		ResourceKey<? extends net.minecraft.core.Registry<?>> registryKey,
		String path,
		Object value,
		Method bindValue
	) throws ReflectiveOperationException {
		ResourceKey<?> key = ResourceKey.create((ResourceKey) registryKey, Worldsmith.id(path));
		Holder.Reference<?> holder = (Holder.Reference<?>) registry.get((ResourceKey) key).orElseThrow();
		bindValue.invoke(holder, value);
	}
}

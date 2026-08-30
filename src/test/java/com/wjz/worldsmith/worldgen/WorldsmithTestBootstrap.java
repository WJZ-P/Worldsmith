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
		try {
			Field frozen = MappedRegistry.class.getDeclaredField("frozen");
			frozen.setAccessible(true);
			frozen.setBoolean(materialConditions, false);
			WorldsmithWorldgen.initialize();
			ResourceKey<?> key = ResourceKey.create(Registries.MATERIAL_CONDITION, Worldsmith.id("hydrology"));
			Holder.Reference<?> holder = (Holder.Reference<?>)materialConditions.get((ResourceKey)key).orElseThrow();
			Method bindValue = Holder.Reference.class.getDeclaredMethod("bindValue", Object.class);
			bindValue.setAccessible(true);
			bindValue.invoke(holder, WorldsmithHydrologyConditionSource.CODEC);
			// Fabric performs the ordinary final freeze after mod registration.
			// The plain test registry was already frozen once, so restore the flag
			// directly instead of rebuilding its already-bound tag set.
			frozen.setBoolean(materialConditions, true);
		} catch (ReflectiveOperationException failure) {
			throw new IllegalStateException("Could not open the test material-condition registry", failure);
		}
		bootstrapped = true;
	}
}

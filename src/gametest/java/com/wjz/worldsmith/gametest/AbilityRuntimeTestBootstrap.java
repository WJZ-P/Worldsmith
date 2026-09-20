package com.wjz.worldsmith.gametest;

import com.wjz.worldsmith.ability.WorldAbilityRuntime;
import com.wjz.worldsmith.core.ability.AbilityCapabilitySpec;
import com.wjz.worldsmith.core.ability.AbilityType;
import com.wjz.worldsmith.core.ability.AbilityValues;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import net.fabricmc.api.ModInitializer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;

/** Installed by the opt-in test mod before any world binding, never included in production. */
public final class AbilityRuntimeTestBootstrap implements ModInitializer {
    static final AtomicInteger CALLS = new AtomicInteger();
    @Override public void onInitialize() {
        WorldAbilityRuntime.registerCapability(new AbilityCapabilitySpec("test.floor_name", 1, List.of(AbilityType.VECTOR),
            AbilityType.TEXT, false, "Read one loaded block through an installed native test extension"), (context, arguments) -> {
            var point = context.point(arguments.getFirst());
            CALLS.incrementAndGet();
            return AbilityValues.text(BuiltInRegistries.BLOCK.getKey(context.level().getBlockState(BlockPos.containing(point)).getBlock()).toString());
        });
    }
}

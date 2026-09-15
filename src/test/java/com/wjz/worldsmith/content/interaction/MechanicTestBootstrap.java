package com.wjz.worldsmith.content.interaction;

/** Plain JVM tests need the same 26.2 component-initialization phase as server resource loading. */
final class MechanicTestBootstrap {
    private MechanicTestBootstrap() {}
    static synchronized void initialize() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
        if (!net.minecraft.world.item.Items.STICK.builtInRegistryHolder().areComponentsBound()) {
            net.minecraft.core.registries.BuiltInRegistries.DATA_COMPONENT_INITIALIZERS
                .build(net.minecraft.data.registries.VanillaRegistries.createLookup())
                .forEach(net.minecraft.core.component.DataComponentInitializers.PendingComponents::apply);
        }
    }
}

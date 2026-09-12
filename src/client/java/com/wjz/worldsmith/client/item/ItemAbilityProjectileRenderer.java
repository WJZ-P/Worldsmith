package com.wjz.worldsmith.client.item;

import com.wjz.worldsmith.content.item.ItemAbilityProjectile;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.minecraft.client.renderer.entity.ThrownItemRenderer;

public final class ItemAbilityProjectileRenderer {
    private ItemAbilityProjectileRenderer() {}
    public static void register() { EntityRendererRegistry.register(ItemAbilityProjectile.type(), context -> new ThrownItemRenderer<>(context, 0.65F, false)); }
}

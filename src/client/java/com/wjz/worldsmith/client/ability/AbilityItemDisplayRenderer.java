package com.wjz.worldsmith.client.ability;

import com.wjz.worldsmith.ability.OwnedItemDisplay;
import com.wjz.worldsmith.client.content.WorldContentClientRuntime;
import net.minecraft.client.renderer.entity.DisplayRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.state.ItemDisplayEntityRenderState;
import net.minecraft.world.entity.Display;

/** Reuses vanilla's actual item model resolution, lighting and interpolated display transforms. */
public final class AbilityItemDisplayRenderer extends DisplayRenderer.ItemDisplayRenderer {
    public AbilityItemDisplayRenderer(EntityRendererProvider.Context context) { super(context); }
    @Override public void extractRenderState(Display.ItemDisplay entity, ItemDisplayEntityRenderState state, float partialTicks) {
        super.extractRenderState(entity, state, partialTicks);
        if (entity instanceof OwnedItemDisplay owned && !owned.scope().equals(WorldContentClientRuntime.activeScope())) state.renderState = null;
    }
}

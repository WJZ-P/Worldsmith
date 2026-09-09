package com.wjz.worldsmith.client.content.creature;

import com.wjz.worldsmith.core.content.CreatureDefinition;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;

public final class CreatureRenderState extends LivingEntityRenderState {
    public CreatureDefinition definition;
    public String bundleHash = "";
    public long appearanceSeed;
    public int action;
}

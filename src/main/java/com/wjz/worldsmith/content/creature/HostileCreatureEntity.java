package com.wjz.worldsmith.content.creature;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.level.Level;

/** Separate Enemy marker and MONSTER host retain vanilla hostile spawn-cap/peaceful behavior. */
public final class HostileCreatureEntity extends CreatureEntity implements Enemy {
    public HostileCreatureEntity(EntityType<? extends CreatureEntity> type, Level level) { super(type, level); }
}

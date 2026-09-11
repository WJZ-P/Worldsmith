package com.wjz.worldsmith.content.creature;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import org.slf4j.LoggerFactory;

/**
 * A separate exact class prevents ordinary hostile creatures from consuming vanilla spawner proximity slots.
 * Natural and creative Bosses keep their original host and save identity; this class is only for landmarks.
 */
public final class EncounterBossCreatureEntity extends CreatureEntity implements Enemy {
    public static final String INITIALIZE_TAG = "WorldsmithEncounterInitialize";
    private boolean pendingEncounterInitialization;

    public EncounterBossCreatureEntity(EntityType<? extends CreatureEntity> type, Level level) { super(type, level); }

    @Override protected void readAdditionalSaveData(ValueInput input) {
        super.readAdditionalSaveData(input);
        // This tag only exists in generated SpawnData, never in an ordinary entity save.
        pendingEncounterInitialization = input.getBooleanOr(INITIALIZE_TAG, false);
    }

    @Override public void snapTo(double x, double y, double z, float yaw, float pitch) {
        super.snapTo(x, y, z, yaw, pitch);
        if (!pendingEncounterInitialization || !(level() instanceof ServerLevel server)) return;
        pendingEncounterInitialization = false;
        var definition = definition();
        if (definition == null || definition.getBoss() == null || !CreatureRuntime.matchesHost(getType(), definition)) {
            LoggerFactory.getLogger("worldsmith.creatures").error("Landmark Boss spawn rejected: missing/mismatched immutable definition {}:{}", bundleHash(), creatureId());
            discard();
            return;
        }
        // BaseSpawner loads configured NBT without finalizeSpawn, then supplies the actual final position
        // through this callback. Initialize once here, before native collision checks or entity publication.
        initialize(bundleHash(), creatureId(), getRandom().nextLong());
        finalizeSpawn(server, server.getCurrentDifficultyAt(blockPosition()), EntitySpawnReason.SPAWNER, null);
        if (server.isOutsideBuildHeight(blockPosition()) || getBoundingBox().maxY > server.getMaxY() + 1
            || !server.getWorldBorder().isWithinBounds(getBoundingBox())) discard();
        // Deliberately use vanilla's local exact-class cap/delay. There is no cross-landmark uniqueness ledger.
    }
}

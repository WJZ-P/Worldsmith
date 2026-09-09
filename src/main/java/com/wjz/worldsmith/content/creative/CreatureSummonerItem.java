package com.wjz.worldsmith.content.creative;

import com.wjz.worldsmith.content.creature.CreatureEntity;
import com.wjz.worldsmith.content.creature.CreatureRuntime;
import com.wjz.worldsmith.core.content.CreatureCategory;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.gameevent.GameEvent;

/** Creative-only placement tool. It deliberately does not inherit vanilla egg/spawner or ENTITY_DATA behavior. */
public final class CreatureSummonerItem extends Item {
    CreatureSummonerItem(Properties properties) { super(properties); }

    @Override public Component getName(ItemStack stack) {
        CreatureSpawnToken token = stack.get(WorldsmithCreativeContent.CREATURE_SPAWN);
        var snapshot = CreatureRuntime.clientSnapshot();
        if (token != null && snapshot != null && snapshot.bundleHash().equals(token.bundleHash())) {
            var definition = snapshot.definitions().get(token.species());
            if (definition != null) return Component.translatable("item.worldsmith.creature_summoner.named", definition.getDisplayName());
        }
        return Component.translatable(token == null ? "item.worldsmith.creature_summoner" : "item.worldsmith.creature_summoner.unavailable");
    }

    @Override public InteractionResult useOn(UseOnContext context) {
        Player player = context.getPlayer();
        if (player == null || !player.isCreative()) return deny(player, "worldsmith.creative.creative_only");
        ItemStack stack = context.getItemInHand();
        CreatureSpawnToken token = stack.get(WorldsmithCreativeContent.CREATURE_SPAWN);
        if (token == null) return deny(player, "worldsmith.creative.missing_species");

        // The client predicts only the interaction, never selects or creates an entity.
        if (!(context.getLevel() instanceof ServerLevel level)) return InteractionResult.SUCCESS;
        var snapshot = CreatureRuntime.snapshot(level);
        if (snapshot == null || !snapshot.bundleHash().equals(token.bundleHash()))
            return deny(player, "worldsmith.creative.wrong_world");
        var definition = snapshot.definitions().get(token.species());
        if (definition == null) return deny(player, "worldsmith.creative.missing_species");

        BlockPos clicked = context.getClickedPos();
        if (!level.hasChunkAt(clicked)) return deny(player, "worldsmith.creative.blocked_spawn");
        BlockPos target = level.getBlockState(clicked).getCollisionShape(level, clicked).isEmpty()
            ? clicked : clicked.relative(context.getClickedFace());
        if (!level.hasChunkAt(target) || level.isOutsideBuildHeight(target) || !level.mayInteract(player, target)
            || !player.mayUseItemAt(target, context.getClickedFace(), stack))
            return deny(player, "worldsmith.creative.blocked_spawn");

        EntityType<? extends CreatureEntity> type = definition.getCategory() == CreatureCategory.HOSTILE
            ? CreatureRuntime.hostileType() : CreatureRuntime.passiveType();
        CreatureEntity entity = type.create(level, EntitySpawnReason.SPAWN_ITEM_USE);
        if (entity == null) return deny(player, "worldsmith.creative.spawn_unavailable");
        entity.snapTo(target.getX() + .5, target.getY(), target.getZ() + .5, level.getRandom().nextFloat() * 360, 0);
        entity.initialize(snapshot.bundleHash(), definition.getId(), level.getRandom().nextLong());
        entity.finalizeSpawn(level, level.getCurrentDifficultyAt(target), EntitySpawnReason.SPAWN_ITEM_USE, null);
        entity.setPersistenceRequired();
        // Recheck after initialization: the definition's dimensions can be larger than the generic host.
        if (entity.isRemoved() || entity.getBoundingBox().maxY > level.getMaxY() + 1
            || !level.getWorldBorder().isWithinBounds(entity.getBoundingBox())
            || !entity.checkSpawnObstruction(level) || !level.noCollision(entity) || !level.addFreshEntity(entity)) {
            entity.discard();
            return deny(player, "worldsmith.creative.blocked_spawn");
        }
        level.gameEvent(player, GameEvent.ENTITY_PLACE, target);
        return InteractionResult.SUCCESS;
    }

    private static InteractionResult deny(Player player, String key) {
        if (player != null && !player.level().isClientSide()) player.sendOverlayMessage(Component.translatable(key));
        return InteractionResult.FAIL;
    }
}

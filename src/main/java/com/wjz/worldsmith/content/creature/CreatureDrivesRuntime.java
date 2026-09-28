package com.wjz.worldsmith.content.creature;

import com.wjz.worldsmith.content.WorldBlockBindings;
import com.wjz.worldsmith.content.item.CustomItemRuntime;
import com.wjz.worldsmith.core.content.CreatureActivity;
import com.wjz.worldsmith.core.content.CreatureDrives;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gamerules.GameRules;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Turns a creature's authored drives into native behavior.
 *
 * <p>Every reference is resolved once, when the creature is configured, into a
 * plain predicate. A reference that names nothing - an invented entity id, a
 * block that is not registered - is logged and dropped, the same way an
 * unresolved material falls back: one bad name removes one relationship, not
 * the creature.
 */
final class CreatureDrivesRuntime {
	private static final Logger LOGGER = LoggerFactory.getLogger("worldsmith.creatures");
	private CreatureDrivesRuntime() {
	}

	/** Which creatures a drive list names: local creature ids or native entity types. */
	static Predicate<LivingEntity> creatures(List<String> references, String owner) {
		List<String> local = new ArrayList<>();
		List<EntityType<?>> natives = new ArrayList<>();
		for (String reference : references) {
			if (reference.indexOf(':') < 0) {
				local.add(reference);
				continue;
			}
			Identifier id = Identifier.tryParse(reference);
			Optional<EntityType<?>> type = id == null ? Optional.empty() : BuiltInRegistries.ENTITY_TYPE.getOptional(id);
			if (type.isPresent()) natives.add(type.get());
			else LOGGER.warn("Creature {} names unknown entity {} in its drives; that relationship is skipped", owner, reference);
		}
		if (local.isEmpty() && natives.isEmpty()) return entity -> false;
		return entity -> entity instanceof CreatureEntity creature
			? local.contains(creature.creatureId())
			: natives.contains(entity.getType());
	}

	/** Which blocks a creature eats; logical content blocks resolve through the world's own bindings. */
	static Predicate<BlockState> blocks(List<String> references, String owner) {
		List<Block> blocks = new ArrayList<>();
		for (String reference : references) {
			try {
				if (reference.startsWith("worldsmith:content/")) {
					var active = WorldBlockBindings.active();
					if (active == null) throw new IllegalStateException("no bound world blocks");
					blocks.add(WorldBlockBindings.resolver(active).resolve(reference, Map.of()).getBlock());
				} else {
					Identifier id = Identifier.parse(reference);
					Block block = BuiltInRegistries.BLOCK.getOptional(id).orElseThrow();
					if (block == Blocks.AIR) throw new IllegalArgumentException("air");
					blocks.add(block);
				}
			} catch (RuntimeException failure) {
				LOGGER.warn("Creature {} eats unknown block {}; that food is skipped", owner, reference);
			}
		}
		return state -> blocks.contains(state.getBlock());
	}

	/** Which held stacks draw a creature; logical items compare by their world definition. */
	static Predicate<ItemStack> items(List<String> references, ServerLevel level, String owner) {
		List<String> logical = new ArrayList<>();
		List<net.minecraft.world.item.Item> natives = new ArrayList<>();
		for (String reference : references) {
			if (reference.startsWith(CustomItemRuntime.LOGICAL_PREFIX)) {
				logical.add(reference.substring(CustomItemRuntime.LOGICAL_PREFIX.length()));
				continue;
			}
			Identifier id = Identifier.tryParse(reference);
			var item = id == null ? null : BuiltInRegistries.ITEM.getOptional(id).orElse(null);
			if (item != null) natives.add(item);
			else LOGGER.warn("Creature {} is tempted by unknown item {}; that lure is skipped", owner, reference);
		}
		return stack -> {
			if (stack.isEmpty()) return false;
			if (natives.contains(stack.getItem())) return true;
			if (logical.isEmpty()) return false;
			var definition = CustomItemRuntime.definition(level, stack);
			return definition != null && logical.contains(definition.getId());
		};
	}

	/** True while the creature is inside the hours it keeps. */
	static boolean active(PathfinderMob mob, CreatureDrives drives) {
		return switch (drives.getActivity()) {
			case ALWAYS -> true;
			case DAY -> mob.level().isBrightOutside();
			case NIGHT -> mob.level().isDarkOutside();
		};
	}

	/**
	 * Vanilla's own sun test, which it keeps private and gates on an entity-type
	 * tag every Worldsmith creature shares. It honours the monsters-burn
	 * environment attribute, so a biome that switches burning off shelters these
	 * creatures exactly as it shelters zombies.
	 */
	static boolean sunBurns(PathfinderMob mob) {
		if (mob.level().isClientSide() || !mob.isAlive()) return false;
		if (!mob.level().environmentAttributes().getValue(net.minecraft.world.attribute.EnvironmentAttributes.MONSTERS_BURN, mob.position())) return false;
		float brightness = mob.getLightLevelDependentMagicValue();
		BlockPos eyes = BlockPos.containing(mob.getX(), mob.getEyeY(), mob.getZ());
		return brightness > 0.5F && mob.getRandom().nextFloat() * 30.0F < (brightness - 0.4F) * 2.0F
			&& !mob.isInWaterOrRain() && mob.level().canSeeSky(eyes);
	}

	/**
	 * Walks to a nearby block it eats and eats it.
	 *
	 * <p>The eaten block becomes air, which is what makes grazing visible: a
	 * herd that stays in a meadow thins it. Eating respects the mob-griefing
	 * rule, as a sheep's does.
	 */
	static final class GrazeGoal extends Goal {
		private static final int SEARCH = 8;
		private final PathfinderMob mob;
		private final Predicate<BlockState> food;
		private BlockPos target;
		private int cooldown;
		private int timeout;

		GrazeGoal(PathfinderMob mob, Predicate<BlockState> food) {
			this.mob = mob;
			this.food = food;
			setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
		}

		@Override public boolean canUse() {
			if (cooldown-- > 0 || mob.getTarget() != null) return false;
			cooldown = 100 + mob.getRandom().nextInt(200);
			target = find();
			return target != null;
		}

		@Override public boolean canContinueToUse() { return target != null && timeout > 0 && food.test(mob.level().getBlockState(target)); }

		@Override public void start() {
			timeout = 200;
			mob.getNavigation().moveTo(target.getX() + .5, target.getY(), target.getZ() + .5, 1.0);
		}

		@Override public void tick() {
			timeout--;
			mob.getLookControl().setLookAt(target.getX() + .5, target.getY() + .5, target.getZ() + .5);
			if (mob.blockPosition().distSqr(target) > 4.0) {
				if (mob.getNavigation().isDone()) mob.getNavigation().moveTo(target.getX() + .5, target.getY(), target.getZ() + .5, 1.0);
				return;
			}
			if (mob.level() instanceof ServerLevel level && level.getGameRules().get(GameRules.MOB_GRIEFING)) {
				level.destroyBlock(target, false);
				mob.heal(2.0F);
			}
			target = null;
		}

		@Override public void stop() { target = null; mob.getNavigation().stop(); }

		private BlockPos find() {
			BlockPos origin = mob.blockPosition();
			for (int attempt = 0; attempt < 24; attempt++) {
				BlockPos candidate = origin.offset(
					mob.getRandom().nextInt(SEARCH * 2 + 1) - SEARCH,
					mob.getRandom().nextInt(5) - 2,
					mob.getRandom().nextInt(SEARCH * 2 + 1) - SEARCH);
				if (mob.isWithinHome(candidate) && food.test(mob.level().getBlockState(candidate))) return candidate.immutable();
			}
			return null;
		}
	}

	/** Drifts back toward others of its kind when it strays from them. */
	static final class HerdGoal extends Goal {
		private static final double RANGE = 16.0;
		private final PathfinderMob mob;
		private final String kind;
		private int nextCheck;

		HerdGoal(PathfinderMob mob, String kind) {
			this.mob = mob;
			this.kind = kind;
			setFlags(EnumSet.of(Flag.MOVE));
		}

		@Override public boolean canUse() {
			if (nextCheck-- > 0 || mob.getTarget() != null) return false;
			nextCheck = 40 + mob.getRandom().nextInt(40);
			var centre = centre();
			return centre != null && mob.position().distanceToSqr(centre) > 25.0;
		}

		@Override public void start() {
			var centre = centre();
			if (centre != null) mob.getNavigation().moveTo(centre.x, centre.y, centre.z, 1.0);
		}

		@Override public boolean canContinueToUse() { return !mob.getNavigation().isDone(); }

		private net.minecraft.world.phys.Vec3 centre() {
			var others = mob.level().getEntitiesOfClass(CreatureEntity.class, mob.getBoundingBox().inflate(RANGE),
				other -> other != mob && other.isAlive() && kind.equals(other.creatureId()));
			if (others.isEmpty()) return null;
			double x = 0, y = 0, z = 0;
			for (var other : others) { x += other.getX(); y += other.getY(); z += other.getZ(); }
			return new net.minecraft.world.phys.Vec3(x / others.size(), y / others.size(), z / others.size());
		}
	}

	/**
	 * Outside its hours a creature goes home and stays there.
	 *
	 * <p>Placed above wandering and every drive, so a night creature is simply
	 * not out in the day - which is what lets a player learn when and where to
	 * find it.
	 */
	static final class RestGoal extends Goal {
		private final PathfinderMob mob;
		private final CreatureDrives drives;
		private int nextPath;

		RestGoal(PathfinderMob mob, CreatureDrives drives) {
			this.mob = mob;
			this.drives = drives;
			setFlags(EnumSet.of(Flag.MOVE, Flag.JUMP));
		}

		@Override public boolean canUse() { return drives.getActivity() != CreatureActivity.ALWAYS && !active(mob, drives) && mob.getTarget() == null; }
		@Override public boolean canContinueToUse() { return canUse(); }
		@Override public void start() { nextPath = 0; }

		@Override public void tick() {
			BlockPos home = mob.hasHome() ? mob.getHomePosition() : null;
			if (home == null || mob.blockPosition().distSqr(home) <= 9.0) { mob.getNavigation().stop(); return; }
			if (nextPath-- <= 0) { mob.getNavigation().moveTo(home.getX() + .5, home.getY(), home.getZ() + .5, 1.0); nextPath = 40; }
		}

		@Override public void stop() { mob.getNavigation().stop(); }
	}
}

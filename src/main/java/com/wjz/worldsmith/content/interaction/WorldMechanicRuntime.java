package com.wjz.worldsmith.content.interaction;

import com.wjz.worldsmith.Worldsmith;
import com.wjz.worldsmith.content.WorldBlockBindings;
import com.wjz.worldsmith.content.WorldRewardItems;
import com.wjz.worldsmith.content.WorldsmithCustomBlocks;
import com.wjz.worldsmith.content.creature.CreatureEntity;
import com.wjz.worldsmith.content.creature.CreatureRuntime;
import com.wjz.worldsmith.content.item.CustomItemRuntime;
import com.wjz.worldsmith.core.content.*;
import com.wjz.worldsmith.core.model.WorldsmithPack;
import static com.wjz.worldsmith.content.interaction.MechanicInspection.Code.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Difficulty;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;

/** Player events -> bounded pattern/state checks -> staged native effects -> durable anchor state. */
public final class WorldMechanicRuntime {
    public static final int MAX_CANDIDATES_PER_EVENT = 512;
    public static final int MAX_CELL_CHECKS_PER_EVENT = 8192;
    private static final int STAGED_FLAGS = Block.UPDATE_SKIP_ALL_SIDEEFFECTS;
    private static final Map<ServerLevel, Bound> WORLDS = new ConcurrentHashMap<>();
    private static volatile Snapshot clientSnapshot;
    private static boolean registered;

    private WorldMechanicRuntime() {}

    public static synchronized void register() {
        if (registered) return;
        UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
            if (hand != InteractionHand.MAIN_HAND || !player.isAlive() || player.isSpectator() || !player.mayBuild() || player.isShiftKeyDown())
                return InteractionResult.PASS;
            if (level.isClientSide()) {
                Snapshot snapshot = clientSnapshot;
                return snapshot != null && previewUse(snapshot, level, player, hit.getBlockPos())
                    ? InteractionResult.SUCCESS : InteractionResult.PASS;
            }
            if (!(player instanceof ServerPlayer serverPlayer) || !(level instanceof ServerLevel serverLevel)) return InteractionResult.PASS;
            return trigger(serverLevel, serverPlayer, hit.getBlockPos(), WorldMechanicEvent.USE_BLOCK);
        });
        registered = true;
    }

    /** Called only after the entire native BlockItem.place operation has succeeded and charged its item. */
    public static void afterPlayerPlacement(ServerPlayer player, BlockPos placed) {
        if (!(player.level() instanceof ServerLevel level)) return;
        trigger(level, player, placed, WorldMechanicEvent.BLOCK_PLACED);
    }

    public static Snapshot prepare(WorldsmithPack pack, WorldBlockBindings.Resolver blocks,
                                   CustomItemRuntime.Snapshot items, CreatureRuntime.Snapshot creatures) {
        String scope = pack.getManifest().getId();
        if (!scope.equals(blocks.snapshot().getScope()) || !scope.equals(items.bundleHash()) || !scope.equals(creatures.bundleHash()))
            throw new IllegalArgumentException("Mechanic resolvers belong to different world bundles");
        var diagnostics = WorldMechanicValidation.validate(pack.getMechanics());
        if (!diagnostics.isEmpty()) throw new IllegalArgumentException("Invalid world mechanics: " + diagnostics);
        return new Snapshot(scope, WorldMechanicValidation.freeze(pack.getMechanics()), blocks, items, creatures);
    }

    public static void bind(ServerLevel level, Snapshot snapshot) {
        Bound previous = WORLDS.get(level);
        if (previous != null) {
            if (!previous.snapshot.scope.equals(snapshot.scope) || !previous.snapshot.library.equals(snapshot.library))
                throw new IllegalStateException("A running dimension already owns different mechanic rules");
            return;
        }
        // Native SavedDataStorage treats a decode failure like a missing file. A one-shot ledger must
        // distinguish those cases rather than replace unreadable history with a new, replayable ledger.
        var dataRoot = net.minecraft.world.level.dimension.DimensionType.getStorageFolder(level.dimension(),
            level.getServer().getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT)).resolve("data");
        WorldMechanicSavedData ledger = WorldMechanicSavedData.load(level.getDataStorage(), dataRoot, snapshot.scope, snapshot.definitions);
        WORLDS.put(level, new Bound(snapshot, ledger));
    }
    public static void unbind(ServerLevel level) { WORLDS.remove(level); }
    public static Snapshot snapshot(ServerLevel level) { Bound bound = WORLDS.get(level); return bound == null ? null : bound.snapshot; }
    public static void activateClient(Snapshot snapshot) { clientSnapshot = Objects.requireNonNull(snapshot); }
    public static void clearClient() { clientSnapshot = null; }
    public static Snapshot clientSnapshot() { return clientSnapshot; }

    /**
     * Inspect only a manually selected device/anchor, even if its pattern is incomplete. The caller
     * gates journal visibility and rate-limits requests; this boundary independently checks the world,
     * scope, reach and loaded area. No ledger allocation/planning, entity construction, randomness,
     * inventory application, chunk loading or persistent writes are permitted on this path.
     */
    public static MechanicInspection inspect(ServerPlayer player, String mechanicId, BlockPos anchor) {
        if (player == null || anchor == null || mechanicId == null || !WorldMechanicValidation.validId(mechanicId)
            || !(player.level() instanceof ServerLevel level)) return MechanicInspection.of(UNAVAILABLE);
        if (!level.getServer().isSameThread()) throw new IllegalStateException("World mechanics must inspect on the owning server thread");
        Bound bound = WORLDS.get(level);
        if (bound == null || bound.busy || !player.isAlive() || player.isSpectator() || !scopeReady(level, bound)
            || !player.isWithinBlockInteractionRange(anchor, 1.0)) return MechanicInspection.of(UNAVAILABLE);
        if (!level.isInValidBounds(anchor) || !level.getWorldBorder().isWithinBounds(anchor)) return MechanicInspection.of(WRONG_ANCHOR);
        if (level.getChunkSource().getChunkNow(anchor.getX() >> 4, anchor.getZ() >> 4) == null) return MechanicInspection.of(UNLOADED);
        if (!level.mayInteract(player, anchor)) return MechanicInspection.of(PROTECTED);
        List<Variant> variants = bound.snapshot.inspectionIndex.get(mechanicId);
        if (variants == null) return MechanicInspection.of(UNAVAILABLE);
        Budget budget = new Budget();
        MechanicInspection best = null;
        for (Variant variant : variants) {
            if (!budget.candidate()) return best == null ? MechanicInspection.of(UNAVAILABLE) : best;
            MechanicInspection result;
            try { result = inspectVariant(level, player, bound, anchor, variant, budget); }
            catch (Rejected rejected) { result = observation(rejected.code, variant, player); }
            catch (RuntimeException failure) {
                Worldsmith.LOGGER.warn("Mechanic {}:{} inspection failed at {}", mechanicId, variant.rule.getId(), anchor, failure);
                return MechanicInspection.of(UNAVAILABLE);
            }
            if (result.code() == READY) return result;
            best = prefer(best, result);
        }
        return best == null ? MechanicInspection.of(UNAVAILABLE) : best;
    }

    private static MechanicInspection inspectVariant(ServerLevel level, ServerPlayer player, Bound bound,
                                                      BlockPos anchor, Variant variant, Budget budget) {
        String key = WorldMechanicSavedData.key(variant.mechanic.getId(), anchor.asLong());
        MechanicInspection state = inspectState(bound.ledger, key, variant.mechanic, variant.rule, level.getGameTime());
        if (state.code() != READY) return state;
        PatternCheck pattern = inspectPattern(level, anchor, variant, budget);
        if (pattern.code != READY) return new MechanicInspection(pattern.code, variant.rule.getId(), pattern.missing,
            requiredItems(variant), heldItems(variant, player, bound.snapshot.items), 0);
        if (!variant.rule.getBiomes().isEmpty() && !loaded(level, new AABB(anchor).inflate(8))) return observation(UNLOADED, variant, player);
        if (!biomeMatches(bound.snapshot, level, anchor, variant.rule)) return observation(WRONG_BIOME, variant, player);
        if (variant.rule.getEvent() == WorldMechanicEvent.USE_BLOCK) {
            if (!variant.recognizesInput(player.getMainHandItem(), bound.snapshot.items)) return observation(WRONG_ITEM, variant, player);
            if (!variant.matchesInput(player.getMainHandItem(), bound.snapshot.items)) return observation(INSUFFICIENT_ITEMS, variant, player);
        }
        preflight(level, player, bound, anchor, variant, pattern.checked, budget);
        return observation(READY, variant, player);
    }

    /** Ledger eligibility and transition-counter checks without calling plan, bind or SavedDataStorage. */
    static MechanicInspection inspectState(WorldMechanicSavedData ledger, String key, WorldMechanicDefinition definition,
                                           WorldMechanicRule rule, long now) {
        var state = ledger.state();
        var current = ledger.progress(key);
        MechanicInspection.Code code;
        long remaining = 0;
        if (state.quarantined()) code = QUARANTINED;
        else if (now < 0 || state.scope().isEmpty()) code = UNAVAILABLE;
        else if (!(current == null ? definition.getInitialState() : current.state()).equals(rule.getFromState())) code = SPENT;
        else if (current != null && now < current.readyAt()) { code = COOLDOWN; remaining = Math.min(current.readyAt() - now, WorldMechanicValidation.MAX_COOLDOWN_TICKS); }
        else if (current == null && state.instances().size() >= WorldMechanicSavedData.MAX_INSTANCES) code = INSTANCE_LIMIT;
        else if (state.revision() == Long.MAX_VALUE || current != null && current.activations() == Long.MAX_VALUE
            || now > Long.MAX_VALUE - rule.getCooldownTicks()) code = UNAVAILABLE;
        else code = READY;
        return new MechanicInspection(code, rule.getId(), 0, rule.getHeldItem() == null ? 0 : rule.getHeldItem().getCount(), 0, remaining);
    }

    private static boolean scopeReady(ServerLevel level, Bound bound) {
        Snapshot snapshot = bound.snapshot;
        return snapshot.scope.equals(bound.ledger.state().scope())
            && Objects.equals(WorldBlockBindings.active(), snapshot.blocks.snapshot())
            && CustomItemRuntime.snapshot(level) == snapshot.items && CreatureRuntime.snapshot(level) == snapshot.creatures;
    }

    private static int requiredItems(Variant variant) { return variant.rule.getHeldItem() == null ? 0 : variant.rule.getHeldItem().getCount(); }
    private static int heldItems(Variant variant, Player player, CustomItemRuntime.Snapshot items) {
        return variant.recognizesInput(player.getMainHandItem(), items) ? Math.min(64, player.getMainHandItem().getCount()) : 0;
    }
    private static MechanicInspection observation(MechanicInspection.Code code, Variant variant, Player player) {
        return new MechanicInspection(code, variant.rule.getId(), 0, requiredItems(variant),
            heldItems(variant, player, variant.snapshot.items), 0);
    }

    /** Ready routes always win; otherwise prefer an applicable/completed pattern over an unrelated route. */
    static MechanicInspection prefer(MechanicInspection previous, MechanicInspection next) {
        if (previous == null || priority(next.code()) < priority(previous.code())) return next;
        if (next.code() == previous.code()) {
            if (next.code() == INCOMPLETE_PATTERN && next.missingCells() < previous.missingCells()) return next;
            if (next.code() == INSUFFICIENT_ITEMS && next.requiredItems() < previous.requiredItems()) return next;
        }
        return previous;
    }
    private static int priority(MechanicInspection.Code code) {
        return switch (code) {
            case READY -> 0;
            case QUARANTINED -> 1;
            case COOLDOWN, INSTANCE_LIMIT -> 2;
            case WRONG_BIOME, NO_SPACE, OBSTRUCTED, PROTECTED -> 3;
            case INSUFFICIENT_ITEMS -> 4;
            case WRONG_ITEM -> 5;
            case INCOMPLETE_PATTERN -> 6;
            case UNLOADED -> 7;
            case WRONG_ANCHOR -> 8;
            case SPENT -> 9;
            case UNAVAILABLE -> 10;
        };
    }

    private static PatternCheck inspectPattern(Level level, BlockPos anchor, Variant variant, Budget budget) {
        Map<BlockPos, BlockState> states = new LinkedHashMap<>();
        int missing = 0;
        boolean wrongAnchor = false;
        for (Cell cell : variant.cells) {
            if (!budget.cell()) throw new Rejected(UNAVAILABLE);
            BlockPos pos = anchor.offset(cell.offset);
            if (!level.isInValidBounds(pos) || !level.getWorldBorder().isWithinBounds(pos)) return new PatternCheck(WRONG_ANCHOR, 0, Map.of());
            if (!(level instanceof ServerLevel server)) return new PatternCheck(UNAVAILABLE, 0, Map.of());
            var chunk = server.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
            if (chunk == null) return new PatternCheck(UNLOADED, 0, Map.of());
            BlockState actual = chunk.getBlockState(pos);
            if (!cell.predicate.matches(actual, variant.inverseRotation)) {
                missing++;
                if (cell.offset.equals(BlockPos.ZERO)) wrongAnchor = true;
            }
            states.put(pos, actual);
        }
        return new PatternCheck(wrongAnchor ? WRONG_ANCHOR : missing > 0 ? INCOMPLETE_PATTERN : READY, missing, states);
    }
    private record PatternCheck(MechanicInspection.Code code, int missing, Map<BlockPos, BlockState> checked) {}

    private static boolean previewUse(Snapshot snapshot, Level level, Player player, BlockPos anchor) {
        if (!level.hasChunkAt(anchor)) return false;
        var candidates = snapshot.useIndex.getOrDefault(level.getBlockState(anchor).getBlock(), List.of());
        Budget budget = new Budget();
        for (Trigger candidate : candidates) {
            if (!budget.candidate()) break;
            Variant variant = candidate.variant;
            if (!variant.recognizesInput(player.getMainHandItem(), snapshot.items)) continue;
            if (match(level, anchor, variant, budget) != null) return true;
        }
        return false;
    }

    private static InteractionResult trigger(ServerLevel level, ServerPlayer player, BlockPos changed, WorldMechanicEvent event) {
        Bound bound = WORLDS.get(level);
        if (bound == null || bound.busy || !player.isAlive() || player.isSpectator() || !player.mayBuild()
            || !player.isWithinBlockInteractionRange(changed, 1.0) || !level.hasChunkAt(changed) || !level.mayInteract(player, changed))
            return InteractionResult.PASS;
        if (!level.getServer().isSameThread()) throw new IllegalStateException("World mechanics must execute on the owning server thread");
        var index = event == WorldMechanicEvent.USE_BLOCK ? bound.snapshot.useIndex : bound.snapshot.placeIndex;
        var candidates = index.getOrDefault(level.getBlockState(changed).getBlock(), List.of());
        if (candidates.isEmpty()) return InteractionResult.PASS;
        if (!bound.admit(player.getUUID(), level.getGameTime())) return InteractionResult.FAIL;
        Budget budget = new Budget();
        var seen = new HashSet<Attempt>();
        MechanicInspection blocked = null;
        for (Trigger candidate : candidates) {
            if (!budget.candidate()) break;
            Variant variant = candidate.variant;
            BlockPos anchor = changed.subtract(candidate.offset);
            if (!seen.add(new Attempt(variant, anchor))) continue;
            if (event == WorldMechanicEvent.USE_BLOCK && !variant.recognizesInput(player.getMainHandItem(), bound.snapshot.items)) continue;
            String key = WorldMechanicSavedData.key(variant.mechanic.getId(), anchor.asLong());
            MechanicInspection state = inspectState(bound.ledger, key, variant.mechanic, variant.rule, level.getGameTime());
            boolean eligible = state.code() == READY;
            boolean biome = biomeMatches(bound.snapshot, level, anchor, variant.rule);
            if (event == WorldMechanicEvent.BLOCK_PLACED && (!eligible || !biome)) continue;
            Map<BlockPos, BlockState> checked = match(level, anchor, variant, budget);
            if (checked == null) continue;
            // A recognizable device owns the click even when temporarily unavailable. Continue searching
            // for a lower-cost/state-matching recipe, but never fall through into vanilla block/item use.
            if (!eligible) {
                blocked = prefer(blocked, state);
                continue;
            }
            if (!biome) { blocked = prefer(blocked, observation(WRONG_BIOME, variant, player)); continue; }
            if (event == WorldMechanicEvent.USE_BLOCK && !variant.matchesInput(player.getMainHandItem(), bound.snapshot.items)) {
                blocked = prefer(blocked, observation(INSUFFICIENT_ITEMS, variant, player)); continue;
            }
            Preflight planned;
            try {
                planned = preflight(level, player, bound, anchor, variant, checked, budget);
            } catch (Rejected rejected) {
                if (event == WorldMechanicEvent.USE_BLOCK) blocked = prefer(blocked, observation(rejected.code, variant, player));
                continue;
            } catch (RuntimeException failure) {
                Worldsmith.LOGGER.error("Mechanic {}:{} preflight failed at {}", variant.mechanic.getId(), variant.rule.getId(), anchor, failure);
                player.sendOverlayMessage(MechanicInspection.of(UNAVAILABLE).message());
                return InteractionResult.FAIL;
            }
            bound.busy = true;
            try {
                execute(level, player, bound, anchor, key, variant, checked, planned);
                return InteractionResult.SUCCESS;
            } catch (Rejected rejected) {
                if (event == WorldMechanicEvent.USE_BLOCK) player.sendOverlayMessage(
                    bound.ledger.state().quarantined() ? MechanicInspection.of(QUARANTINED).message() : observation(rejected.code, variant, player).message());
                return InteractionResult.FAIL;
            } catch (RuntimeException failure) {
                Worldsmith.LOGGER.error("Mechanic {}:{} failed at {}", variant.mechanic.getId(), variant.rule.getId(), anchor, failure);
                player.sendOverlayMessage(MechanicInspection.of(bound.ledger.state().quarantined() ? QUARANTINED : UNAVAILABLE).message());
                return InteractionResult.FAIL;
            } finally { bound.busy = false; }
        }
        if (blocked != null) { player.sendOverlayMessage(blocked.message()); return InteractionResult.FAIL; }
        return InteractionResult.PASS;
    }

    private static boolean biomeMatches(Snapshot snapshot, ServerLevel level, BlockPos anchor, WorldMechanicRule rule) {
        if (!level.hasChunkAt(anchor)) return false;
        if (rule.getBiomes().isEmpty()) return true;
        // BiomeManager samples neighbouring quart cells; guard those chunks before native lookup.
        if (!loaded(level, new AABB(anchor).inflate(8))) return false;
        String nativeId = level.getBiome(anchor).unwrapKey().map(key -> key.identifier().toString()).orElse("");
        return rule.getBiomes().stream().anyMatch(id -> nativeId.equals(snapshot.creatures.biomeBindings().get(id)));
    }

    private static Map<BlockPos, BlockState> match(Level level, BlockPos anchor, Variant variant, Budget budget) {
        Map<BlockPos, BlockState> states = new LinkedHashMap<>();
        for (Cell cell : variant.cells) {
            if (!budget.cell()) return null;
            BlockPos pos = anchor.offset(cell.offset);
            if (!level.isInValidBounds(pos) || !level.hasChunkAt(pos) || !level.getWorldBorder().isWithinBounds(pos)) return null;
            BlockState actual = level.getBlockState(pos);
            if (!cell.predicate.matches(actual, variant.inverseRotation)) return null;
            states.put(pos, actual);
        }
        return states;
    }

    /** Shared, read-only transaction planning; callers alone decide whether to apply its result. */
    private static Preflight preflight(ServerLevel level, ServerPlayer player, Bound bound, BlockPos anchor,
                                      Variant variant, Map<BlockPos, BlockState> checked, Budget budget) {
        Snapshot snapshot = bound.snapshot;
        if (!scopeReady(level, bound)) throw new Rejected(UNAVAILABLE);
        if (!player.mayBuild() || !level.mayInteract(player, anchor)) throw new Rejected(PROTECTED);
        var inventory = new MechanicInventoryTransaction(player.getInventory());
        if (variant.rule.getHeldItem() != null && !inventory.offer(stack -> variant.matchesInput(stack, snapshot.items), variant.rule.getHeldItem().getCount()))
            throw new Rejected(INSUFFICIENT_ITEMS);
        for (ItemStack output : variant.outputs) if (!inventory.insert(output)) throw new Rejected(NO_SPACE);
        Map<BlockPos, BlockState> writes = new LinkedHashMap<>();
        for (Cell cell : variant.cells) if (cell.consume) writes.put(anchor.offset(cell.offset), Blocks.AIR.defaultBlockState());
        for (Write write : variant.writes) writes.put(anchor.offset(write.offset), write.state);
        ReadOnlyBlocks proposed = new ReadOnlyBlocks(level, writes, budget);
        for (var write : writes.entrySet()) {
            BlockPos pos = write.getKey(); BlockState old = checked.get(pos);
            if (old == null) throw new Rejected(UNAVAILABLE);
            if (!loaded(level, new AABB(pos).inflate(1))) throw new Rejected(UNLOADED);
            if (!level.mayInteract(player, pos) || old.hasBlockEntity() || write.getValue().hasBlockEntity()
                || old.getDestroySpeed(level, pos) < 0) throw new Rejected(PROTECTED);
            // getBlockEntity() promotes pending NBT even with EntityCreationType.CHECK. Inspect maps
            // directly instead so a guide query never instantiates a block entity or dirties a chunk.
            var chunk = level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
            if (chunk == null) throw new Rejected(UNLOADED);
            if (chunk.getBlockEntities().containsKey(pos) || chunk.getBlockEntityNbt(pos) != null) throw new Rejected(PROTECTED);
            // A conversion/gate may not replace a matched air cell with a solid block through a player/entity.
            if (!write.getValue().isAir() && !level.isUnobstructed(null,
                write.getValue().getCollisionShape(proposed, pos, CollisionContext.empty()).move(pos))) throw new Rejected(OBSTRUCTED);
        }
        inspectSpawn(level, snapshot, anchor, variant, proposed);
        return new Preflight(inventory, writes);
    }
    private record Preflight(MechanicInventoryTransaction inventory, Map<BlockPos, BlockState> writes) {}

    private static void execute(ServerLevel level, ServerPlayer player, Bound bound, BlockPos anchor, String key,
                                Variant variant, Map<BlockPos, BlockState> checked, Preflight planned) {
        Snapshot snapshot = bound.snapshot;
        MechanicInventoryTransaction inventory = planned.inventory;
        Map<BlockPos, BlockState> writes = planned.writes;
        CreatureEntity creature = prepareCreature(level, snapshot, anchor, variant);
        var stateUpdate = bound.ledger.plan(key, variant.mechanic, variant.rule, level.getGameTime());
        inventory.assertUnchanged(); stateUpdate.assertUnchanged();
        for (var entry : checked.entrySet()) if (level.getBlockState(entry.getKey()) != entry.getValue())
            throw new Rejected(UNAVAILABLE);
        List<BlockPos> changed = new ArrayList<>();
        boolean inventoryStarted = false;
        try {
            for (var write : writes.entrySet()) {
                if (level.getBlockState(write.getKey()) == write.getValue()) continue;
                changed.add(write.getKey());
                if (!level.setBlock(write.getKey(), write.getValue(), STAGED_FLAGS) || level.getBlockState(write.getKey()) != write.getValue())
                    throw new Rejected(UNAVAILABLE);
            }
            if (creature != null) {
                // Collision is checked against the provisional, fully changed pattern, not the former altar.
                if (!creature.checkSpawnObstruction(level)) throw new Rejected(OBSTRUCTED);
                if (!level.addFreshEntity(creature)) throw new Rejected(UNAVAILABLE);
            }
            inventory.assertUnchanged(); stateUpdate.assertUnchanged();
            inventoryStarted = true; inventory.apply();
            stateUpdate.commit(); // Final fallible boundary. Feedback/neighbor propagation below never roll this back.
        } catch (RuntimeException failure) {
            boolean restored = true;
            if (creature != null) restored &= compensate(failure, creature::discard);
            if (inventoryStarted) restored &= compensate(failure, inventory::rollback);
            Collections.reverse(changed);
            for (BlockPos pos : changed) {
                BlockState original = checked.get(pos);
                restored &= compensate(failure, () -> {
                    level.setBlock(pos, original, STAGED_FLAGS | Block.UPDATE_CLIENTS);
                    if (level.getBlockState(pos) != original) throw new IllegalStateException("Mechanic rollback failed at " + pos);
                });
            }
            if (!restored) {
                bound.ledger.quarantine();
                Worldsmith.LOGGER.error("Mechanic ledger quarantined after incomplete rollback at {} in {}", anchor, level.dimension().identifier());
            }
            compensate(failure, () -> syncInventory(player));
            throw failure;
        }
        // Durable success is not reclassified as a failed offering if an observer or cosmetic effect throws.
        com.wjz.worldsmith.content.quest.server.QuestRuntime.afterMechanicActivation(player, variant.mechanic.getId());
        try {
            for (BlockPos pos : changed) {
                BlockState current = level.getBlockState(pos);
                level.sendBlockUpdated(pos, checked.get(pos), current, Block.UPDATE_ALL);
                current.onPlace(level, pos, checked.get(pos), false);
                level.updateNeighboursOnBlockSet(pos, checked.get(pos));
                checked.get(pos).updateIndirectNeighbourShapes(level, pos, Block.UPDATE_CLIENTS, 64);
                current.updateNeighbourShapes(level, pos, Block.UPDATE_CLIENTS, 64);
                current.updateIndirectNeighbourShapes(level, pos, Block.UPDATE_CLIENTS, 64);
            }
            player.getInventory().setChanged(); syncInventory(player);
            level.playSound(null, anchor, SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.BLOCKS, .8F, 1.1F);
            level.sendParticles(ParticleTypes.ENCHANT, anchor.getX() + .5, anchor.getY() + 1.0, anchor.getZ() + .5, 20, .4, .5, .4, .1);
            player.sendOverlayMessage(Component.translatable("worldsmith.mechanics.activated", variant.mechanic.getDisplayName()));
        } catch (RuntimeException observerFailure) {
            Worldsmith.LOGGER.warn("Mechanic {} committed; a post-commit notification failed", variant.mechanic.getId(), observerFailure);
        }
    }

    private static CreatureEntity prepareCreature(ServerLevel level, Snapshot snapshot, BlockPos anchor, Variant variant) {
        if (variant.spawn == null) return null;
        var definition = snapshot.creatures.definitions().get(variant.spawn.creature);
        if (definition.getCategory() == CreatureCategory.HOSTILE && level.getDifficulty() == Difficulty.PEACEFUL)
            throw new Rejected(UNAVAILABLE);
        BlockPos pos = anchor.offset(variant.spawn.offset);
        if (!level.isInValidBounds(pos) || !level.hasChunkAt(pos)) throw new Rejected(UNLOADED);
        var type = definition.getCategory() == CreatureCategory.PASSIVE ? CreatureRuntime.passiveType()
            : definition.getBoss() == null ? CreatureRuntime.hostileType() : CreatureRuntime.encounterBossType();
        CreatureEntity creature = type.create(level, EntitySpawnReason.TRIGGERED);
        if (creature == null) throw new Rejected(UNAVAILABLE);
        creature.snapTo(pos, 0, 0);
        creature.initialize(snapshot.scope, definition.getId(), level.getRandom().nextLong());
        creature.setPersistenceRequired();
        if (!loaded(level, creature.getBoundingBox().inflate(1)) || !level.getWorldBorder().isWithinBounds(creature.getBoundingBox())
            || creature.getBoundingBox().minY < level.getMinY() || creature.getBoundingBox().maxY > level.getMaxY())
            throw new Rejected(UNLOADED);
        return creature;
    }

    /** Check the authored body against the provisional blocks without ever creating a creature. */
    private static void inspectSpawn(ServerLevel level, Snapshot snapshot, BlockPos anchor, Variant variant, ReadOnlyBlocks proposed) {
        if (variant.spawn == null) return;
        var definition = snapshot.creatures.definitions().get(variant.spawn.creature);
        if (definition.getCategory() == CreatureCategory.HOSTILE && level.getDifficulty() == Difficulty.PEACEFUL)
            throw new Rejected(UNAVAILABLE);
        BlockPos pos = anchor.offset(variant.spawn.offset);
        var attributes = definition.getAttributes();
        AABB body = EntityDimensions.scalable(attributes.getWidth(), attributes.getHeight()).makeBoundingBox(Vec3.atBottomCenterOf(pos));
        if (!loaded(level, body.inflate(1))) throw new Rejected(UNLOADED);
        if (!level.getWorldBorder().isWithinBounds(body) || body.minY < level.getMinY() || body.maxY > level.getMaxY())
            throw new Rejected(OBSTRUCTED);
        if (!level.isUnobstructed(null, Shapes.create(body)) || !level.getEntityCollisions(null, body).isEmpty()) throw new Rejected(OBSTRUCTED);
        if (hasBodyObstruction(proposed, body)) throw new Rejected(OBSTRUCTED);
    }

    /** Include one surrounding cell for collision shapes that protrude outside their block (fences etc.). */
    static boolean hasBodyObstruction(BlockGetter blocks, AABB body) {
        var shape = Shapes.create(body);
        for (BlockPos pos : BlockPos.betweenClosed(body.inflate(1))) {
            BlockState state = blocks.getBlockState(pos);
            if (!state.getFluidState().isEmpty() && new AABB(pos).intersects(body)) return true;
            if (Shapes.joinIsNotEmpty(state.getCollisionShape(blocks, pos, CollisionContext.empty()).move(pos), shape, BooleanOp.AND)) return true;
        }
        return false;
    }

    /** A bounded block overlay. Pending block entities are deliberately never promoted on a read. */
    private static final class ReadOnlyBlocks implements BlockGetter {
        final ServerLevel level;
        final Map<BlockPos, BlockState> writes;
        final Map<BlockPos, BlockState> reads = new HashMap<>();
        final Budget budget;
        ReadOnlyBlocks(ServerLevel level, Map<BlockPos, BlockState> writes, Budget budget) {
            this.level = level; this.writes = writes; this.budget = budget;
        }
        @Override public BlockState getBlockState(BlockPos pos) {
            BlockState cached = reads.get(pos);
            if (cached != null) return cached;
            if (!budget.cell()) throw new Rejected(UNAVAILABLE);
            if (!level.isInValidBounds(pos)) return Blocks.AIR.defaultBlockState();
            var chunk = level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
            if (chunk == null) throw new Rejected(UNLOADED);
            BlockState state = writes.get(pos);
            if (state == null) state = chunk.getBlockState(pos);
            reads.put(pos.immutable(), state);
            return state;
        }
        @Override public FluidState getFluidState(BlockPos pos) { return getBlockState(pos).getFluidState(); }
        @Override public BlockEntity getBlockEntity(BlockPos pos) { return null; }
        @Override public int getHeight() { return level.getHeight(); }
        @Override public int getMinY() { return level.getMinY(); }
    }

    private static boolean loaded(ServerLevel level, AABB box) {
        int minX = ((int)Math.floor(box.minX)) >> 4, maxX = ((int)Math.floor(box.maxX)) >> 4;
        int minZ = ((int)Math.floor(box.minZ)) >> 4, maxZ = ((int)Math.floor(box.maxZ)) >> 4;
        for (int x = minX; x <= maxX; x++) for (int z = minZ; z <= maxZ; z++)
            if (level.getChunkSource().getChunkNow(x, z) == null) return false;
        return true;
    }
    private static void syncInventory(ServerPlayer player) {
        player.inventoryMenu.sendAllDataToRemote();
        if (player.containerMenu != player.inventoryMenu) player.containerMenu.sendAllDataToRemote();
    }
    private static boolean compensate(RuntimeException original, Runnable step) {
        try { step.run(); return true; }
        catch (RuntimeException rollbackFailure) { original.addSuppressed(rollbackFailure); return false; }
    }

    static BlockPos rotate(MechanicOffset pos, int turns) {
        return switch (turns) {
            case 0 -> new BlockPos(pos.getX(), pos.getY(), pos.getZ());
            case 1 -> new BlockPos(-pos.getZ(), pos.getY(), pos.getX());
            case 2 -> new BlockPos(-pos.getX(), pos.getY(), -pos.getZ());
            case 3 -> new BlockPos(pos.getZ(), pos.getY(), -pos.getX());
            default -> throw new IllegalArgumentException("Rotation is 0..3 quarter turns");
        };
    }
    private static final Rotation[] ROTATIONS = {Rotation.NONE, Rotation.CLOCKWISE_90, Rotation.CLOCKWISE_180, Rotation.COUNTERCLOCKWISE_90};

    static NativePredicate resolve(MechanicBlockPredicate predicate, WorldBlockBindings.Resolver blocks) {
        String reference = predicate.getBlock();
        BlockState state;
        if (WorldsmithCustomBlocks.isReservedNativeId(reference)) throw new IllegalArgumentException("Mechanics use logical block aliases, never native hosts");
        if (reference.startsWith("worldsmith:content/")) {
            if (!predicate.getProperties().isEmpty()) throw new IllegalArgumentException("Logical blocks retain their immutable world properties");
            state = blocks.resolve(reference);
        } else {
            var block = BuiltInRegistries.BLOCK.getOptional(Identifier.parse(reference)).orElseThrow(() -> new IllegalArgumentException("Unknown mechanic block: " + reference));
            state = block.defaultBlockState();
        }
        List<Property<?>> properties = new ArrayList<>();
        for (var entry : predicate.getProperties().entrySet()) {
            Property<?> property = state.getBlock().getStateDefinition().getProperty(entry.getKey());
            if (property == null) throw new IllegalArgumentException("Unknown mechanic block property: " + reference + "." + entry.getKey());
            state = withProperty(state, property, entry.getValue()); properties.add(property);
        }
        if (reference.startsWith("worldsmith:content/")) properties.add(WorldsmithCustomBlocks.LIGHT);
        return new NativePredicate(state, List.copyOf(properties));
    }
    private static <T extends Comparable<T>> BlockState withProperty(BlockState state, Property<T> property, String value) {
        return state.setValue(property, property.getValue(value).orElseThrow(() -> new IllegalArgumentException("Invalid mechanic property value: " + property.getName() + "=" + value)));
    }
    record NativePredicate(BlockState state, List<Property<?>> properties) {
        boolean matches(BlockState actual, Rotation inverse) {
            if (!actual.is(state.getBlock())) return false;
            BlockState unrotated = actual.rotate(inverse);
            for (Property<?> property : properties) if (!unrotated.getValue(property).equals(state.getValue(property))) return false;
            return true;
        }
    }
    private record Cell(BlockPos offset, NativePredicate predicate, boolean consume) {}
    private record Write(BlockPos offset, BlockState state) {}
    private record Spawn(String creature, BlockPos offset) {}
    private record Trigger(Variant variant, BlockPos offset) {}
    private record Attempt(Variant variant, BlockPos anchor) {}

    private static final class Variant {
        final Snapshot snapshot;
        final WorldMechanicDefinition mechanic;
        final WorldMechanicRule rule;
        final Rotation inverseRotation;
        final List<Cell> cells;
        final List<Write> writes;
        final List<ItemStack> outputs;
        final ItemStack input;
        final Spawn spawn;
        Variant(WorldMechanicDefinition mechanic, WorldMechanicRule rule, int rotation, Snapshot snapshot) {
            this.snapshot = snapshot; this.mechanic = mechanic; this.rule = rule; inverseRotation = ROTATIONS[(4 - rotation) % 4];
            cells = rule.getPattern().stream().map(cell -> new Cell(rotate(cell.getOffset(), rotation), resolve(cell.getBlock(), snapshot.blocks), cell.getConsume())).toList();
            var changes = new ArrayList<Write>(); var rewards = new ArrayList<ItemStack>(); Spawn creature = null;
            for (Cell cell : cells) if (cell.consume && cell.predicate.state.hasBlockEntity())
                throw new IllegalArgumentException("Mechanic pattern costs may not consume block entities");
            for (MechanicAction action : rule.getActions()) {
                if (action instanceof MechanicAction.SetBlock set) {
                    var state = resolve(set.getBlock(), snapshot.blocks).state.rotate(ROTATIONS[rotation]);
                    var old = cells.stream().filter(c -> c.offset.equals(rotate(set.getOffset(), rotation))).findFirst().orElseThrow();
                    if (state.hasBlockEntity() || old.predicate.state.hasBlockEntity()) throw new IllegalArgumentException("Mechanic block writes require ordinary data-free blocks");
                    changes.add(new Write(rotate(set.getOffset(), rotation), state));
                } else if (action instanceof MechanicAction.GiveItem give) {
                    rewards.add(WorldRewardItems.stack(give.getItem(), give.getCount(), snapshot.blocks, snapshot.items));
                } else if (action instanceof MechanicAction.SpawnCreature spawn) {
                    if (!snapshot.creatures.definitions().containsKey(spawn.getCreature())) throw new IllegalArgumentException("Unknown mechanic creature: " + spawn.getCreature());
                    creature = new Spawn(spawn.getCreature(), rotate(spawn.getOffset(), rotation));
                } else throw new IllegalArgumentException("Unsupported native mechanic action");
            }
            writes = List.copyOf(changes); outputs = List.copyOf(rewards); spawn = creature;
            input = rule.getHeldItem() == null ? ItemStack.EMPTY : WorldRewardItems.stack(rule.getHeldItem().getItem(), rule.getHeldItem().getCount(), snapshot.blocks, snapshot.items);
            for (String biome : rule.getBiomes()) if (!snapshot.creatures.biomeBindings().containsKey(biome))
                throw new IllegalArgumentException("Unknown mechanic biome: " + biome);
        }
        boolean matchesInput(ItemStack stack, CustomItemRuntime.Snapshot items) {
            return recognizesInput(stack, items) && (input.isEmpty() || stack.getCount() >= input.getCount());
        }
        boolean recognizesInput(ItemStack stack, CustomItemRuntime.Snapshot items) {
            if (input.isEmpty()) return stack.isEmpty();
            if (stack.isEmpty() || stack.getItem() != input.getItem()) return false;
            if (CustomItemRuntime.isLogicalId(rule.getHeldItem().getItem()))
                return items.isValidWorldStack(stack) && Objects.equals(stack.get(CustomItemRuntime.identityComponent()), input.get(CustomItemRuntime.identityComponent()));
            return true;
        }
    }

    public static final class Snapshot {
        private final String scope;
        private final WorldMechanicLibrary library;
        private final Map<String, WorldMechanicDefinition> definitions;
        private final WorldBlockBindings.Resolver blocks;
        private final CustomItemRuntime.Snapshot items;
        private final CreatureRuntime.Snapshot creatures;
        private final Map<Block, List<Trigger>> useIndex, placeIndex;
        private final Map<String, List<Variant>> inspectionIndex;
        private Snapshot(String scope, WorldMechanicLibrary library, WorldBlockBindings.Resolver blocks,
                         CustomItemRuntime.Snapshot items, CreatureRuntime.Snapshot creatures) {
            this.scope = scope; this.library = library; this.blocks = blocks; this.items = items; this.creatures = creatures;
            Map<String, WorldMechanicDefinition> definitions = new LinkedHashMap<>();
            Map<String, List<Variant>> inspection = new LinkedHashMap<>();
            Map<Block, List<Trigger>> use = new LinkedHashMap<>(), place = new LinkedHashMap<>();
            for (var definition : library.getMechanics().stream().sorted(Comparator.comparing(WorldMechanicDefinition::getId)).toList()) {
                definitions.put(definition.getId(), definition);
                List<Variant> variants = new ArrayList<>();
                for (var rule : definition.getRules().stream().sorted(Comparator.comparing(WorldMechanicRule::getId)).toList()) {
                    for (int rotation = 0; rotation < (rule.getRotateY() ? 4 : 1); rotation++) {
                        var variant = new Variant(definition, rule, rotation, this);
                        variants.add(variant);
                        for (Cell cell : variant.cells) {
                            if (rule.getEvent() == WorldMechanicEvent.USE_BLOCK && !cell.offset.equals(BlockPos.ZERO)) continue;
                            if (cell.predicate.state.isAir()) continue;
                            (rule.getEvent() == WorldMechanicEvent.USE_BLOCK ? use : place)
                                .computeIfAbsent(cell.predicate.state.getBlock(), ignored -> new ArrayList<>()).add(new Trigger(variant, cell.offset));
                        }
                    }
                }
                inspection.put(definition.getId(), List.copyOf(variants));
            }
            this.definitions = Collections.unmodifiableMap(definitions);
            inspectionIndex = Collections.unmodifiableMap(inspection);
            useIndex = freezeIndex(use); placeIndex = freezeIndex(place);
        }
        public String scope() { return scope; }
        public WorldMechanicLibrary library() { return library; }
        public Map<String, WorldMechanicDefinition> definitions() { return definitions; }
        public int placementCandidateCount(Block block) { return placeIndex.getOrDefault(block, List.of()).size(); }
        public int useCandidateCount(Block block) { return useIndex.getOrDefault(block, List.of()).size(); }
        private static Map<Block, List<Trigger>> freezeIndex(Map<Block, List<Trigger>> source) {
            Map<Block, List<Trigger>> result = new LinkedHashMap<>();
            source.forEach((block, triggers) -> {
                long worstReads = triggers.stream().mapToLong(trigger -> trigger.variant.cells.size()).sum();
                if (triggers.size() > MAX_CANDIDATES_PER_EVENT || worstReads > MAX_CELL_CHECKS_PER_EVENT)
                    throw new IllegalArgumentException("Mechanic trigger index exceeds the per-event budget for " + BuiltInRegistries.BLOCK.getKey(block)
                        + " (" + triggers.size() + " candidates, " + worstReads + " cell checks). Use more distinctive pattern blocks or fewer overlapping rules/rotations.");
                result.put(block, List.copyOf(triggers));
            });
            return Map.copyOf(result);
        }
    }
    private static final class Budget {
        int candidates, cells;
        boolean candidate() { return ++candidates <= MAX_CANDIDATES_PER_EVENT && cells < MAX_CELL_CHECKS_PER_EVENT; }
        boolean cell() { return ++cells <= MAX_CELL_CHECKS_PER_EVENT; }
    }
    private static final class Bound {
        final Snapshot snapshot;
        final WorldMechanicSavedData ledger;
        final Map<UUID, Integer> attempts = new HashMap<>();
        long tick = Long.MIN_VALUE;
        int total;
        boolean busy;
        Bound(Snapshot snapshot, WorldMechanicSavedData ledger) { this.snapshot = snapshot; this.ledger = ledger; }
        boolean admit(UUID player, long now) {
            if (tick != now) { tick = now; attempts.clear(); total = 0; }
            return ++total <= 128 && attempts.merge(player, 1, Integer::sum) <= 16;
        }
    }
    private static final class Rejected extends RuntimeException {
        final MechanicInspection.Code code;
        Rejected(MechanicInspection.Code code) { super(code.name()); this.code = code; }
    }
}

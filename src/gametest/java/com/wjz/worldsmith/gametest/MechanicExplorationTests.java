package com.wjz.worldsmith.gametest;

import com.wjz.worldsmith.content.WorldContentRuntime;
import com.wjz.worldsmith.content.creature.CreatureEntity;
import com.wjz.worldsmith.content.interaction.MechanicGuideProtocol;
import com.wjz.worldsmith.content.interaction.MechanicInspection;
import com.wjz.worldsmith.content.interaction.WorldMechanicRuntime;
import com.wjz.worldsmith.content.interaction.WorldMechanicSavedData;
import com.wjz.worldsmith.content.item.CustomItemRuntime;
import com.wjz.worldsmith.content.quest.server.QuestPlayerState;
import com.wjz.worldsmith.content.quest.server.QuestRuntime;
import com.wjz.worldsmith.core.content.MechanicGuides;
import com.wjz.worldsmith.core.examples.MechanicDiscoveryExample;
import com.wjz.worldsmith.core.structure.BuildPos;
import com.wjz.worldsmith.core.structure.StructureGeometryCompiler;
import com.wjz.worldsmith.worldgen.WorldsmithStructureTemplates;
import com.wjz.worldsmith.worldgen.MechanicDiscoveryWorldgenChecks;
import java.util.LinkedHashMap;
import java.util.List;
import net.fabricmc.fabric.api.attachment.v1.AttachmentTarget;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.NbtOps;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/** The exported example, not a second hand-written approximation of its rules or block entities. */
public final class MechanicExplorationTests {
    @GameTest(environment = "worldsmith_mechanics_smoke:exploration", maxTicks = 100, skyAccess = true, padding = 20)
    public void cluesToSummonToDroppedKeyToOpenPassage(GameTestHelper helper) throws Exception {
        ServerLevel level = helper.getLevel();
        var pack = MechanicDiscoveryExample.create();
        MechanicDiscoveryWorldgenChecks.verify(pack, level.getServer().getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT)
            .resolve("worldsmith-discovery-verification"));
        var biomeNames = new LinkedHashMap<String, String>();
        pack.getBiomes().getBiomes().forEach(b -> biomeNames.put(b.getId(), "worldsmith:generated/" + pack.getComputedId() + "/" + b.getId()));
        var prepared = WorldContentRuntime.prepare(pack, biomeNames);
        // GameTest environments run in distinct sequential batches but share a dimension's data storage.
        // Isolate this test's ledger without deleting/replacing any external run directory or save.
        var previousLedger = level.getDataStorage().get(WorldMechanicSavedData.TYPE);
        var testLedger = new WorldMechanicSavedData();
        level.getDataStorage().set(WorldMechanicSavedData.TYPE, testLedger);
        check(WorldContentRuntime.boundLevelCount() == 0, "Exploration batch overlapped another world's content binding");
        WorldContentRuntime.bindLevel(level, prepared);
        BlockPos base = helper.absolutePos(new BlockPos(0, 2, 0));
        ServerPlayer player = null;
        try {
            var geometry = StructureGeometryCompiler.compile(pack.getStructures().getStructures().getFirst().getBlueprint());
            var nativeTemplate = new StructureTemplate();
            nativeTemplate.load(BuiltInRegistries.BLOCK, WorldsmithStructureTemplates.encode(geometry, level.registryAccess(), null));
            check(nativeTemplate.placeInWorld(level, base, base, new StructurePlaceSettings(), level.getRandom(), Block.UPDATE_ALL),
                "The example's registry-validated native template was not placed");
            var sign = level.getBlockEntity(at(base, MechanicDiscoveryExample.ENTRY_SIGN_POSITION));
            check(sign instanceof SignBlockEntity && ((SignBlockEntity) sign).getFrontText().getMessage(1, false).getString().contains("日志"),
                "Native structure sign did not preserve its in-world journal clue");
            check(level.getBlockState(at(base, MechanicDiscoveryExample.ENTRY_SIGN_POSITION))
                .getValue(net.minecraft.world.level.block.StandingSignBlock.ROTATION) == 8,
                "Arrival exposes the clue's blank back rather than its readable front");
            var supplies = (Container) level.getBlockEntity(at(base, MechanicDiscoveryExample.MATERIALS_POSITION));
            check(supplies != null && supplies.getItem(0).is(Items.IRON_BLOCK) && supplies.getItem(0).getCount() == 1,
                "The real structure chest is missing the precise assembly material");

            player = helper.makeMockServerPlayerInLevel();
            player.setGameMode(GameType.SURVIVAL);
            GameType.SURVIVAL.updatePlayerAbilities(player.getAbilities());
            player.setNoGravity(true);
            player.getInventory().clearContent();
            player.getInventory().setSelectedSlot(0);
            check(!player.hasInfiniteMaterials() && player.mayBuild(), "Exploration needs finite survival inventory");
            BlockPos altar = at(base, MechanicDiscoveryExample.ALTAR_POSITION);
            BlockPos gate = at(base, MechanicDiscoveryExample.GATE_POSITION);
            standBeside(player, altar);

            // Reading a plan precedes construction and comes from the same definition used by runtime.
            var definition = pack.getMechanics().getMechanics().stream().filter(d -> d.getId().equals(MechanicDiscoveryExample.ALTAR)).findFirst().orElseThrow();
            var guide = MechanicGuides.describe(definition).getRules().getFirst();
            check(guide.getRule().equals(definition.getRules().getFirst()), "Journal plan drifted from its activation rule");
            check(QuestRuntime.canReadMechanicGuide(player, MechanicDiscoveryExample.ALTAR), "A missing pattern hid the unlocked quest's guide");
            var missing = guide.getRule().getPattern().stream().filter(cell -> {
                var p = cell.getOffset();
                return !BuiltInRegistries.BLOCK.getKey(level.getBlockState(altar.offset(p.getX(), p.getY(), p.getZ())).getBlock())
                    .toString().equals(cell.getBlock().getBlock());
            }).toList();
            check(missing.size() == 1 && missing.getFirst().getBlock().getBlock().equals("minecraft:iron_block"),
                "The specimen is not the guide's single missing-cell altar");
            var before = testLedger.state();
            boolean dirty = testLedger.isDirty();
            var initialHand = player.getMainHandItem().copy();
            var result = inspect(player, pack.getComputedId(), MechanicDiscoveryExample.ALTAR, altar, 1);
            check(result.code() == MechanicInspection.Code.INCOMPLETE_PATTERN && result.missingCells() == 1,
                "Manual inspection did not report the incomplete altar: " + result);
            check(testLedger.state() == before && testLedger.isDirty() == dirty && ItemStack.matches(initialHand, player.getMainHandItem())
                && guardians(level, altar).isEmpty() && level.getBlockState(altar.east()).isAir(),
                "Read-only inspection mutated ledger, materials, entities or blocks");
            check(inspect(player, "0".repeat(64), MechanicDiscoveryExample.ALTAR, altar, 2).code() == MechanicInspection.Code.UNAVAILABLE,
                "A stale-world query disclosed live inspection data");

            // Move the actual material out of the authored chest, then place the missing guide cell
            // through vanilla BlockItem.place. No give command, JSON task action or manual callback.
            player.getInventory().setItem(0, supplies.removeItem(0, 1));
            player.getInventory().setItem(1, supplies.removeItem(1, 1));
            player.getInventory().setItem(2, supplies.removeItem(2, 8));
            var offset = missing.getFirst().getOffset();
            place(player, altar.offset(offset.getX(), offset.getY(), offset.getZ()));
            check(player.getMainHandItem().isEmpty() && supplies.getItem(0).isEmpty(), "Assembly duplicated the collected material");
            check(level.getBlockState(altar.west()).isAir() && level.getBlockState(altar.east()).isAir(), "Summon did not consume its marked cells");
            var summoned = guardians(level, altar);
            check(summoned.size() == 1, "Native placement did not summon exactly one guardian");
            check(questState(player).mechanicActivationCount(MechanicDiscoveryExample.ALTAR) == 1,
                "Real altar activation did not reach the player's quest attachment");

            // The native damage/death/drop path provides the only gate key used by the test.
            CreatureEntity guardian = summoned.getFirst();
            player.getInventory().setSelectedSlot(1);
            check(guardian.hurtServer(level, player.damageSources().playerAttack(player), 100f), "The real player killing blow was rejected");
            check(guardian.isDeadOrDying() && guardian.getKillCredit() == player, "Native death lost player kill attribution");
            var drops = level.getEntitiesOfClass(ItemEntity.class, new AABB(altar).inflate(8), item -> {
                var identity = item.getItem().get(CustomItemRuntime.identityComponent());
                return identity != null && identity.bundleHash().equals(pack.getComputedId()) && identity.itemId().equals(MechanicDiscoveryExample.KEY);
            });
            check(drops.size() == 1 && drops.getFirst().getItem().getCount() == 1 && prepared.items().isCanonical(drops.getFirst().getItem()),
                "Guardian death did not produce exactly one canonical world-scoped key");
            var droppedKey = drops.getFirst();
            player.snapTo(droppedKey.getX(), droppedKey.getY(), droppedKey.getZ(), 0, 0);
            droppedKey.setNoPickUpDelay();
            droppedKey.playerTouch(player);
            check(droppedKey.isRemoved(), "The real item pickup did not transfer the key");
            player.getInventory().setSelectedSlot(1); // Sword is intentionally the wrong item.
            standBeside(player, gate);
            check(inspect(player, pack.getComputedId(), MechanicDiscoveryExample.GATE, gate, 3).code() == MechanicInspection.Code.WRONG_ITEM,
                "Wrong item was not distinguished from a broken gate");
            int keySlot = findKey(player);
            check(keySlot >= 0, "Picked-up key is missing from player inventory");
            player.getInventory().setSelectedSlot(keySlot);
            check(inspect(player, pack.getComputedId(), MechanicDiscoveryExample.GATE, gate, 4).code() == MechanicInspection.Code.READY,
                "The canonical dropped key did not make the gate ready");
            check(use(level, player, gate).consumesAction(), "The gate did not accept its real guardian drop");
            check(level.getBlockState(gate.offset(2, 0, 1)).isAir() && level.getBlockState(gate.offset(2, 1, 1)).isAir(),
                "Successful gate activation did not clear two blocks of passage headroom");
            check(findKey(player) < 0, "Opening the gate did not charge precisely its single key");
            check(inspect(player, pack.getComputedId(), MechanicDiscoveryExample.GATE, gate, 5).code() == MechanicInspection.Code.SPENT,
                "An opened gate was described as missing blocks rather than spent");
            QuestPlayerState state = questState(player);
            check(state.mechanicActivationCount(MechanicDiscoveryExample.GATE) == 1
                && state.progressFor(pack.getQuests().getQuests().getFirst()).counts().equals(List.of(1, 1)),
                "The exploration did not finish both real journal objectives");
            System.out.println("[MechanicExploration] Native signs/chest + guide-driven missing cell + readonly inspection + player summon/death/key pickup + gate + quest objectives passed");
            helper.succeed();
        } finally {
            guardians(level, at(base, MechanicDiscoveryExample.ALTAR_POSITION)).forEach(CreatureEntity::discard);
            if (player != null) { level.getServer().getPlayerList().remove(player); player.discard(); }
            WorldContentRuntime.unbindLevel(level);
            level.getDataStorage().set(WorldMechanicSavedData.TYPE, previousLedger == null ? new WorldMechanicSavedData() : previousLedger);
        }
    }

    private static MechanicInspection inspect(ServerPlayer player, String scope, String id, BlockPos anchor, int request) {
        return MechanicGuideProtocol.evaluate(player, new MechanicGuideProtocol.Query(scope,
            player.level().dimension().identifier().toString(), id, anchor, request)).result();
    }
    private static QuestPlayerState questState(ServerPlayer player) {
        var output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, player.level().registryAccess());
        player.saveWithoutId(output);
        var saved = output.buildResult().getCompoundOrEmpty(AttachmentTarget.NBT_ATTACHMENT_KEY).get("worldsmith:quest_progress");
        check(saved != null, "Actual player serialization has no persistent quest attachment");
        return QuestPlayerState.CODEC.parse(NbtOps.INSTANCE, saved).getOrThrow();
    }
    private static int findKey(ServerPlayer player) {
        for (int slot = 0; slot < 36; slot++) {
            var identity = player.getInventory().getItem(slot).get(CustomItemRuntime.identityComponent());
            if (identity != null && identity.itemId().equals(MechanicDiscoveryExample.KEY)) return slot;
        }
        return -1;
    }
    private static BlockPos at(BlockPos base, BuildPos position) { return base.offset(position.getX(), position.getY(), position.getZ()); }
    private static void standBeside(ServerPlayer player, BlockPos anchor) { player.snapTo(anchor.getX() + .5, anchor.getY() + .1, anchor.getZ() + 2.5, 180, 0); }
    private static InteractionResult use(ServerLevel level, ServerPlayer player, BlockPos position) {
        return UseBlockCallback.EVENT.invoker().interact(player, level, InteractionHand.MAIN_HAND,
            new BlockHitResult(Vec3.atCenterOf(position), Direction.UP, position, false));
    }
    private static void place(ServerPlayer player, BlockPos target) {
        ItemStack stack = player.getMainHandItem();
        check(stack.getItem() instanceof BlockItem, "Collected material is not a placeable block");
        BlockPos support = target.below();
        var context = new BlockPlaceContext(new UseOnContext(player, InteractionHand.MAIN_HAND,
            new BlockHitResult(Vec3.atCenterOf(support).add(0, .5, 0), Direction.UP, support, false)));
        check(context.getClickedPos().equals(target) && ((BlockItem) stack.getItem()).place(context).consumesAction(),
            "Real survival placement rejected the guide's missing cell");
    }
    private static List<CreatureEntity> guardians(ServerLevel level, BlockPos anchor) {
        return level.getEntitiesOfClass(CreatureEntity.class, new AABB(anchor).inflate(16), e -> e.creatureId().equals(MechanicDiscoveryExample.GUARDIAN));
    }
    private static void check(boolean condition, String message) { if (!condition) throw new IllegalStateException(message); }
}

package com.wjz.worldsmith.gametest;

import com.wjz.worldsmith.content.WorldContentRuntime;
import com.wjz.worldsmith.content.creature.CreatureEntity;
import com.wjz.worldsmith.content.interaction.WorldMechanicRuntime;
import com.wjz.worldsmith.content.interaction.WorldMechanicSavedData;
import com.wjz.worldsmith.content.item.CustomItemRuntime;
import com.wjz.worldsmith.content.item.WorldItemIdentity;
import com.wjz.worldsmith.core.content.*;
import com.wjz.worldsmith.core.model.WorldsmithPack;
import com.wjz.worldsmith.core.pack.WorldContentBundleIO;
import com.wjz.worldsmith.core.pack.WorldsmithPackLoader;
import com.wjz.worldsmith.core.serialization.WorldsmithJson;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.NbtOps;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/** Real server, real Fabric event and BlockItem mixin. No saved user worlds or authoring services. */
public final class WorldMechanicSmokeTests {
    @GameTest(maxTicks = 140, skyAccess = true)
    public void threeMechanismsAreTransactional(GameTestHelper helper) throws Exception {
        ServerLevel level = helper.getLevel();
        WorldsmithPack pack = fixture();
        Map<String, String> biomes = new LinkedHashMap<>();
        pack.getBiomes().getBiomes().forEach(b -> biomes.put(b.getId(), "worldsmith:generated/" + pack.getComputedId() + "/" + b.getId()));
        var prepared = WorldContentRuntime.prepare(pack, biomes);
        WorldContentRuntime.bindLevel(level, prepared);
        check(WorldMechanicRuntime.snapshot(level) != null, "Mechanic server binding was not installed");

        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        player.setGameMode(GameType.SURVIVAL);
        // The 26.2 helper's subclass reports CREATIVE; explicitly use finite native placement materials.
        GameType.SURVIVAL.updatePlayerAbilities(player.getAbilities());
        check(!player.hasInfiniteMaterials() && player.mayBuild(), "Smoke player needs finite survival materials");
        player.setNoGravity(true);
        player.getInventory().setSelectedSlot(0);
        for (int x = 0; x < 8; x++) for (int z = 0; z < 8; z++) helper.setBlock(new BlockPos(x, 1, z), Blocks.STONE);

        BlockPos summon = helper.absolutePos(new BlockPos(2, 2, 2));
        BlockPos gate = helper.absolutePos(new BlockPos(5, 2, 2));
        BlockPos exchange = helper.absolutePos(new BlockPos(2, 2, 5));
        WorldMechanicSavedData ledger = level.getDataStorage().computeIfAbsent(WorldMechanicSavedData.TYPE);

        // Worldgen/admin setBlock is deliberately not a player-place event.
        set(level, summon, Blocks.GOLD_BLOCK);
        set(level, summon.east(), Blocks.IRON_BLOCK);
        check(creatures(level, summon).isEmpty(), "Arbitrary setBlock incorrectly triggered a player-built summon");
        check(ledger.progress(key("summon", summon)) == null, "Worldgen created a mechanic fact");

        // Native placement succeeds and charges its own one block; the rejected mechanic preserves the assembled pattern.
        set(level, summon.east(), Blocks.AIR);
        set(level, summon.above(), Blocks.STONE);
        standBeside(player, summon);
        hand(player, Items.IRON_BLOCK, 2);
        place(level, player, summon.east());
        check(count(player, Items.IRON_BLOCK) == 1, "A rejected mechanic changed the native placement item charge");
        check(level.getBlockState(summon).is(Blocks.GOLD_BLOCK) && level.getBlockState(summon.east()).is(Blocks.IRON_BLOCK), "Blocked summon did not roll back both consumed pattern cells");
        check(creatures(level, summon).isEmpty() && ledger.progress(key("summon", summon)) == null, "Blocked summon committed an entity or state");

        // Complete the non-anchor cell again: the actual BlockItem.place RETURN mixin must run.
        set(level, summon.above(), Blocks.AIR);
        set(level, summon.east(), Blocks.AIR);
        place(level, player, summon.east());
        check(level.getBlockState(summon).isAir() && level.getBlockState(summon.east()).isAir(), "Successful player placement did not consume the summon pattern (mixin missing)");
        check(creatures(level, summon).size() == 1, "Player placement did not create exactly one authored creature");
        check(ledger.progress(key("summon", summon)).activations() == 1, "Summon state was not committed once");

        // Rebuilding a spent anchor never resets the device or duplicates its creature.
        set(level, summon, Blocks.GOLD_BLOCK);
        hand(player, Items.IRON_BLOCK, 1);
        place(level, player, summon.east());
        check(creatures(level, summon).size() == 1, "Rebuilding a spent anchor repeated its summon");
        check(level.getBlockState(summon).is(Blocks.GOLD_BLOCK) && level.getBlockState(summon.east()).is(Blocks.IRON_BLOCK), "Spent summon still consumed rebuilt blocks");
        System.out.println("[MechanicSmoke] Native placement mixin, obstruction rollback and one-shot summon passed");

        set(level, gate, Blocks.STONE_BRICKS);
        set(level, gate.above(), Blocks.COBBLESTONE);
        standBeside(player, gate);
        hand(player, prepared.items().stack("worldsmith:item/smoke_key", 2));
        check(use(level, player, gate, InteractionHand.OFF_HAND) == InteractionResult.PASS, "Off-hand activated a main-hand mechanic");
        var foreignKey = prepared.items().stack("worldsmith:item/smoke_key", 2);
        foreignKey.set(CustomItemRuntime.identityComponent(), new WorldItemIdentity("0".repeat(64), "smoke_key"));
        hand(player, foreignKey);
        check(use(level, player, gate, InteractionHand.MAIN_HAND) == InteractionResult.PASS, "A foreign-world custom key matched the local device");
        check(player.getMainHandItem().getCount() == 2 && level.getBlockState(gate.above()).is(Blocks.COBBLESTONE), "Foreign-world key handling consumed input or changed the gate");
        hand(player, Items.IRON_NUGGET, 1);
        check(use(level, player, gate, InteractionHand.MAIN_HAND) == InteractionResult.PASS, "Wrong key activated a gate");
        check(level.getBlockState(gate.above()).is(Blocks.COBBLESTONE) && count(player, Items.IRON_NUGGET) == 1, "Wrong-key path changed the gate or material");
        hand(player, prepared.items().stack("worldsmith:item/smoke_key", 2));
        check(use(level, player, gate, InteractionHand.MAIN_HAND).consumesAction(), "Correct key did not activate gate");
        check(level.getBlockState(gate.above()).isAir() && customCount(level, player, "smoke_key") == 1, "Gate did not atomically remove its block and charge one key");
        set(level, gate.above(), Blocks.COBBLESTONE);
        check(use(level, player, gate, InteractionHand.MAIN_HAND) == InteractionResult.FAIL, "Spent gate did not suppress native fallthrough");
        check(customCount(level, player, "smoke_key") == 1, "Spent gate consumed a second key");
        System.out.println("[MechanicSmoke] Main-hand key gate, wrong-key and off-hand checks passed");

        var exchangeState = prepared.blockResolver().resolve("worldsmith:content/smoke_exchanger");
        level.setBlock(exchange, exchangeState, Block.UPDATE_ALL);
        standBeside(player, exchange);
        for (int slot = 0; slot < 36; slot++) player.getInventory().setItem(slot, new ItemStack(Items.COBBLESTONE, 64));
        hand(player, Items.COPPER_INGOT, 3); // Charging two leaves this slot occupied, so no reward fits.
        check(use(level, player, exchange, InteractionHand.MAIN_HAND) == InteractionResult.FAIL, "Full inventory did not reject exchange");
        check(count(player, Items.COPPER_INGOT) == 3 && customCount(level, player, "smoke_token") == 0, "Full-inventory rejection charged input or dropped reward");
        check(ledger.progress(key("exchange", exchange)) == null && level.getBlockState(exchange) == exchangeState, "Failed exchange committed state or damaged its device");

        player.getInventory().clearContent();
        hand(player, Items.COPPER_INGOT, 1);
        check(use(level, player, exchange, InteractionHand.MAIN_HAND) == InteractionResult.FAIL, "Insufficient offering did not suppress native fallthrough");
        check(count(player, Items.COPPER_INGOT) == 1, "Insufficient offering was consumed");
        hand(player, Items.COPPER_INGOT, 6);
        check(use(level, player, exchange, InteractionHand.MAIN_HAND).consumesAction(), "An earlier unaffordable bulk recipe blocked the affordable paid exchange");
        check(count(player, Items.COPPER_INGOT) == 4 && customCount(level, player, "smoke_token") == 1, "Exchange did not charge and reward exact quantities");
        check(use(level, player, exchange, InteractionHand.MAIN_HAND) == InteractionResult.FAIL, "Cooldown did not suppress immediate retrigger");
        check(count(player, Items.COPPER_INGOT) == 4 && customCount(level, player, "smoke_token") == 1, "Cooldown retrigger changed inventory");

        // set_block must restore native onPlace scheduling after the transactional stage.
        BlockPos falling = helper.absolutePos(new BlockPos(5, 4, 5));
        set(level, falling, Blocks.LAPIS_BLOCK);
        ItemStack retainedOffering = player.getMainHandItem().copy();
        hand(player, Items.REDSTONE, 1);
        standBeside(player, falling);
        check(use(level, player, falling, InteractionHand.MAIN_HAND).consumesAction(), "Native falling-block conversion failed");
        player.getInventory().setItem(player.getInventory().getSelectedSlot(), retainedOffering);

        helper.runAfterDelay(25, () -> {
            check(!level.getBlockState(falling).is(Blocks.SAND) && level.getBlockState(falling.below(2)).is(Blocks.SAND), "set_block skipped native onPlace scheduling and left floating sand");
            standBeside(player, exchange);
            check(use(level, player, exchange, InteractionHand.MAIN_HAND).consumesAction(), "Exchange did not become eligible after cooldown");
            check(count(player, Items.COPPER_INGOT) == 2 && customCount(level, player, "smoke_token") == 2, "Second paid exchange produced incorrect inventory");
            var before = level.getDataStorage().computeIfAbsent(WorldMechanicSavedData.TYPE);
            var nbt = WorldMechanicSavedData.CODEC.encodeStart(NbtOps.INSTANCE, before).getOrThrow();
            var restored = WorldMechanicSavedData.CODEC.parse(NbtOps.INSTANCE, nbt).getOrThrow();
            check(before.state().equals(restored.state()), "Mechanic NBT round trip changed anchor state, counters or cooldown");
            WorldContentRuntime.unbindLevel(level);
            level.getDataStorage().set(WorldMechanicSavedData.TYPE, restored);
            WorldContentRuntime.bindLevel(level, prepared);
            standBeside(player, gate);
            hand(player, prepared.items().stack("worldsmith:item/smoke_key", 1));
            check(use(level, player, gate, InteractionHand.MAIN_HAND) == InteractionResult.FAIL && customCount(level, player, "smoke_key") == 1, "Restored ledger replayed a spent gate");
            check(restored.progress(key("summon", summon)).activations() == 1 && restored.progress(key("exchange", exchange)).activations() == 2, "Restored activation counters changed");
            creatures(level, summon).forEach(CreatureEntity::discard);
            level.getServer().getPlayerList().remove(player);
            player.discard();
            check(!level.getServer().getPlayerList().getPlayers().contains(player)
                && level.getServer().getPlayerList().getPlayer(player.getUUID()) != player,
                "Smoke fixture retained its mock player in the global player list");
            WorldContentRuntime.unbindLevel(level);
            System.out.println("[MechanicSmoke] Exchange rejection, cooldown, NBT restore and spent-state rebind passed");
            helper.succeed();
        });
    }

    private static InteractionResult use(ServerLevel level, ServerPlayer player, BlockPos pos, InteractionHand hand) {
        return UseBlockCallback.EVENT.invoker().interact(player, level, hand, new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false));
    }

    private static void place(ServerLevel level, ServerPlayer player, BlockPos target) {
        ItemStack stack = player.getMainHandItem();
        check(stack.getItem() instanceof BlockItem, "Placement fixture needs a native BlockItem");
        BlockPos support = target.below();
        var hit = new BlockHitResult(Vec3.atCenterOf(support).add(0, .5, 0), Direction.UP, support, false);
        var context = new BlockPlaceContext(new UseOnContext(player, InteractionHand.MAIN_HAND, hit));
        check(context.getClickedPos().equals(target), "Native placement context targeted a different cell");
        check(((BlockItem)stack.getItem()).place(context).consumesAction(), "Native BlockItem.place failed");
    }

    private static void hand(ServerPlayer player, Item item, int count) { hand(player, new ItemStack(item, count)); }
    private static void hand(ServerPlayer player, ItemStack stack) { player.getInventory().setItem(player.getInventory().getSelectedSlot(), stack); }
    private static int customCount(ServerLevel level, ServerPlayer player, String id) {
        int total = 0;
        var snapshot = CustomItemRuntime.snapshot(level);
        for (int slot = 0; slot < 36; slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            var identity = stack.get(CustomItemRuntime.identityComponent());
            if (identity == null || !id.equals(identity.itemId())) continue;
            var definition = CustomItemRuntime.definition(level, stack);
            check(definition != null && id.equals(definition.getId()) && snapshot.isCanonical(stack), "Reward/key was not a canonical stack from this world's item definition: " + id);
            total += stack.getCount();
        }
        return total;
    }
    private static void standBeside(ServerPlayer player, BlockPos anchor) { player.snapTo(anchor.getX() + .5, anchor.getY() + .1, anchor.getZ() + 2.5, 180, 0); }
    private static void set(ServerLevel level, BlockPos pos, Block block) { level.setBlock(pos, block.defaultBlockState(), Block.UPDATE_ALL); }
    private static String key(String mechanic, BlockPos anchor) { return WorldMechanicSavedData.key(mechanic, anchor.asLong()); }
    private static int count(ServerPlayer player, Item item) {
        int total = 0;
        for (int i = 0; i < 36; i++) if (player.getInventory().getItem(i).is(item)) total += player.getInventory().getItem(i).getCount();
        return total;
    }
    private static List<CreatureEntity> creatures(ServerLevel level, BlockPos anchor) {
        return level.getEntitiesOfClass(CreatureEntity.class, new AABB(anchor).inflate(12), entity -> entity.creatureId().equals("smoke_guardian"));
    }
    private static void check(boolean condition, String message) { if (!condition) throw new IllegalStateException(message); }

    private static WorldsmithPack fixture() throws Exception {
        var base = WorldsmithPackLoader.loadClasspath("worldsmith/packs/ashlands");
        var image = new BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB);
        for (int x = 0; x < 32; x++) for (int y = 0; y < 32; y++) image.setRGB(x, y, 0xff8060d0);
        var output = new ByteArrayOutputStream(); ImageIO.write(image, "png", output);
        byte[] png = output.toByteArray(); String hash = ContentAssetValidation.INSTANCE.hash(png);
        var creatures = WorldsmithJson.INSTANCE.getFormat().decodeFromString(CreatureLibrary.Companion.serializer(), """
            {"schemaVersion":1,"creatures":[{"id":"smoke_guardian","displayName":"Smoke guardian","category":"PASSIVE",
              "model":{"texture":"%s","textureWidth":32,"textureHeight":32,"bones":[{"id":"body","pivot":{"y":24},
                "cubes":[{"origin":{"x":-4,"y":-8,"z":-4},"size":{"x":8,"y":8,"z":8}}]}]},
              "attributes":{"width":0.6,"height":1.0}}]}
            """.formatted(hash));
        var blocks = new CustomBlockLibrary(2,List.of(new CustomBlockDefinition("smoke_exchanger","Exchange device",CustomBlockProfile.STONE,BlockAppearance.uniform(hash),6,"")));
        var items = WorldsmithJson.INSTANCE.getFormat().decodeFromString(CustomItemLibrary.Companion.serializer(), """
            {"schemaVersion":1,"items":[{"id":"smoke_token","displayName":"Exchange token","textureAsset":"%s"},
              {"id":"smoke_key","displayName":"Gate key","textureAsset":"%s"}]}
            """.formatted(hash, hash));
        var mechanics = WorldsmithJson.INSTANCE.getFormat().decodeFromString(WorldMechanicLibrary.Companion.serializer(), """
            {"schemaVersion":1,"mechanics":[
              {"id":"summon","displayName":"Summon fixture","rules":[{"id":"complete","event":"BLOCK_PLACED","pattern":[
                {"offset":{},"block":{"block":"minecraft:gold_block"},"consume":true},
                {"offset":{"x":1},"block":{"block":"minecraft:iron_block"},"consume":true}],
                "actions":[{"kind":"spawn_creature","creature":"smoke_guardian","offset":{"y":1}}]}]},
              {"id":"gate","displayName":"Key gate","rules":[{"id":"open","event":"USE_BLOCK","rotateY":false,
                "pattern":[{"offset":{},"block":{"block":"minecraft:stone_bricks"}},
                  {"offset":{"y":1},"block":{"block":"minecraft:cobblestone"}}],
                "heldItem":{"item":"worldsmith:item/smoke_key","count":1},
                "actions":[{"kind":"set_block","offset":{"y":1},"block":{"block":"minecraft:air"}}]}]},
              {"id":"exchange","displayName":"Paid exchange","rules":[
                {"id":"bulk","event":"USE_BLOCK","rotateY":false,"fromState":"idle","toState":"idle",
                 "pattern":[{"offset":{},"block":{"block":"worldsmith:content/smoke_exchanger"}}],
                 "heldItem":{"item":"minecraft:copper_ingot","count":64},
                 "actions":[{"kind":"give_item","item":"worldsmith:item/smoke_token","count":32}]},
                {"id":"trade","event":"USE_BLOCK","rotateY":false,
                "fromState":"idle","toState":"idle","cooldownTicks":20,
                "pattern":[{"offset":{},"block":{"block":"worldsmith:content/smoke_exchanger"}}],
                "heldItem":{"item":"minecraft:copper_ingot","count":2},
                "actions":[{"kind":"give_item","item":"worldsmith:item/smoke_token","count":1}]}]},
              {"id":"native_placement","displayName":"Native lifecycle","rules":[{"id":"fall","event":"USE_BLOCK","rotateY":false,
                "pattern":[{"offset":{},"block":{"block":"minecraft:lapis_block"}}],
                "heldItem":{"item":"minecraft:redstone","count":1},
                "actions":[{"kind":"set_block","offset":{},"block":{"block":"minecraft:sand"}}]}]}
            ]}
            """);
        return WorldContentBundleIO.create("Mechanic native smoke", "Isolated generated native interaction fixture", base.getTerrain(), base.getBiomes(), base.getFeatures(),
            base.getStructures(), base.getTheme(), blocks, creatures, Map.of(hash, png), items, new QuestLibrary(), null, mechanics);
    }
}

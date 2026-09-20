package com.wjz.worldsmith.gametest;

import com.mojang.authlib.GameProfile;
import com.wjz.worldsmith.ability.AbilityEventRuntime;
import com.wjz.worldsmith.ability.AbilityActorControl;
import com.wjz.worldsmith.ability.WorldAbilityRuntime;
import com.wjz.worldsmith.content.WorldContentRuntime;
import com.wjz.worldsmith.content.creature.CreatureEntity;
import com.wjz.worldsmith.content.creature.CreatureRuntime;
import com.wjz.worldsmith.content.interaction.WorldMechanicSavedData;
import com.wjz.worldsmith.content.item.CustomItemRuntime;
import com.wjz.worldsmith.core.ability.*;
import com.wjz.worldsmith.core.content.*;
import com.wjz.worldsmith.core.examples.AbilityRuntimeExample;
import com.wjz.worldsmith.core.model.WorldsmithPack;
import com.wjz.worldsmith.core.pack.WorldContentBundleIO;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.*;
import java.util.function.BooleanSupplier;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ServerboundPlayerLoadedPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;

/** Separate environment batch: real native use state, real equipment changes and passive entity callbacks. */
public final class AbilityEventBindingGameTests {
    @GameTest(environment = "worldsmith_mechanics_smoke:ability_bindings", maxTicks = 1600, skyAccess = true, padding = 30)
    public void nativeInputsRespectBindingAndInvocationOwnership(GameTestHelper helper) {
        var run = new Run(helper);
        try { run.begin(); } catch (Throwable failure) { run.close(); throw failure; }
        helper.onEachTick(() -> {
            try { run.tick(); } catch (Throwable failure) { run.close(); throw failure; }
        });
    }

    private record Trial(String name, Runnable start, BooleanSupplier poll) {}

    private static final class Run {
        final GameTestHelper helper; final ServerLevel level; final WorldsmithPack pack = fixture();
        final List<Trial> trials = new ArrayList<>();
        final Map<ChunkPos, Boolean> forcedBefore = new LinkedHashMap<>();
        ServerPlayer player; CreatureEntity npc; WorldMechanicSavedData priorLedger; BlockPos base;
        int index = -1, phase; long since, marked, ticketWaitAt; double previous; UUID first, replacement; String nonce;
        boolean bound, closed;
        Run(GameTestHelper helper) { this.helper = helper; level = helper.getLevel(); }

        void begin() {
            check(WorldContentRuntime.boundLevelCount() == 0, "Binding test overlapped another world-content owner");
            var biomes = new LinkedHashMap<String, String>();
            pack.getBiomes().getBiomes().forEach(b -> biomes.put(b.getId(), "worldsmith:generated/" + pack.getComputedId() + "/" + b.getId()));
            var prepared = WorldContentRuntime.prepare(pack, biomes);
            priorLedger = level.getDataStorage().get(WorldMechanicSavedData.TYPE);
            level.getDataStorage().set(WorldMechanicSavedData.TYPE, new WorldMechanicSavedData());
            WorldContentRuntime.bindLevel(level, prepared); bound = true;
            base = helper.absolutePos(new BlockPos(8, 3, 8));
            ticketWaitAt = level.getGameTime();
            for (int x = (base.getX() - 12) >> 4; x <= (base.getX() + 12) >> 4; x++)
                for (int z = (base.getZ() - 12) >> 4; z <= (base.getZ() + 12) >> 4; z++) {
                    var chunk = new ChunkPos(x, z);
                    boolean prior = level.getChunkSource().getForceLoadedChunks().contains(chunk.pack());
                    forcedBefore.put(chunk, prior); if (!prior) level.setChunkForced(x, z, true);
                }
            for (int x = -12; x <= 12; x++) for (int z = -12; z <= 12; z++) {
                level.setBlock(base.offset(x, -1, z), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
                for (int y = 0; y <= 5; y++) level.setBlock(base.offset(x, y, z), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            }
            var cookie = CommonListenerCookie.createInitial(new GameProfile(UUID.randomUUID(), "bindings-player"), false);
            player = new ServerPlayer(level.getServer(), level, cookie.gameProfile(), cookie.clientInformation());
            var connection = new Connection(PacketFlow.SERVERBOUND); new EmbeddedChannel(connection);
            level.getServer().getPlayerList().placeNewPlayer(connection, player, cookie);
            player.connection.handleAcceptPlayerLoad(new ServerboundPlayerLoadedPacket());
            player.setGameMode(GameType.SURVIVAL); player.setNoGravity(true); stand(0, 0);
            check(!player.isCreative() && player.connection.hasClientLoaded(), "Input tests need a real survival player connection");
            define();
        }

        void define() {
            trials.add(new Trial("native held-use start tick release and event order", () -> {
                equip("held_a"); previous = number(player, "held_events", "starts");
            }, () -> {
                if (phase == 0) {
                    if (!ready("held_events")) return false;
                    use(); nonce = AbilityEventRuntime.useNonce(player); first = WorldAbilityRuntime.invocation(player, "held_events");
                    check(nonce != null && first != null && player.isUsingItem(), "Native press did not create one held session");
                    check(number(player, "held_events", "starts") == previous, "Input callback ran source before server END");
                    phase = 1;
                }
                if (phase == 1 && number(player, "held_events", "ticks") >= 3) {
                    check(number(player, "held_events", "starts") == previous + 1, "Hold ticks restarted the program instead of observing it");
                    check(nonce.equals(AbilityEventRuntime.useNonce(player)), "Hold changed the accepted use nonce");
                    check(order(player, "held_events").subList(0, 2).equals(List.of("start", "use_start")), "Start/event queue order changed");
                    player.releaseUsingItem();
                    check(!player.isUsingItem() && AbilityEventRuntime.useNonce(player) == null, "Native release retained use input state");
                    phase = 2;
                }
                if (phase != 2 || !bool(player, "held_events", "released")) return false;
                check(!bool(player, "held_events", "cancelled"), "Normal release also generated a cancellation");
                check(player.getMainHandItem().getCount() == 1 && player.getMainHandItem().getDamageValue() == 0,
                    "Event bindings introduced a hidden quantity or durability fee");
                return !WorldAbilityRuntime.isActive(player, "held_events");
            }));
            trials.add(new Trial("shared native item host does not transfer a held session to another logical item", () -> equip("held_a"), () -> {
                if (phase == 0) {
                    if (!ready("held_events")) return false;
                    use(); first = WorldAbilityRuntime.invocation(player, "held_events"); phase = 1;
                }
                if (phase == 1 && bool(player, "held_events", "pressed")) {
                    ItemStack old = player.getMainHandItem(); equip("held_b");
                    check(old.getItem() == player.getMainHandItem().getItem(), "Fixture must use the exact shared native item host");
                    marked = level.getGameTime(); phase = 2;
                }
                if (phase != 2 || level.getGameTime() - marked < 4) return false;
                check(AbilityEventRuntime.useNonce(player) == null && !player.isUsingItem(), "Logical replacement inherited the original held state");
                check(bool(player, "held_events", "cancelled"), "Source replacement did not notify the old session's cancellation handler");
                check(!WorldAbilityRuntime.invocationActive(level, first), "Source replacement leaked the old invocation");
                check(!bool(player, "held_events", "released"), "Logical replacement was treated as an authorized release");
                return true;
            }));
            trials.add(new Trial("an old native release never signals or cancels a replacement invocation UUID", () -> equip("held_a"), () -> {
                if (phase == 0) {
                    if (!ready("held_events")) return false;
                    use(); first = WorldAbilityRuntime.invocation(player, "held_events"); phase = 1;
                }
                if (phase == 1 && bool(player, "held_events", "pressed")) {
                    check(WorldAbilityRuntime.cancelInvocation(level, first), "Could not retire the original invocation"); phase = 2;
                }
                if (phase == 2) {
                    if (!ready("held_events")) return false;
                    try (var prepared = WorldAbilityRuntime.prepareStart(level, player, "held_events", player.position(), null, 1, false)) {
                        check(prepared != null, "Replacement reservation was rejected"); replacement = prepared.commit();
                    }
                    check(!replacement.equals(first), "Replacement retained a stale invocation UUID");
                    WorldAbilityRuntime.holdInvocation(level, replacement, 8);
                    player.releaseUsingItem();
                    check(WorldAbilityRuntime.invocationActive(level, replacement), "Old release cancelled a replacement synchronously");
                    marked = level.getGameTime(); phase = 3;
                }
                if (phase != 3 || level.getGameTime() - marked < 3) return false;
                check(WorldAbilityRuntime.invocationActive(level, replacement), "Old use-session cleanup cancelled a newer invocation");
                check(!bool(player, "held_events", "released") && !bool(player, "held_events", "cancelled"), "Old nonce was delivered to a replacement's handlers");
                WorldAbilityRuntime.cancelInvocation(level, replacement); return true;
            }));
            trials.add(new Trial("rejected use start writes no nonce and consumes no inventory", () -> equip("held_a"), () -> {
                if (phase == 0) {
                    if (!ready("held_events")) return false;
                    check(WorldAbilityRuntime.start(level, player, "held_events", player.position(), null, 1, false), "Foreign owner setup failed");
                    replacement = WorldAbilityRuntime.invocation(player, "held_events"); WorldAbilityRuntime.holdInvocation(level, replacement, 20); phase = 1;
                }
                ItemStack before = player.getMainHandItem().copy(); int active = WorldAbilityRuntime.activeCount(level);
                check(!player.getMainHandItem().use(level, player, InteractionHand.MAIN_HAND).consumesAction(), "Binding claimed another owner's program");
                check(AbilityEventRuntime.useNonce(player) == null && !player.isUsingItem(), "Rejected start installed a physical session");
                check(ItemStack.matches(before, player.getMainHandItem()) && active == WorldAbilityRuntime.activeCount(level), "Rejected start consumed inventory or allocated another invocation");
                WorldAbilityRuntime.cancelInvocation(level, replacement); return true;
            }));
            trials.add(new Trial("while-equipped lifetime renews but durability mutation is not re-equipping", () -> {
                equip("equipped_focus"); previous = number(player, "equip_events", "starts");
            }, () -> {
                if (phase == 0 && number(player, "equip_events", "starts") >= previous + 2) {
                    previous = number(player, "equip_events", "starts"); player.getMainHandItem().setDamageValue(3);
                    marked = level.getGameTime(); phase = 1;
                }
                if (phase == 1 && level.getGameTime() - marked >= 2) {
                    check(number(player, "equip_events", "starts") == previous, "Ordinary wear retriggered equip source");
                    player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY); marked = level.getGameTime(); phase = 2;
                }
                if (phase != 2 || level.getGameTime() - marked < 5) return false;
                check(bool(player, "equip_events", "unequipped"), "Real slot removal did not reach unequip handler");
                check(!WorldAbilityRuntime.isActive(player, "equip_events"), "Unequipped observer retained an invocation"); return true;
            }));
            trials.add(new Trial("confirmed weapon melee starts a binding through native postHurtEnemy", () -> {
                spawnNpc(); equip("bound_blade"); stand(4, 0); previous = number(player, "melee_events", "hits");
            }, () -> {
                if (phase == 0 && bool(npc, "npc_events", "spawned")) {
                    npc.invulnerableTime = 0; player.attack(npc); phase = 1;
                }
                if (number(player, "melee_events", "hits") <= previous) return false;
                check(npc.getHealth() < npc.getMaxHealth(), "Melee event was only attack intent, without a native hit");
                check(player.getMainHandItem().getDamageValue() > 0, "Native weapon post-hit processing did not run");
                check(bool(player, "melee_events", "living_target"), "Post-hit binding lost its actual entity handle"); return true;
            }));
            trials.add(new Trial("passive NPC spawn proximity interaction and exactly one post-hurt observation", () -> {
                spawnNpc(); stand(0, 0);
            }, () -> {
                if (phase == 0 && bool(npc, "npc_events", "spawned")) { stand(4, 0); phase = 1; }
                if (phase == 1 && number(npc, "npc_events", "entered") > 0) {
                    player.setGameMode(GameType.SPECTATOR);
                    check(!UseEntityCallback.EVENT.invoker().interact(player, level, InteractionHand.MAIN_HAND, npc, new EntityHitResult(npc)).consumesAction(),
                        "Spectator interaction activated a program");
                    player.setGameMode(GameType.SURVIVAL);
                    check(UseEntityCallback.EVENT.invoker().interact(player, level, InteractionHand.MAIN_HAND, npc, new EntityHitResult(npc)).consumesAction(),
                        "Passive NPC rejected its declared interaction hook");
                    phase = 2;
                }
                if (phase == 2 && bool(npc, "npc_dialogue", "player_target")) {
                    check(number(npc, "npc_events", "interactions") == 1, "One native interaction was duplicated or lost");
                    previous = number(npc, "npc_events", "hurts"); npc.invulnerableTime = 0;
                    check(npc.hurtServer(level, player.damageSources().playerAttack(player), 1F), "Real post-hurt input was rejected");
                    phase = 3;
                }
                if (phase == 3 && number(npc, "npc_events", "hurts") > previous) {
                    check(number(npc, "npc_events", "hurts") == previous + 1, "Root hurt fanout and binding hook delivered the same hit twice");
                    stand(0, 0); phase = 4;
                }
                if (phase != 4 || number(npc, "npc_events", "exited") < 1) return false;
                check(npc.definition().getCategory() == CreatureCategory.PASSIVE && npc.definition().getAbility() == null,
                    "NPC events accidentally depended on a hostile combat policy");
                check(number(npc, "npc_events", "ticks") > 0 && npc.getTarget() == null, "Passive automatic binding did not run independently");
                check(AbilityActorControl.hasControl(npc), "Stationed event fixture did not explicitly claim its idle movement");
                return true;
            }));
        }

        void tick() {
            if (closed) return;
            check(helper.getTick() < 1500, "Event binding suite exhausted its deadline");
            player.getFoodData().setFoodLevel(10); player.connection.tick();
            if (index < 0) {
                if (!forcedBefore.keySet().stream().allMatch(chunk -> level.isPositionEntityTicking(chunk.getMiddleBlockPosition(base.getY())))) {
                    check(level.getGameTime() - ticketWaitAt < 200, "Event fixture arena never became entity-ticking: " + forcedBefore.keySet());
                    return;
                }
                next();
            }
            check(level.getGameTime() - since < 180, "Timed out: " + trials.get(index).name() + "; held=" + WorldAbilityRuntime.state(player, "held_events")
                + "; equip=" + WorldAbilityRuntime.state(player, "equip_events") + "; useNonce=" + AbilityEventRuntime.useNonce(player)
                + "; phase=" + phase + "; melee=" + WorldAbilityRuntime.state(player, "melee_events")
                + "; npc=" + (npc == null ? "absent" : "ticks=" + npc.tickCount + ",ticking=" + level.isPositionEntityTicking(npc.blockPosition())
                    + ",hp=" + npc.getHealth() + ",state=" + WorldAbilityRuntime.state(npc, "npc_events")
                    + ",failure=" + WorldAbilityRuntime.lastFailure(npc, "npc_events")));
            if (trials.get(index).poll().getAsBoolean()) {
                System.out.println("[AbilityBindings] PASS " + trials.get(index).name());
                if (index + 1 == trials.size()) { close(); helper.succeed(); } else next();
            }
        }
        void next() {
            if (player.isUsingItem()) player.stopUsingItem();
            WorldAbilityRuntime.cancelOwner(player); player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            if (npc != null) { npc.discard(); npc = null; }
            player.setGameMode(GameType.SURVIVAL); player.setHealth(20); player.removeAllEffects();
            player.getInventory().clearContent(); player.getInventory().setSelectedSlot(0); stand(0, 0);
            phase = 0; marked = -1; first = null; replacement = null; nonce = null; since = level.getGameTime(); index++;
            trials.get(index).start().run();
        }
        void equip(String id) { player.setItemInHand(InteractionHand.MAIN_HAND, CustomItemRuntime.snapshot(level).stack("worldsmith:item/" + id, 1)); }
        boolean ready(String program) { return WorldAbilityRuntime.canStart(level, player, program, player.position()); }
        void use() { check(player.getMainHandItem().use(level, player, InteractionHand.MAIN_HAND).consumesAction(), "Actual item use was rejected"); }
        void stand(double x, double z) {
            player.snapTo(base.getX() + .5 + x, base.getY(), base.getZ() + .5 + z, 0, 0); player.setDeltaMovement(Vec3.ZERO);
            // This embedded connection has no movement packets; perform their normal chunk-tracker update.
            level.getChunkSource().move(player);
        }
        void spawnNpc() {
            npc = CreatureRuntime.passiveType().create(level, EntitySpawnReason.TRIGGERED);
            check(npc != null, "Passive host construction failed");
            npc.snapTo(base.getX() + 6.5, base.getY(), base.getZ() + .5, 0, 0); npc.initialize(pack.getComputedId(), "event_guide", 42);
            npc.setNoGravity(true); check(level.addFreshEntity(npc), "Passive NPC insertion failed");
        }
        void close() {
            if (closed) return; closed = true;
            try {
                if (npc != null) npc.discard();
                if (player != null) { WorldAbilityRuntime.cancelOwner(player); level.getServer().getPlayerList().remove(player); }
                if (bound) {
                    WorldContentRuntime.unbindLevel(level);
                    level.getDataStorage().set(WorldMechanicSavedData.TYPE, priorLedger == null ? new WorldMechanicSavedData() : priorLedger);
                }
            } finally {
                forcedBefore.forEach((chunk, prior) -> level.setChunkForced(chunk.x(), chunk.z(), prior));
                forcedBefore.clear();
            }
        }
    }

    private static WorldsmithPack fixture() {
        var base = AbilityRuntimeExample.create(); var programs = new ArrayList<>(base.getAbilities().getPrograms());
        programs.add(new AbilityProgramDefinition("held_events", "Held events", """
            on start {
                if (state.starts == null) { state.starts = 0; }
                state.starts = state.starts + 1; state.pressed = false; state.released = false; state.cancelled = false;
                state.ticks = 0; state.order = ["start"];
            }
            on use_start { state.pressed = true; state.order = list.append(state.order, event_name); }
            on use_tick { state.ticks = state.ticks + 1; }
            on use_release { state.released = true; }
            on use_cancel { state.cancelled = true; }
            """, 120, 2048));
        programs.add(new AbilityProgramDefinition("equip_events", "Equipment events", """
            on start { if (state.starts == null) { state.starts = 0; } state.starts = state.starts + 1; state.unequipped = false; }
            on equip { state.equipped = true; }
            on unequip { state.unequipped = true; }
            """, 12, 512));
        programs.add(new AbilityProgramDefinition("melee_events", "Confirmed melee", """
            on start { if (state.hits == null) { state.hits = 0; } }
            on melee_hit { state.hits = state.hits + 1; state.living_target = entity.alive(event_entity); }
            """, 80, 512));
        programs.add(new AbilityProgramDefinition("npc_events", "Passive stationed event host", """
            // This fixture intentionally stays in one spot; pure observers need no token.
            on start { state.spawned = false; state.ticks = 0; state.entered = 0; state.exited = 0; state.interactions = 0; state.hurts = 0; state.control = control.claim(20, 500); motion.stop(self); }
            on spawn { state.spawned = true; }
            on tick { state.ticks = state.ticks + 1; }
            on enter { state.entered = state.entered + 1; }
            on exit { state.exited = state.exited + 1; }
            on interact_entity { state.interactions = state.interactions + 1; }
            on hurt { state.hurts = state.hurts + 1; }
            """, 500, 32768));
        programs.add(new AbilityProgramDefinition("npc_dialogue", "Passive interaction", """
            on start { state.player_target = entity.kind(target) == "player"; }
            """, 80, 512));
        var held = new AbilityEventBinding("held", "held_events", List.of("use_start"), List.of("use_tick", "use_release", "use_cancel"), List.of(), 5, 8);
        var equipped = new AbilityEventBinding("equipped", "equip_events", List.of("equip"), List.of("unequip"), List.of(), 5, 8);
        var melee = new AbilityEventBinding("strike", "melee_events", List.of("melee_hit"), List.of(), List.of(), 5, 8);
        String icon = base.getItems().getItems().getFirst().getTextureAsset();
        var items = new ArrayList<>(base.getItems().getItems());
        items.add(item("held_a", icon, null, held, 40)); items.add(item("held_b", icon, null, held, 40));
        items.add(item("equipped_focus", icon, new ItemEquipment(ItemEquipmentType.MELEE), equipped, 0));
        items.add(item("bound_blade", icon, new ItemEquipment(ItemEquipmentType.MELEE), melee, 0));
        var original = base.getCreatures().getCreatures().getFirst(); var creatures = new ArrayList<>(base.getCreatures().getCreatures());
        creatures.add(new CreatureDefinition("event_guide", "Event guide", CreatureCategory.PASSIVE, original.getModel(), new CreatureAttributes(),
            new CreatureBehavior(), new CreatureSpawn(), "A passive observer", List.of(), null, original.getSounds(), null, List.of(
                new AbilityEventBinding("observe", "npc_events", List.of("spawn"), List.of("tick", "enter", "exit", "interact_entity", "hurt"), List.of(), 5, 4),
                new AbilityEventBinding("talk", "npc_dialogue", List.of("interact_entity"), List.of(), List.of(), 5, 4))));
        return WorldContentBundleIO.create("Native event bindings", "Real input and invocation ownership", base.getTerrain(), base.getBiomes(), base.getFeatures(),
            base.getStructures(), base.getTheme(), base.getBlocks(), new CreatureLibrary(5, creatures), base.getAssets(), new CustomItemLibrary(4, items),
            base.getQuests(), base.getManifest().getRepresentativeContent(), base.getMechanics(), new AbilityLibrary(1, programs));
    }
    private static CustomItemDefinition item(String id, String texture, ItemEquipment equipment, AbilityEventBinding binding, int ticks) {
        return new CustomItemDefinition(id, id, texture, CustomItemKind.RELIC, 1, CustomItemRarity.COMMON, "", "", equipment, null, List.of(), List.of(binding), ticks);
    }
    private static double number(net.minecraft.world.entity.LivingEntity actor, String program, String key) {
        var value = WorldAbilityRuntime.state(actor, program).get(key); return value instanceof AbilityValue.NumberValue n ? n.getValue() : 0;
    }
    private static boolean bool(net.minecraft.world.entity.LivingEntity actor, String program, String key) {
        var value = WorldAbilityRuntime.state(actor, program).get(key); return value instanceof AbilityValue.BoolValue b && b.getValue();
    }
    private static List<String> order(net.minecraft.world.entity.LivingEntity actor, String program) {
        var value = WorldAbilityRuntime.state(actor, program).get("order");
        return value instanceof AbilityValue.ListValue list ? list.getValues().stream().map(entry -> ((AbilityValue.TextValue)entry).getValue()).toList() : List.of();
    }
    private static void check(boolean value, String message) { if (!value) throw new IllegalStateException(message); }
}

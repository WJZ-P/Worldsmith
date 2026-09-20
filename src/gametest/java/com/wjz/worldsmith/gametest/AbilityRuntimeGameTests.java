package com.wjz.worldsmith.gametest;

import com.mojang.authlib.GameProfile;
import com.wjz.worldsmith.ability.AbilityProjectile;
import com.wjz.worldsmith.ability.AbilityActorControl;
import com.wjz.worldsmith.ability.AbilityProjectileLeaseChecks;
import com.wjz.worldsmith.ability.WorldAbilityRuntime;
import com.wjz.worldsmith.content.WorldContentRuntime;
import com.wjz.worldsmith.content.creature.CreatureEntity;
import com.wjz.worldsmith.content.creature.CreatureRuntime;
import com.wjz.worldsmith.content.creature.HostileCreatureEntity;
import com.wjz.worldsmith.content.interaction.WorldMechanicSavedData;
import com.wjz.worldsmith.content.interaction.WorldMechanicRuntime;
import com.wjz.worldsmith.content.interaction.MechanicInspection;
import com.wjz.worldsmith.content.item.CustomItemRuntime;
import com.wjz.worldsmith.core.ability.AbilityLibrary;
import com.wjz.worldsmith.core.ability.AbilityProgramDefinition;
import com.wjz.worldsmith.core.ability.AbilityValue;
import com.wjz.worldsmith.core.content.CreatureCombatState;
import com.wjz.worldsmith.core.examples.AbilityRuntimeExample;
import com.wjz.worldsmith.core.examples.MechanicDiscoveryExample;
import com.wjz.worldsmith.core.model.WorldsmithPack;
import com.wjz.worldsmith.core.pack.WorldContentBundleIO;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ServerboundPlayerLoadedPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/** Real server ticks and three production invocation paths; no direct call to an ability damage provider. */
public final class AbilityRuntimeGameTests {
    @GameTest(environment = "worldsmith_mechanics_smoke:abilities", maxTicks = 2800, skyAccess = true, padding = 30)
    public void sourceProgramsUseRealHostsEventsAndLifecycle(GameTestHelper helper) {
        var test = new TrialRun(helper);
        try { test.begin(); }
        catch (Throwable failure) { test.close(); throw failure; }
        helper.onEachTick(() -> {
            try { test.tick(); }
            catch (Throwable failure) { test.close(); throw failure; }
        });
    }

    private record Trial(String name, Runnable start, BooleanSupplier poll) {}

    private static final class TrialRun {
        final GameTestHelper helper;
        final ServerLevel level;
        final WorldsmithPack pack;
        final List<Trial> trials = new ArrayList<>();
        final Map<ChunkPos, Boolean> arenaForcedBefore = new LinkedHashMap<>();
        WorldMechanicSavedData previousLedger;
        ServerPlayer player, otherPlayer;
        Boolean previousPvp;
        CreatureEntity boss;
        BlockPos base;
        int current = -1;
        long trialAt, castAt = -1;
        long ticketWaitAt;
        float lastHealth;
        int hits;
        double priorStarts;
        boolean launched, closed, bound;
        WorldAbilityRuntime.PreparedCast uncommittedCast;
        Vec3 motionStart;
        net.minecraft.world.level.pathfinder.Path ownedPath;
        int deadlineCandidate, deadlinePhysicsTicks;
        long deadlineCandidateAt;
        Vec3 deadlineOrigin;
        AbilityProjectile deadlineProjectile;
        boolean lateProbeSent;

        TrialRun(GameTestHelper helper) { this.helper = helper; level = helper.getLevel(); pack = fixture(); }

        void begin() {
            check(WorldContentRuntime.boundLevelCount() == 0, "Ability batch overlapped an existing content binding");
            var biomes = new LinkedHashMap<String, String>();
            pack.getBiomes().getBiomes().forEach(b -> biomes.put(b.getId(), "worldsmith:generated/" + pack.getComputedId() + "/" + b.getId()));
            var prepared = WorldContentRuntime.prepare(pack, biomes);
            previousLedger = level.getDataStorage().get(WorldMechanicSavedData.TYPE);
            level.getDataStorage().set(WorldMechanicSavedData.TYPE, new WorldMechanicSavedData());
            WorldContentRuntime.bindLevel(level, prepared); bound = true;
            base = helper.absolutePos(new BlockPos(8, 3, 8));
            requestArenaTickets();
            for (int x = -12; x <= 12; x++) for (int z = -12; z <= 12; z++) {
                level.setBlock(base.offset(x, -1, z), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
                for (int y = 0; y <= 5; y++) level.setBlock(base.offset(x, y, z), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            }
            // The vanilla helper overrides gameMode() to CREATIVE forever. A normal ServerPlayer
            // with an embedded connection is essential to exercise damage and target selection.
            var cookie = CommonListenerCookie.createInitial(new GameProfile(UUID.randomUUID(), "ability-survival"), false);
            player = new ServerPlayer(level.getServer(), level, cookie.gameProfile(), cookie.clientInformation());
            var connection = new Connection(PacketFlow.SERVERBOUND);
            new EmbeddedChannel(connection);
            level.getServer().getPlayerList().placeNewPlayer(connection, player, cookie);
            player.connection.handleAcceptPlayerLoad(new ServerboundPlayerLoadedPacket());
            player.setGameMode(GameType.SURVIVAL); player.setNoGravity(true);
            check(!player.isCreative() && !player.isSpectator() && !player.getAbilities().invulnerable, "AI fixture must be a real survival target");
            check(player.connection.hasClientLoaded() && !player.isInvulnerableTo(level, player.damageSources().generic()),
                "Client-load grace period would hide erroneous early damage in the dodge test");
            stand(0, 0); // Establish this owned player's normal simulation tickets before trials begin.
            defineTrials();
        }

        void defineTrials() {
            trials.add(new Trial("creature / captured positions remain dodgeable", this::spawnBoss, () -> {
                long elapsed = afterCast(); if (elapsed < 0) return false;
                check(player.getHealth() == 20F, "Moving out of captured marks caused an early or tracking hit");
                if (elapsed < 14) stand(-6, 0); else if (elapsed < 27) stand(6, 0); else stand(0, -6);
                return elapsed >= 85 && bool(boss, AbilityRuntimeExample.ECHO, "completed");
            }));
            trials.add(new Trial("creature / each of three programmed pulses hits only once", () -> {
                spawnBoss(); hits = 0; lastHealth = 20F;
            }, () -> {
                stand(0, 4); player.invulnerableTime = 0;
                long elapsed = afterCast(); if (elapsed < 0) return false;
                float health = player.getHealth();
                if (health < lastHealth) { hits++; lastHealth = health; }
                if (elapsed < 30) check(health == 20F, "Damage preceded the source's three recorded warnings");
                if (elapsed < 85) return false;
                check(hits == 3 && health == 11F, "Expected three source pulses, not repeated per-tick damage: hits=" + hits + " hp=" + health);
                return true;
            }));
            trials.add(new Trial("creature / source-visible query makes walls block effects", this::spawnBoss, () -> {
                long elapsed = afterCast(); if (elapsed < 0) return false;
                wall(true); stand(0, 4); player.invulnerableTime = 0;
                check(player.getHealth() == 20F, "A program guarded by world.visible hit through a solid wall");
                return elapsed >= 90;
            }));
            trials.add(new Trial("creature / NoAI cancels pending source and resources", this::spawnBoss, () -> {
                long elapsed = afterCast(); if (elapsed < 0) return false;
                boss.setNoAi(true);
                check(player.getHealth() == 20F, "NoAI left a delayed hit alive");
                if (elapsed < 85) return false;
                check(!WorldAbilityRuntime.isActive(boss, AbilityRuntimeExample.ECHO), "NoAI retained an invocation"); return true;
            }));
            trials.add(new Trial("creature / target becoming creative cancels without late hit", this::spawnBoss, () -> {
                long elapsed = afterCast(); if (elapsed < 0) return false;
                player.setGameMode(GameType.CREATIVE);
                if (elapsed < 85) return false;
                check(!WorldAbilityRuntime.isActive(boss, AbilityRuntimeExample.ECHO), "Creative target retained an invocation");
                check(player.getHealth() == 20F, "Target cancellation applied delayed damage"); return true;
            }));
            trials.add(new Trial("creature / owner death ends waiting handlers", this::spawnBoss, () -> {
                long elapsed = afterCast(); if (elapsed < 0) return false;
                if (boss.isAlive()) boss.hurtServer(level, player.damageSources().playerAttack(player), 1000F);
                check(player.getHealth() == 20F, "Dead owner produced a later pulse");
                return elapsed >= 85 && !WorldAbilityRuntime.isActive(boss, AbilityRuntimeExample.ECHO);
            }));
            trials.add(new Trial("creature / save reload preserves state but not a pending program counter", this::spawnBoss, () -> {
                long elapsed = afterCast(); if (elapsed < 0) return false;
                if (!launched) {
                    var output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, level.registryAccess());
                    boss.saveWithoutId(output);
                    var tag = output.buildResult(); tag.remove("UUID");
                    priorStarts = number(boss, AbilityRuntimeExample.ECHO, "starts");
                    boss.discard();
                    boss = new HostileCreatureEntity(CreatureRuntime.hostileType(), level);
                    boss.load(TagValueInput.create(ProblemReporter.DISCARDING, level.registryAccess(), tag));
                    check(level.addFreshEntity(boss), "Reload fixture was rejected");
                    boss.setTarget(player); launched = true;
                }
                check(player.getHealth() == 20F, "A saved waiting PC resumed damage on reload");
                check(number(boss, AbilityRuntimeExample.ECHO, "starts") == priorStarts, "Reload lost persistent state or bypassed recovery debt");
                return elapsed >= 20;
            }));
            trials.add(new Trial("mechanic / real USE commits the shared echo program", () -> {
                stand(0, -2); level.setBlock(base, Blocks.DIAMOND_BLOCK.defaultBlockState(), Block.UPDATE_ALL);
                priorStarts = number(player, AbilityRuntimeExample.ECHO, "starts");
            }, () -> {
                if (!launched && WorldAbilityRuntime.canStart(level, player, AbilityRuntimeExample.ECHO, Vec3.atCenterOf(base))) {
                    // A previous death trial can leave native drops which are picked up after next() cleared
                    // inventory. This trial deliberately supplies the pedestal's documented empty-hand input.
                    if (!player.getMainHandItem().isEmpty()) {
                        System.out.println("[AbilityRuntime] Clearing incidental pickup before empty-hand pedestal: " + player.getMainHandItem());
                        player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
                    }
                    var ledger = level.getDataStorage().get(WorldMechanicSavedData.TYPE);
                    check(ledger != null, "Mechanic binding has no persistent ledger");
                    long revision = ledger.state().revision();
                    int active = WorldAbilityRuntime.activeCount(level);
                    var inspection = WorldMechanicRuntime.inspect(player, AbilityRuntimeExample.PEDESTAL, base);
                    check(inspection.code() == MechanicInspection.Code.READY,
                        "The bound program pedestal was not inspectable: " + inspection + "; position=" + player.position() + "; base=" + base + "; block=" + level.getBlockState(base));
                    check(ledger.state().revision() == revision && WorldAbilityRuntime.activeCount(level) == active
                        && number(player, AbilityRuntimeExample.ECHO, "starts") == priorStarts, "Read-only inspection allocated or ran a program");
                    level.setBlock(base, Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
                    check(!UseBlockCallback.EVENT.invoker().interact(player, level, InteractionHand.MAIN_HAND,
                        new BlockHitResult(Vec3.atCenterOf(base), Direction.UP, base, false)).consumesAction(), "Wrong anchor launched a program");
                    check(ledger.state().revision() == revision && WorldAbilityRuntime.activeCount(level) == active,
                        "Rejected anchor wrote the ledger or leaked a reservation");
                    level.setBlock(base, Blocks.DIAMOND_BLOCK.defaultBlockState(), Block.UPDATE_ALL);
                    check(UseBlockCallback.EVENT.invoker().interact(player, level, InteractionHand.MAIN_HAND,
                        new BlockHitResult(Vec3.atCenterOf(base), Direction.UP, base, false)).consumesAction(), "Mechanic did not accept the canonical pedestal");
                    var progress = ledger.progress(WorldMechanicSavedData.key(AbilityRuntimeExample.PEDESTAL, base.asLong()));
                    check(progress != null && progress.activations() == 1 && ledger.state().revision() == revision + 1,
                        "The committed launch has no corresponding mechanic activation");
                    check(number(player, AbilityRuntimeExample.ECHO, "starts") == priorStarts && WorldAbilityRuntime.activeCount(level) == active + 1,
                        "Program source executed before the committed ledger reached the server tick");
                    launched = true;
                }
                return launched && number(player, AbilityRuntimeExample.ECHO, "starts") == priorStarts + 1
                    && !WorldAbilityRuntime.isActive(player, AbilityRuntimeExample.ECHO);
            }));
            trials.add(new Trial("item / USE runs that same shared source", () -> {
                stand(0, 0); priorStarts = number(player, AbilityRuntimeExample.ECHO, "starts");
            }, () -> {
                if (!launched && WorldAbilityRuntime.canStart(level, player, AbilityRuntimeExample.ECHO, player.position())) {
                    useWand(AbilityRuntimeExample.ECHO_WAND); launched = true;
                }
                return launched && number(player, AbilityRuntimeExample.ECHO, "starts") == priorStarts + 1
                    && !WorldAbilityRuntime.isActive(player, AbilityRuntimeExample.ECHO);
            }));
            trials.add(new Trial("hurt / a real damage event selects the interrupted branch", () -> stand(0, 0), () -> {
                if (!launched && WorldAbilityRuntime.canStart(level, player, AbilityRuntimeExample.CHANNEL, player.position())) {
                    useWand(AbilityRuntimeExample.CHANNEL_WAND); launched = true;
                }
                if (!launched || !text(player, AbilityRuntimeExample.CHANNEL, "branch").equals("channeling"))
                    return launched && interrupted("hurt");
                if (castAt < 0) {
                    castAt = level.getGameTime(); player.invulnerableTime = 0;
                    check(player.hurtServer(level, player.damageSources().generic(), 1F), "Real player hurt event was rejected");
                }
                return false;
            }));
            trials.add(new Trial("block break / a native destruction event selects the other interruption reason", () -> stand(0, 0), () -> {
                if (!launched && WorldAbilityRuntime.canStart(level, player, AbilityRuntimeExample.CHANNEL, player.position())
                    && !player.getCooldowns().isOnCooldown(CustomItemRuntime.snapshot(level).stack("worldsmith:item/" + AbilityRuntimeExample.CHANNEL_WAND, 1))) {
                    useWand(AbilityRuntimeExample.CHANNEL_WAND); launched = true;
                }
                if (!launched || !text(player, AbilityRuntimeExample.CHANNEL, "branch").equals("channeling"))
                    return launched && interrupted("block_break");
                if (castAt < 0) {
                    castAt = level.getGameTime(); var at = base.offset(2, 0, 0);
                    level.setBlock(at, Blocks.DIRT.defaultBlockState(), Block.UPDATE_ALL);
                    check(player.gameMode.destroyBlock(at), "Native player block destruction was rejected");
                }
                return false;
            }));
            trials.add(new Trial("projectile / native impact calls source and splits only once", () -> { stand(0, 0); wall(true); }, () -> {
                if (!launched) {
                    System.out.println("[AbilityRuntime] projectile fixture before USE: " + projectileDiagnostics());
                    useWand(AbilityRuntimeExample.ORB_WAND); launched = true;
                }
                if ((level.getGameTime() - trialAt) % 40 == 0)
                    System.out.println("[AbilityRuntime] projectile tick " + (level.getGameTime() - trialAt) + ": " + projectileDiagnostics());
                long elapsed = level.getGameTime() - trialAt;
                // An 80-tick seed can create 30-tick sparks at impact; callbacks/cues add
                // only bounded scheduling slack. Loaded but non-ticking chunks must not
                // retain the resource until the much larger whole-program lifetime limit.
                if (elapsed >= 125) check(!WorldAbilityRuntime.isActive(player, AbilityRuntimeExample.ORB),
                    "Owned projectile resources outlived their world-time lease: " + projectileDiagnostics());
                if (number(player, AbilityRuntimeExample.ORB, "hits") < 2 || WorldAbilityRuntime.isActive(player, AbilityRuntimeExample.ORB)) return false;
                check(elapsed <= 125, "Projectile callbacks completed only after their resource deadline");
                check(level.getEntitiesOfClass(AbilityProjectile.class, new AABB(base).inflate(20)).isEmpty(), "Owned callback projectiles leaked");
                return true;
            }));
            trials.add(new Trial("native argument limits fail closed without applying oversized damage", () -> {
                check(WorldAbilityRuntime.start(level, player, "native_limit", player.position(), null, 1, false), "Limit test failed to enqueue");
            }, () -> {
                if (WorldAbilityRuntime.isActive(player, "native_limit")) return false;
                check(WorldAbilityRuntime.lastFailure(player, "native_limit") != null, "Out-of-range damage had no diagnostic");
                check(player.getHealth() == 20F, "Rejected oversized damage had a partial effect"); return true;
            }));
            trials.add(new Trial("operation budget stops a busy program without blocking subsequent server ticks", () -> {
                check(WorldAbilityRuntime.start(level, player, "busy_limit", player.position(), null, 1, false), "Budget test failed to enqueue");
            }, () -> {
                if (WorldAbilityRuntime.isActive(player, "busy_limit")) return false;
                check(WorldAbilityRuntime.lastFailure(player, "busy_limit") != null, "Busy loop had no budget diagnostic");
                check(bool(player, "busy_limit", "entered"), "The budget test never actually executed"); return true;
            }));
            trials.add(new Trial("installed native capability extends source without a VM branch", () -> {
                check(AbilityRuntimeTestBootstrap.CALLS.get() == 0, "Native extension executed before its program start");
                check(WorldAbilityRuntime.start(level, player, "native_extension", Vec3.atCenterOf(base.below()), null, 1, false),
                    "A registered capability program failed to enqueue");
                check(AbilityRuntimeTestBootstrap.CALLS.get() == 0, "Committing the invocation executed source synchronously");
            }, () -> {
                if (WorldAbilityRuntime.isActive(player, "native_extension")) return false;
                check(WorldAbilityRuntime.lastFailure(player, "native_extension") == null, "Registered native extension failed");
                check(text(player, "native_extension", "material").equals("minecraft:stone"), "Source did not store the actual native provider result");
                check(AbilityRuntimeTestBootstrap.CALLS.get() == 1, "The installed provider was skipped or replayed"); return true;
            }));
            trials.add(new Trial("PvP disabled / self status and push work while another survival player stays protected", () -> {
                stand(0, 0);
                previousPvp = level.getGameRules().get(GameRules.PVP);
                level.getGameRules().set(GameRules.PVP, false, level.getServer());
                var cookie = CommonListenerCookie.createInitial(new GameProfile(UUID.randomUUID(), "ability-guard"), false);
                otherPlayer = new ServerPlayer(level.getServer(), level, cookie.gameProfile(), cookie.clientInformation());
                var connection = new Connection(PacketFlow.SERVERBOUND);
                new EmbeddedChannel(connection);
                level.getServer().getPlayerList().placeNewPlayer(connection, otherPlayer, cookie);
                otherPlayer.connection.handleAcceptPlayerLoad(new ServerboundPlayerLoadedPacket());
                otherPlayer.setGameMode(GameType.SURVIVAL); otherPlayer.setNoGravity(true);
                otherPlayer.snapTo(base.getX() + 4.5, base.getY(), base.getZ() + .5, 0, 0);
                level.getChunkSource().move(otherPlayer);
                otherPlayer.setHealth(20F); otherPlayer.removeAllEffects(); otherPlayer.setDeltaMovement(Vec3.ZERO);
                check(!level.isPvpAllowed() && !player.canHarmPlayer(player) && !player.canHarmPlayer(otherPlayer),
                    "Fixture did not actually disable the vanilla PvP policy");
                check(!otherPlayer.isCreative() && !otherPlayer.isSpectator() && otherPlayer.connection.hasClientLoaded(),
                    "PvP protection must be tested against a real survival player, not a creative mock");
                check(WorldAbilityRuntime.start(level, player, "pvp_self", player.position(), otherPlayer, 1, false),
                    "PvP-disabled program failed to enqueue");
            }, () -> {
                if (!bool(player, "pvp_self", "done")) return false;
                check(WorldAbilityRuntime.lastFailure(player, "pvp_self") == null, "PvP program failed before its assertions");
                check(bool(player, "pvp_self", "self_status") && player.hasEffect(MobEffects.SPEED),
                    "PvP disabled incorrectly rejected a self-targeted status effect");
                check(bool(player, "pvp_self", "self_push") && player.getDeltaMovement().x > .001,
                    "PvP disabled incorrectly rejected a real self push");
                check(!bool(player, "pvp_self", "other_status") && !otherPlayer.hasEffect(MobEffects.SLOWNESS),
                    "Self-effect exception bypassed another player's PvP status protection");
                check(!bool(player, "pvp_self", "other_push") && otherPlayer.getDeltaMovement().lengthSqr() < 1.0e-8,
                    "Self-effect exception pushed another protected player");
                check(otherPlayer.getHealth() == 20F, "PvP protection trial damaged its protected target");
                return !WorldAbilityRuntime.isActive(player, "pvp_self");
            }));
            trials.add(new Trial("presentation / no-wait pose and caption leases survive idle source then expire", () -> {
                spawnBoss();
                // Keep the ordinary creature adapter from independently starting its bound combat program.
                boss.setTarget(null); player.setGameMode(GameType.CREATIVE);
                check(WorldAbilityRuntime.start(level, boss, "presentation_lease", boss.position(), null, 1, false),
                    "Presentation-only program failed to enqueue");
            }, () -> {
                if (!bool(boss, "presentation_lease", "started")) return false;
                // The helper observes server-END effects on the following tick, so allow that one-tick skew.
                if (castAt < 0) castAt = level.getGameTime();
                long elapsed = level.getGameTime() - castAt;
                if (elapsed < 28) {
                    check(WorldAbilityRuntime.isActive(boss, "presentation_lease"), "Idle source discarded a live 30-tick presentation lease");
                    check(boss.action() == CreatureCombatState.WINDUP.ordinal(), "Pose duration ended before its lease expired");
                    check(WorldAbilityRuntime.caption(boss).equals("Lease remains"), "Caption duration ended when the no-wait source became idle");
                    check(WorldAbilityRuntime.lastFailure(boss, "presentation_lease") == null, "Idle presentation consumed its operation budget");
                    return false;
                }
                if (elapsed < 32) return false;
                check(!WorldAbilityRuntime.isActive(boss, "presentation_lease"), "Expired presentation retained an idle invocation");
                check(boss.action() == CreatureCombatState.IDLE.ordinal() && WorldAbilityRuntime.caption(boss).isEmpty(),
                    "Expired presentation did not clear its pose and caption");
                return true;
            }));
            trials.add(new Trial("overlap / unrelated completion and expired reservation preserve the owner's presentation", () -> {
                presentationActor(); startOnBoss("overlap_long");
            }, () -> {
                if (!bool(boss, "overlap_long", "started")) return false;
                if (!launched) {
                    castAt = level.getGameTime(); startOnBoss("overlap_noop");
                    uncommittedCast = WorldAbilityRuntime.prepareStart(level, boss, "overlap_uncommitted", boss.position(), null, 50, false);
                    check(uncommittedCast != null, "Overlapping reservation was not created");
                    check(!WorldAbilityRuntime.isActive(boss, "overlap_uncommitted"), "Reservation executed without commit");
                    launched = true;
                }
                check(WorldAbilityRuntime.isActive(boss, "overlap_long"), "Unrelated program retired the owning lease");
                check(boss.action() == CreatureCombatState.WINDUP.ordinal() && WorldAbilityRuntime.caption(boss).equals("Long lease"),
                    "A program with no pose, or an expired uncommitted reservation, cleared another invocation's presentation");
                if (level.getGameTime() - castAt < 12) return false;
                check(bool(boss, "overlap_noop", "finished") && !WorldAbilityRuntime.isActive(boss, "overlap_noop"), "No-pose source did not actually finish");
                check(WorldAbilityRuntime.activeCount(level) == 1, "Expired uncommitted reservation leaked an active slot");
                check(WorldAbilityRuntime.state(boss, "overlap_uncommitted").isEmpty(), "An uncommitted reservation wrote actor state");
                check(WorldAbilityRuntime.canStart(level, boss, "overlap_uncommitted", boss.position()), "An uncommitted reservation charged recovery debt");
                releaseReservation();
                return true;
            }));
            trials.add(new Trial("overlap / newest pose and caption win then restore an older live lease", () -> {
                presentationActor(); startOnBoss("overlap_long");
            }, () -> {
                if (!bool(boss, "overlap_long", "started")) return false;
                if (!launched) { startOnBoss("overlap_short"); launched = true; }
                if (!bool(boss, "overlap_short", "started")) return false;
                if (castAt < 0) castAt = level.getGameTime();
                long elapsed = level.getGameTime() - castAt;
                if (elapsed < 10) {
                    check(boss.action() == CreatureCombatState.STRIKE.ordinal(), "The newest valid pose did not win");
                    check(WorldAbilityRuntime.caption(boss).equals("Short lease"), "An older caption hid the newest valid caption");
                    return false;
                }
                if (elapsed < 15) return false;
                check(!WorldAbilityRuntime.isActive(boss, "overlap_short") && WorldAbilityRuntime.isActive(boss, "overlap_long"),
                    "The short lease did not expire independently of the long lease");
                check(boss.action() == CreatureCombatState.WINDUP.ordinal() && WorldAbilityRuntime.caption(boss).equals("Long lease"),
                    "Ending the latest presentation did not restore the still-live older lease");
                return true;
            }));
            trials.add(new Trial("overlap / an older pose expiry never clears a newer active lease", () -> {
                presentationActor(); startOnBoss("overlap_old_short");
            }, () -> {
                if (!bool(boss, "overlap_old_short", "started")) return false;
                if (!launched) { startOnBoss("overlap_new_long"); launched = true; }
                if (!bool(boss, "overlap_new_long", "started")) return false;
                if (castAt < 0) castAt = level.getGameTime();
                check(WorldAbilityRuntime.isActive(boss, "overlap_new_long"), "New long presentation unexpectedly ended");
                check(boss.action() == CreatureCombatState.STRIKE.ordinal() && WorldAbilityRuntime.caption(boss).equals("New lease"),
                    "An older owner's expiry/cleanup cleared the newer pose or caption");
                if (level.getGameTime() - castAt < 16) return false;
                check(!WorldAbilityRuntime.isActive(boss, "overlap_old_short"), "The older short source never reached its expiry");
                return true;
            }));
            trials.add(new Trial("overlap / explicit higher-priority control owns its exact native path", () -> {
                quietNavigationActor();
            }, () -> {
                if (!bool(boss, "overlap_motion_old", "started")) {
                    if (!WorldAbilityRuntime.isActive(boss, "overlap_motion_old")) {
                        if (!navigationSettled()) {
                            check(level.getGameTime() - trialAt < 40, "Quiet navigation actor did not settle naturally: " + navigationDiagnostics());
                            return false;
                        }
                        motionStart = boss.position();
                        System.out.println("[AbilityRuntime] quiet navigation settled: " + navigationDiagnostics());
                        startOnBoss("overlap_motion_old");
                    }
                    return false;
                }
                if (!launched) { startOnBoss("overlap_motion_new"); launched = true; }
                check(WorldAbilityRuntime.lastFailure(boss, "overlap_motion_new") == null, "Navigation program failed: "
                    + WorldAbilityRuntime.lastFailure(boss, "overlap_motion_new"));
                if (!bool(boss, "overlap_motion_new", "started")) return false;
                check(bool(boss, "overlap_motion_new", "pathAccepted"), "The actual native navigator rejected the test's flat-floor path: " + navigationDiagnostics());
                check(number(boss, "overlap_motion_old", "token") > 0 && number(boss, "overlap_motion_new", "token") > 0,
                    "Movement sources skipped their explicit control claims");
                if (ownedPath == null) ownedPath = boss.getNavigation().getPath();
                if (castAt < 0) castAt = level.getGameTime();
                long elapsed = level.getGameTime() - castAt;
                if (elapsed < 48) {
                    check(AbilityActorControl.held(boss, WorldAbilityRuntime.invocation(boss, "overlap_motion_new"), (int)number(boss, "overlap_motion_new", "token")),
                        "Higher-priority movement has no invocation-owned token");
                    check(WorldAbilityRuntime.isActive(boss, "overlap_motion_new") && !boss.getNavigation().isDone(),
                        "An older motion owner's completion stopped the newer invocation's path");
                    check(boss.getNavigation().getPath() == ownedPath, "Older cleanup replaced or cleared the newer exact Path");
                    if (elapsed >= 14) check(bool(boss, "overlap_motion_old", "finished")
                        && !WorldAbilityRuntime.isActive(boss, "overlap_motion_old"), "The old motion source never completed");
                    if (elapsed >= 25) check(boss.getX() > motionStart.x + .01, "A claimed live path did not move the actual creature; motionStart="
                        + motionStart + "; " + navigationDiagnostics());
                    return false;
                }
                if (elapsed < 65) return false;
                check(!WorldAbilityRuntime.isActive(boss, "overlap_motion_new") && boss.getNavigation().isDone(),
                    "The actual motion owner did not stop its path when it finished");
                return true;
            }));
            trials.add(new Trial("overlap / real creature target loss and binding cooldown preserve an independent reaction", () -> {
                // Keep the production goal selectors intact, unlike the isolated navigation trial.
                spawnBoss(); boss.setNoGravity(false);
                boss.snapTo(base.getX() + .5, base.getY() + 1, base.getZ() + .5, 0, 0);
                boss.setTarget(null); player.setGameMode(GameType.CREATIVE);
            }, () -> {
                if (!launched) {
                    if (player.isCreative()) {
                        if (!navigationSettled()) {
                            check(level.getGameTime() - trialAt < 40, "Real navigation actor did not settle naturally: " + navigationDiagnostics());
                            return false;
                        }
                        System.out.println("[AbilityRuntime] real navigation settled: " + navigationDiagnostics());
                        player.setGameMode(GameType.SURVIVAL); boss.setTarget(player);
                        return false;
                    }
                    if (!WorldAbilityRuntime.isActive(boss, AbilityRuntimeExample.ECHO)
                        || number(boss, AbilityRuntimeExample.ECHO, "starts") == 0) return false;
                    priorStarts = number(boss, AbilityRuntimeExample.ECHO, "starts");
                    startOnBoss("overlap_reaction"); launched = true;
                }
                check(WorldAbilityRuntime.lastFailure(boss, "overlap_reaction") == null, "Independent reaction failed: "
                    + WorldAbilityRuntime.lastFailure(boss, "overlap_reaction"));
                if (!bool(boss, "overlap_reaction", "started")) return false;
                check(bool(boss, "overlap_reaction", "pathAccepted"), "Real creature reaction did not obtain a native path: " + navigationDiagnostics());
                if (castAt < 0) {
                    castAt = level.getGameTime(); motionStart = boss.position();
                    player.setGameMode(GameType.CREATIVE);
                }
                long elapsed = level.getGameTime() - castAt;
                check(WorldAbilityRuntime.isActive(boss, "overlap_reaction"), "Binding target loss cancelled an independent targetless invocation");
                check(boss.action() == CreatureCombatState.STRIKE.ordinal() && WorldAbilityRuntime.caption(boss).equals("Independent reaction"),
                    "The production creature adapter overwrote the independent pose/caption");
                check(!boss.getNavigation().isDone(), "The production creature adapter stopped the independent path");
                check(player.getHealth() == 20F, "The cancelled binding produced a delayed hit");
                if (elapsed < 3) return false;
                check(!WorldAbilityRuntime.isActive(boss, AbilityRuntimeExample.ECHO), "Losing a creative target did not cancel the binding program");
                if (elapsed >= 6 && player.isCreative()) {
                    // Reacquire during the binding's 40-tick cooldown, then keep watching past
                    // its expiry. The other active program owns this creature until it ends.
                    player.setGameMode(GameType.SURVIVAL); boss.setTarget(player);
                }
                check(number(boss, AbilityRuntimeExample.ECHO, "starts") == priorStarts,
                    "The ordinary binding restarted while another invocation still controlled its actor");
                if (elapsed >= 25) check(boss.getX() > motionStart.x + .01, "Independent source retained a nominal path but the creature never moved; motionStart="
                    + motionStart + "; " + navigationDiagnostics());
                return elapsed >= 60;
            }));
            trials.add(new Trial("projectile lease / paused physics expires in world time and retired callbacks stay rejected", () -> {
                deadlineCandidate = 0; deadlineProjectile = null; lateProbeSent = false;
                prepareDeadlineCandidate();
            }, () -> {
                if (!launched) {
                    if (level.getGameTime() - deadlineCandidateAt < 8) return false;
                    var point = BlockPos.containing(deadlineOrigin);
                    if (level.isPositionEntityTicking(point)) {
                        System.out.println("[AbilityRuntime] deadline probe candidate " + deadlineCandidate + " is already ticking at " + point);
                        check(++deadlineCandidate < 4, "All four outside-arena deadline candidates are entity-ticking; probe precondition not established");
                        prepareDeadlineCandidate(); return false;
                    }
                    check(WorldAbilityRuntime.loaded(level, new AABB(point).inflate(2)), "Deadline probe should be loaded without an entity-ticking ticket");
                    check(WorldAbilityRuntime.start(level, player, "projectile_deadline_probe", deadlineOrigin, null, 1, false),
                        "Loaded/non-ticking deadline program failed to enqueue");
                    launched = true;
                }
                check(WorldAbilityRuntime.lastFailure(player, "projectile_deadline_probe") == null, "Deadline probe source failed: "
                    + WorldAbilityRuntime.lastFailure(player, "projectile_deadline_probe"));
                if (!bool(player, "projectile_deadline_probe", "sent")) return false;
                if (deadlineProjectile == null) {
                    var handle = WorldAbilityRuntime.state(player, "projectile_deadline_probe").get("projectile");
                    check(handle instanceof AbilityValue.EntityValue, "The real projectile.emit returned no entity handle");
                    var entity = level.getEntity(UUID.fromString(((AbilityValue.EntityValue) handle).getId()));
                    check(entity instanceof AbilityProjectile, "The source-created projectile was not published");
                    deadlineProjectile = (AbilityProjectile) entity; deadlinePhysicsTicks = entity.tickCount; castAt = level.getGameTime();
                    System.out.println("[AbilityRuntime] non-ticking deadline probe: uuid=" + entity.getUUID() + ",ticks=" + entity.tickCount
                        + ",position=" + entity.position() + ",loaded=" + WorldAbilityRuntime.loaded(level, entity.getBoundingBox().inflate(1))
                        + ",entityTicking=" + level.isPositionEntityTicking(entity.blockPosition()));
                }
                long elapsed = level.getGameTime() - castAt;
                check(!level.isPositionEntityTicking(BlockPos.containing(deadlineOrigin)), "The paused-physics probe unexpectedly gained a ticking ticket");
                check(deadlineProjectile.tickCount == deadlinePhysicsTicks, "The deadline proof must not rely on projectile physics ticks");
                check(number(player, "projectile_deadline_probe", "hits") == 0, "A paused/retired projectile delivered a gameplay callback");
                if (elapsed < 25) return false;
                check(deadlineProjectile.isRemoved() && level.getEntity(deadlineProjectile.getUUID()) == null,
                    "World-time expiry did not discard a loaded, non-ticking projectile");
                if (!lateProbeSent) {
                    check(WorldAbilityRuntime.isActive(player, "projectile_deadline_probe"), "The caption must keep the invocation alive to test resource ownership, not just instance removal");
                    AbilityProjectileLeaseChecks.deliverRetiredImpact(level, deadlineProjectile.invocation(), deadlineProjectile.getUUID(), deadlineProjectile.position());
                    lateProbeSent = true;
                }
                if (elapsed < 75) check(WorldAbilityRuntime.isActive(player, "projectile_deadline_probe"), "The independent caption lease ended early");
                if (elapsed < 83) return false; // Observe several END ticks after the injected late callback.
                check(!WorldAbilityRuntime.isActive(player, "projectile_deadline_probe") && WorldAbilityRuntime.activeCount(level) == 0,
                    "Completed caption/projectile leases retained an invocation");
                check(number(player, "projectile_deadline_probe", "hits") == 0, "An expired projectile UUID was accepted while its invocation was still alive");
                return true;
            }));
        }

        void tick() {
            if (closed) return;
            check(helper.getTick() < 2700, "Ability suite exceeded its bounded deadline");
            player.getFoodData().setFoodLevel(10);
            // placeNewPlayer does not add this manually owned EmbeddedChannel to the network
            // listener's connections. Drive its ordinary packet-listener tick so Player.doTick,
            // item cooldowns and status durations advance exactly as for a connected player.
            player.connection.tick();
            if (otherPlayer != null) otherPlayer.connection.tick();
            if (current < 0) {
                if (!arenaTicking()) {
                    var readiness = arenaForcedBefore.keySet().stream()
                        .map(chunk -> {
                            boolean ticking = level.isPositionEntityTicking(chunk.getMiddleBlockPosition(base.getY()));
                            return chunk + "=" + ticking;
                        }).toList();
                    check(level.getGameTime() - ticketWaitAt < 200, "Fixture chunks never became entity-ticking: " + readiness);
                    return;
                }
                next(); // Count each gameplay trial only after normal chunk promotion has completed.
            }
            if (level.getGameTime() - trialAt >= 300) {
                var wand = CustomItemRuntime.snapshot(level).stack("worldsmith:item/" + AbilityRuntimeExample.CHANNEL_WAND, 1);
                throw new IllegalStateException("Timed out: " + trials.get(current).name() + "; launched=" + launched + "; castAt=" + castAt
                    + "; active=" + WorldAbilityRuntime.activeCount(level) + "; channelCooldown=" + player.getCooldowns().isOnCooldown(wand)
                    + "; channelState=" + WorldAbilityRuntime.state(player, AbilityRuntimeExample.CHANNEL)
                    + "; failure=" + WorldAbilityRuntime.lastFailure(player, AbilityRuntimeExample.CHANNEL)
                    + "; activeInvocations=" + activeInvocationDiagnostics() + "; projectile=" + projectileDiagnostics());
            }
            if (trials.get(current).poll().getAsBoolean()) {
                System.out.println("[AbilityRuntime] PASS " + trials.get(current).name());
                if (current + 1 == trials.size()) { close(); helper.succeed(); }
                else next();
            }
        }

        void next() {
            releaseReservation();
            restorePvpTrial();
            if (boss != null) { WorldAbilityRuntime.cancelOwner(boss); boss.discard(); boss = null; }
            WorldAbilityRuntime.cancelOwner(player);
            wall(false); level.setBlock(base, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            player.setGameMode(GameType.SURVIVAL); player.setHealth(20F); player.removeAllEffects(); player.invulnerableTime = 0;
            player.getInventory().clearContent(); player.getInventory().setSelectedSlot(0); player.setDeltaMovement(Vec3.ZERO);
            launched = false; castAt = -1; motionStart = null; ownedPath = null; trialAt = level.getGameTime(); current++;
            trials.get(current).start().run();
        }

        void requestArenaTickets() {
            ticketWaitAt = level.getGameTime();
            // Only the small fixture floor/collision/navigation area. The independent deadline
            // probe deliberately stays outside these chunks and is merely loaded, never forced.
            for (int x = (base.getX() - 12) >> 4; x <= (base.getX() + 12) >> 4; x++)
                for (int z = (base.getZ() - 12) >> 4; z <= (base.getZ() + 12) >> 4; z++) {
                    var chunk = new ChunkPos(x, z);
                    boolean wasForced = level.getChunkSource().getForceLoadedChunks().contains(chunk.pack());
                    arenaForcedBefore.put(chunk, wasForced);
                    if (!wasForced) level.setChunkForced(x, z, true);
                }
        }

        boolean arenaTicking() {
            return arenaForcedBefore.keySet().stream().allMatch(chunk -> level.isPositionEntityTicking(chunk.getMiddleBlockPosition(base.getY())));
        }

        void restoreArenaTickets() {
            for (var entry : arenaForcedBefore.entrySet()) {
                level.setChunkForced(entry.getKey().x(), entry.getKey().z(), entry.getValue());
                check(level.getChunkSource().getForceLoadedChunks().contains(entry.getKey().pack()) == entry.getValue(), "Fixture did not restore a prior forced-chunk flag");
            }
            arenaForcedBefore.clear();
        }

        void prepareDeadlineCandidate() {
            // The probe is always at least 19 blocks beyond the arena's +12/-12 edge;
            // it cannot occupy one of the chunks forced by this fixture.
            int[][] candidates = {{11, 0, 31, 0}, {-11, 0, -31, 0}, {0, 11, 0, 31}, {0, -11, 0, -31}};
            int[] selected = candidates[deadlineCandidate];
            stand(selected[0], selected[1]);
            deadlineOrigin = new Vec3(base.getX() + selected[2] + .5, base.getY(), base.getZ() + selected[3] + .5);
            var point = BlockPos.containing(deadlineOrigin);
            check(!arenaForcedBefore.containsKey(ChunkPos.containing(point)), "Deadline probe overlapped the stable physics arena");
            for (int x = (point.getX() - 2) >> 4; x <= (point.getX() + 2) >> 4; x++)
                for (int z = (point.getZ() - 2) >> 4; z <= (point.getZ() + 2) >> 4; z++) level.getChunk(x, z);
            deadlineCandidateAt = level.getGameTime();
        }

        void spawnBoss() {
            stand(0, 4);
            boss = new HostileCreatureEntity(CreatureRuntime.hostileType(), level);
            boss.snapTo(base.getX() + .5, base.getY(), base.getZ() + .5, 0, 0);
            boss.initialize(pack.getComputedId(), MechanicDiscoveryExample.GUARDIAN, 1234);
            boss.setNoGravity(true);
            check(level.addFreshEntity(boss), "Actual creature insertion failed"); boss.setTarget(player);
        }

        void presentationActor() {
            spawnBoss(); boss.setTarget(null); player.setGameMode(GameType.CREATIVE);
        }

        void quietNavigationActor() {
            stand(0, 4); player.setGameMode(GameType.CREATIVE);
            var quiet = new QuietProgramCreature(level); boss = quiet;
            boss.snapTo(base.getX() + .5, base.getY() + 1, base.getZ() + .5, 0, 0);
            boss.initialize(pack.getComputedId(), MechanicDiscoveryExample.GUARDIAN, 2234);
            quiet.removeAutonomousGoals(); boss.setNoGravity(false);
            check(!boss.isNoAi() && level.addFreshEntity(boss), "Navigation fixture must retain real native AI ticking");
        }

        boolean navigationSettled() {
            return boss.tickCount >= 5 && boss.onGround() && Math.abs(boss.getY() - base.getY()) < .01 && level.noCollision(boss);
        }

        String navigationDiagnostics() {
            var feet = boss.blockPosition();
            var wanted = BlockPos.containing((motionStart == null ? boss.position() : motionStart).add(8, 0, 0));
            var control = boss.getMoveControl(); var path = boss.getNavigation().getPath();
            return "position=" + boss.position() + ",ticks=" + boss.tickCount + ",onGround=" + boss.onGround()
                + ",NoAI=" + boss.isNoAi() + ",NoGravity=" + boss.isNoGravity() + ",velocity=" + boss.getDeltaMovement()
                + ",width=" + boss.getBbWidth() + ",height=" + boss.getBbHeight() + ",type=" + boss.getType()
                + ",class=" + boss.getClass().getSimpleName() + ",navigation=" + boss.getNavigation().getClass().getSimpleName()
                + ",bounds=" + boss.getBoundingBox() + ",collisionFree=" + level.noCollision(boss)
                + ",feet=" + level.getBlockState(feet) + ",support=" + level.getBlockState(feet.below())
                + ",pathWanted=" + wanted + ",targetFeet=" + level.getBlockState(wanted) + ",targetSupport=" + level.getBlockState(wanted.below())
                + ",entityTicking=" + level.isPositionEntityTicking(feet) + ",path=" + path
                + ",nodeIndex=" + (path == null ? -1 : path.getNextNodeIndex()) + ",nodeCount=" + (path == null ? 0 : path.getNodeCount())
                + ",pathTarget=" + (path == null ? null : path.getTarget()) + ",hasWanted=" + control.hasWanted()
                + ",wantedXYZ=" + new Vec3(control.getWantedX(), control.getWantedY(), control.getWantedZ())
                + ",controlSpeed=" + control.getSpeedModifier() + ",actualSpeed=" + boss.getSpeed()
                + ",movementAttribute=" + boss.getAttributeValue(Attributes.MOVEMENT_SPEED);
        }

        void startOnBoss(String program) {
            check(WorldAbilityRuntime.start(level, boss, program, boss.position(), null, 1, false), "Failed to enqueue overlap source " + program);
        }

        void releaseReservation() {
            if (uncommittedCast != null) { uncommittedCast.close(); uncommittedCast = null; }
        }

        long afterCast() {
            if (castAt < 0 && WorldAbilityRuntime.isActive(boss, AbilityRuntimeExample.ECHO)
                && number(boss, AbilityRuntimeExample.ECHO, "starts") > 0) castAt = level.getGameTime();
            return castAt < 0 ? -1 : level.getGameTime() - castAt;
        }

        void stand(double x, double z) {
            player.snapTo(base.getX() + .5 + x, base.getY(), base.getZ() + .5 + z, 0, 0);
            player.setDeltaMovement(Vec3.ZERO);
            // Serverbound movement normally performs this after positioning. A manually owned
            // EmbeddedChannel has no movement packets; snapTo alone leaves simulation tickets at login.
            level.getChunkSource().move(player);
        }
        void wall(boolean present) {
            for (int x = -2; x <= 2; x++) for (int y = 0; y < 4; y++)
                level.setBlock(base.offset(x, y, 2), (present ? Blocks.STONE : Blocks.AIR).defaultBlockState(), Block.UPDATE_ALL);
        }
        void useWand(String id) {
            ItemStack stack = CustomItemRuntime.snapshot(level).stack("worldsmith:item/" + id, 1);
            player.setItemInHand(InteractionHand.MAIN_HAND, stack);
            check(stack.use(level, player, InteractionHand.MAIN_HAND).consumesAction(), "Native item USE did not enqueue " + id);
        }

        String projectileDiagnostics() {
            var projectiles = level.getEntitiesOfClass(AbilityProjectile.class, new AABB(base).inflate(64));
            return "player=" + player.position() + ",yaw=" + player.getYRot() + ",pitch=" + player.getXRot()
                + ",look=" + player.getLookAngle() + "; base=" + base + ",baseTicking=" + level.isPositionEntityTicking(base)
                + ",wall=" + level.getBlockState(base.offset(0, 1, 2)) + ",wallTicking=" + level.isPositionEntityTicking(base.offset(0, 1, 2))
                + "; orbActive=" + WorldAbilityRuntime.isActive(player, AbilityRuntimeExample.ORB)
                + "; orbState=" + WorldAbilityRuntime.state(player, AbilityRuntimeExample.ORB)
                + "; orbFailure=" + WorldAbilityRuntime.lastFailure(player, AbilityRuntimeExample.ORB)
                + "; count=" + projectiles.size() + "; entities=" + projectiles.stream().limit(32).map(projectile ->
                    "{id=" + projectile.getId() + ",uuid=" + projectile.getUUID() + ",invocation=" + projectile.invocation()
                        + ",ticks=" + projectile.tickCount + ",position=" + projectile.position() + ",velocity=" + projectile.getDeltaMovement()
                        + ",alive=" + projectile.isAlive() + ",removed=" + projectile.isRemoved()
                        + ",loaded=" + WorldAbilityRuntime.loaded(level, projectile.getBoundingBox().inflate(1))
                        + ",entityTicking=" + level.isPositionEntityTicking(projectile.blockPosition()) + "}").toList();
        }

        List<String> activeInvocationDiagnostics() {
            var actors = new java.util.LinkedHashSet<net.minecraft.world.entity.LivingEntity>();
            if (player != null) actors.add(player);
            if (boss != null) actors.add(boss);
            if (otherPlayer != null) actors.add(otherPlayer);
            actors.addAll(level.getEntitiesOfClass(net.minecraft.world.entity.LivingEntity.class, new AABB(base).inflate(64)));
            var active = new ArrayList<String>();
            for (var actor : actors) for (var program : pack.getAbilities().getPrograms()) {
                if (WorldAbilityRuntime.isActive(actor, program.getId())) active.add("{actor=" + actor.getUUID() + ",program=" + program.getId()
                    + ",state=" + WorldAbilityRuntime.state(actor, program.getId()) + ",failure=" + WorldAbilityRuntime.lastFailure(actor, program.getId()) + "}");
            }
            return active;
        }
        boolean interrupted(String reason) {
            if (!text(player, AbilityRuntimeExample.CHANNEL, "reason").equals(reason)
                || !text(player, AbilityRuntimeExample.CHANNEL, "branch").equals("weakened")) return false;
            check(player.hasEffect(MobEffects.WEAKNESS), "Interrupted source did not apply its declared recovery weakness"); return true;
        }
        void close() {
            if (closed) return; closed = true;
            try {
                releaseReservation();
                restorePvpTrial();
                if (boss != null) { WorldAbilityRuntime.cancelOwner(boss); boss.discard(); }
                if (player != null) { WorldAbilityRuntime.cancelOwner(player); level.getServer().getPlayerList().remove(player); }
                if (bound) {
                    WorldContentRuntime.unbindLevel(level);
                    level.getDataStorage().set(WorldMechanicSavedData.TYPE, previousLedger == null ? new WorldMechanicSavedData() : previousLedger);
                }
            } finally { restoreArenaTickets(); }
        }
        void restorePvpTrial() {
            if (previousPvp != null) {
                level.getGameRules().set(GameRules.PVP, previousPvp, level.getServer());
                previousPvp = null;
            }
            if (otherPlayer != null) {
                WorldAbilityRuntime.cancelOwner(otherPlayer);
                level.getServer().getPlayerList().remove(otherPlayer);
                otherPlayer = null;
            }
        }
    }

    /** Remove unrelated wandering/target selection, not the native navigation or entity tick. */
    private static final class QuietProgramCreature extends CreatureEntity implements Enemy {
        QuietProgramCreature(ServerLevel level) { super(CreatureRuntime.hostileType(), level); }
        void removeAutonomousGoals() {
            goalSelector.removeAllGoals(goal -> true); targetSelector.removeAllGoals(goal -> true); setTarget(null);
        }
    }

    private static WorldsmithPack fixture() {
        var base = AbilityRuntimeExample.create();
        var programs = new ArrayList<>(base.getAbilities().getPrograms());
        programs.add(new AbilityProgramDefinition("native_limit", "Native numeric bound", "on start { combat.damage(self, 101); }", 100, 128));
        programs.add(new AbilityProgramDefinition("busy_limit", "Operation bound", "on start { state.entered = true; while (true) { state.loop = 1; } }", 100, 128));
        programs.add(new AbilityProgramDefinition("native_extension", "Installed provider", "on start { state.material = test.floor_name(origin); }",
            100, 128, Map.of("test.floor_name", 1)));
        programs.add(new AbilityProgramDefinition("pvp_self", "Self effects respect PvP boundaries", """
            on start {
                state.self_status = status.apply(self, "minecraft:speed", 100, 0);
                state.self_push = motion.push(self, vec(0.4, 0, 0));
                state.other_status = status.apply(target, "minecraft:slowness", 100, 0);
                state.other_push = motion.push(target, vec(0.4, 0, 0));
                state.done = true;
            }
            """, 100, 256));
        programs.add(new AbilityProgramDefinition("presentation_lease", "Idle presentation leases", """
            on start { fx.pose("windup", 30); fx.caption("Lease remains", 30); state.started = true; }
            """, 100, 128));
        programs.add(new AbilityProgramDefinition("overlap_long", "Long presentation owner", """
            on start { fx.pose("windup", 70); fx.caption("Long lease", 70); state.started = true; }
            """, 120, 128));
        programs.add(new AbilityProgramDefinition("overlap_noop", "Does not own a pose", "on start { wait 4; state.finished = true; }", 100, 128));
        programs.add(new AbilityProgramDefinition("overlap_uncommitted", "Must not execute", "on start { state.started = true; }", 100, 128));
        programs.add(new AbilityProgramDefinition("overlap_short", "Newer temporary presentation", """
            on start { fx.pose("strike", 12); fx.caption("Short lease", 12); state.started = true; }
            """, 100, 128));
        programs.add(new AbilityProgramDefinition("overlap_old_short", "Older expiring presentation", """
            on start { fx.pose("windup", 12); fx.caption("Old lease", 12); state.started = true; }
            """, 100, 128));
        programs.add(new AbilityProgramDefinition("overlap_new_long", "Newer long presentation", """
            on start { fx.pose("strike", 50); fx.caption("New lease", 50); state.started = true; }
            """, 100, 128));
        programs.add(new AbilityProgramDefinition("overlap_motion_old", "Older motion command", """
            on start { state.token = control.claim(20, 100); motion.stop(self); state.started = true; wait 10; state.finished = true; control.release(state.token); }
            """, 100, 128));
        programs.add(new AbilityProgramDefinition("overlap_motion_new", "Newer native navigation command", """
            // Navigation takes a native movement-speed multiplier, not blocks per tick.
            // 0.5 keeps this 0.16-speed fixture moving above vanilla's movement dead zone.
            on start { state.token = control.claim(30, 100); state.pathAccepted = motion.navigate(self, vector.add(origin, vec(8, 0, 0)), 0.5); state.started = true; wait 60; state.finished = true; control.release(state.token); }
            """, 100, 128));
        programs.add(new AbilityProgramDefinition("overlap_reaction", "Independent creature reaction", """
            on start {
                state.token = control.claim(80, 100);
                if (state.token == 0) { return; }
                fx.pose("strike", 80); fx.caption("Independent reaction", 80);
                // Native attribute multiplier; keep real movement above vanilla's dead zone.
                state.pathAccepted = motion.navigate(self, vector.add(origin, vec(8, 0, 0)), 0.5);
                state.started = true; wait 80; control.release(state.token);
            }
            """, 120, 128));
        programs.add(new AbilityProgramDefinition("projectile_deadline_probe", "World-time projectile ownership", """
            on start {
                state.hits = 0;
                state.projectile = projectile.emit(vector.add(origin, vec(0, 1, 0)), vec(0, 0.02, 0), 0, 20, "deadline");
                fx.caption("Deadline lease remains", 80); state.sent = true;
            }
            on projectile_hit { state.hits = state.hits + 1; }
            """, 120, 128));
        return WorldContentBundleIO.create("Ability integration test", "Actual production adapters and lifecycle", base.getTerrain(), base.getBiomes(), base.getFeatures(),
            base.getStructures(), base.getTheme(), base.getBlocks(), base.getCreatures(), base.getAssets(), base.getItems(), base.getQuests(),
            base.getManifest().getRepresentativeContent(), base.getMechanics(), new AbilityLibrary(1, programs));
    }
    private static double number(net.minecraft.world.entity.LivingEntity actor, String program, String key) {
        var value = WorldAbilityRuntime.state(actor, program).get(key);
        return value instanceof AbilityValue.NumberValue n ? n.getValue() : 0;
    }
    private static boolean bool(net.minecraft.world.entity.LivingEntity actor, String program, String key) {
        var value = WorldAbilityRuntime.state(actor, program).get(key);
        return value instanceof AbilityValue.BoolValue b && b.getValue();
    }
    private static String text(net.minecraft.world.entity.LivingEntity actor, String program, String key) {
        var value = WorldAbilityRuntime.state(actor, program).get(key);
        return value instanceof AbilityValue.TextValue t ? t.getValue() : "";
    }
    private static void check(boolean condition, String message) { if (!condition) throw new IllegalStateException(message); }
}

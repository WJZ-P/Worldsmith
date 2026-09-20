package com.wjz.worldsmith.gametest;

import com.google.gson.JsonParser;
import com.mojang.authlib.GameProfile;
import com.wjz.worldsmith.ability.*;
import com.wjz.worldsmith.content.WorldContentRuntime;
import com.wjz.worldsmith.content.creature.CreatureEntity;
import com.wjz.worldsmith.content.interaction.WorldMechanicSavedData;
import com.wjz.worldsmith.core.ability.*;
import com.wjz.worldsmith.core.examples.AbilityRuntimeExample;
import com.wjz.worldsmith.core.examples.AbilityGameplayExample;
import com.wjz.worldsmith.core.model.WorldsmithPack;
import com.wjz.worldsmith.core.pack.WorldContentBundleIO;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.*;
import java.util.function.BooleanSupplier;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ServerboundPlayerLoadedPacket;
import net.minecraft.server.level.*;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.*;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.phys.Vec3;

/** Real scheduler/world/damage-pipeline evidence, separate from explicit-fixture Core simulation. */
public final class AbilityGameplayGameTests {
    @GameTest(environment="worldsmith_mechanics_smoke:ability_gameplay",maxTicks=2000,skyAccess=true,padding=30)
    public void genericGameplayPrimitivesWorkInNativeWorld(GameTestHelper helper) {
        var run=new Run(helper);
        try { run.begin(); } catch(Throwable failure) { run.close(); throw failure; }
        helper.onEachTick(() -> { try { run.tick(); } catch(Throwable failure) { run.close(); throw failure; } });
    }
    private record Trial(String name,Runnable start,BooleanSupplier poll) {}
    private static final class Run {
        final GameTestHelper helper; final ServerLevel level; final WorldsmithPack pack=fixture(); final List<Trial> trials=new ArrayList<>();
        final Map<ChunkPos,Boolean> forced=new LinkedHashMap<>();
        WorldMechanicSavedData prior; ServerPlayer player; LivingEntity other; BlockPos base; boolean bound,closed;
        int index=-1,stage; long started,readyAt; UUID child,owned;
        Run(GameTestHelper helper) { this.helper=helper; level=helper.getLevel(); }
        void begin() {
            check(WorldContentRuntime.boundLevelCount()==0,"Gameplay environment overlaps another content binding");
            var biomes=new LinkedHashMap<String,String>(); pack.getBiomes().getBiomes().forEach(b -> biomes.put(b.getId(),"worldsmith:generated/"+pack.getComputedId()+"/"+b.getId()));
            var prepared=WorldContentRuntime.prepare(pack,biomes); prior=level.getDataStorage().get(WorldMechanicSavedData.TYPE);
            level.getDataStorage().set(WorldMechanicSavedData.TYPE,new WorldMechanicSavedData()); WorldContentRuntime.bindLevel(level,prepared); bound=true;
            base=helper.absolutePos(new BlockPos(8,3,8));
            for(int x=(base.getX()-12)>>4;x<=(base.getX()+12)>>4;x++) for(int z=(base.getZ()-12)>>4;z<=(base.getZ()+12)>>4;z++) {
                ChunkPos chunk=new ChunkPos(x,z); boolean was=level.getChunkSource().getForceLoadedChunks().contains(chunk.pack()); forced.put(chunk,was); level.setChunkForced(x,z,true);
            }
            for(int x=-12;x<=12;x++) for(int z=-12;z<=12;z++) {
                level.setBlock(base.offset(x,-1,z),Blocks.STONE.defaultBlockState(),Block.UPDATE_ALL);
                for(int y=0;y<5;y++) level.setBlock(base.offset(x,y,z),Blocks.AIR.defaultBlockState(),Block.UPDATE_ALL);
            }
            var cookie=CommonListenerCookie.createInitial(new GameProfile(UUID.randomUUID(),"gameplay-survival"),false);
            player=new ServerPlayer(level.getServer(),level,cookie.gameProfile(),cookie.clientInformation()); var connection=new Connection(PacketFlow.SERVERBOUND); new EmbeddedChannel(connection);
            level.getServer().getPlayerList().placeNewPlayer(connection,player,cookie); player.connection.handleAcceptPlayerLoad(new ServerboundPlayerLoadedPacket());
            player.setGameMode(GameType.SURVIVAL); player.setNoGravity(true); player.getAttribute(Attributes.MAX_HEALTH).setBaseValue(100); player.setHealth(100); stand();
            started=level.getGameTime(); define();
        }
        void define() {
            trials.add(new Trial("temporary block lease restores after world-time expiry",() -> start("temporary"),() -> {
                if(!flag(player,"temporary","ready")) return false;
                if(stage++==0) { check(level.getBlockState(base.offset(4,0,0)).is(Blocks.GOLD_BLOCK),"Source did not create the temporary native block"); readyAt=level.getGameTime(); }
                if(level.getGameTime()-readyAt<24) return false;
                check(level.getBlockState(base.offset(4,0,0)).isAir(),"Expired block lease did not restore original air");
                return !WorldAbilityRuntime.isActive(player,"temporary");
            }));
            trials.add(new Trial("compare-and-set restore preserves a later differing player edit",() -> start("cas"),() -> {
                if(!flag(player,"cas","ready")) return false;
                if(stage++==0) { level.setBlock(base.offset(4,0,0),Blocks.EMERALD_BLOCK.defaultBlockState(),Block.UPDATE_ALL); WorldAbilityRuntime.cancelProgram(player,"cas"); readyAt=level.getGameTime(); }
                if(level.getGameTime()-readyAt<3) return false;
                check(level.getBlockState(base.offset(4,0,0)).is(Blocks.EMERALD_BLOCK),"Cleanup overwrote a newer block edit");
                level.setBlock(base.offset(4,0,0),Blocks.AIR.defaultBlockState(),Block.UPDATE_ALL); return true;
            }));
            trials.add(new Trial("permanent world edits enforce the per-invocation write ceiling",() -> start("write_limit"),() -> {
                String error=WorldAbilityRuntime.lastFailure(player,"write_limit"); if(error==null) return false;
                check(error.contains("Permanent block write budget"),"Unexpected write failure: "+error);
                for(int i=0;i<16;i++) check(level.getBlockState(base.offset(i-8,-1,5)).is(Blocks.EMERALD_BLOCK),"Accepted permanent write disappeared");
                check(level.getBlockState(base.offset(8,-1,5)).is(Blocks.STONE),"Seventeenth permanent edit escaped quota"); return true;
            }));
            trials.add(new Trial("resource payment and inventory take/give are all-or-nothing",() -> { player.getInventory().setItem(0,new ItemStack(Items.DIAMOND,3)); start("resources"); },() -> {
                if(!flag(player,"resources","done")) return false;
                check(!flag(player,"resources","bad_pay") && value(player,"resources","mana")==10 && value(player,"resources","focus")==2,"Failed payment changed a pool");
                check(flag(player,"resources","paid") && value(player,"resources","mana_after")==6 && value(player,"resources","focus_after")==1,"Atomic payment did not charge the exact costs");
                check(!flag(player,"resources","bad_take") && flag(player,"resources","taken") && flag(player,"resources","given") && value(player,"resources","diamonds")==1,"Inventory cost/output semantics differ from source");
                return true;
            }));
            trials.add(new Trial("child args and queued signals keep an idle parent alive and cascade cancellation",() -> start("parent"),() -> {
                if(!flag(player,"parent","received")) return false;
                if(stage++==0) {
                    child=UUID.fromString(text(player,"parent","child"));
                    check(WorldAbilityRuntime.isActive(player,"parent") && WorldAbilityRuntime.invocationActive(level,child),"Idle parent failed to retain its child");
                    check(value(player,"child","argument")==7 && value(player,"parent","shared")==42,"Child args/shared scene payload was not inherited");
                    WorldAbilityRuntime.cancelProgram(player,"parent");
                }
                check(!WorldAbilityRuntime.invocationActive(level,child) && !WorldAbilityRuntime.isActive(player,"parent"),"Parent cancellation left a live descendant"); return true;
            }));
            trials.add(new Trial("self guard consumes a custom pool before player health loss and reports real prevention",() -> { player.setHealth(100); start("guard"); },() -> {
                if(!flag(player,"guard","ready")) return false;
                if(stage==0) {
                    player.invulnerableTime=0; player.hurtServer(level,player.damageSources().generic(),10); stage=1;
                    check(player.getHealth()==96,"Guard did not intercept player damage before health loss: "+player.getHealth()); return false;
                }
                if(value(player,"guard","blocked")!=6) return false;
                check(value(player,"guard","balance")==0,"Guard resource payment differs from actual prevented damage");
                player.invulnerableTime=0; player.hurtServer(level,player.damageSources().generic(),10);
                check(player.getHealth()==86,"An empty resource pool granted free damage prevention"); WorldAbilityRuntime.cancelProgram(player,"guard"); return true;
            }));
            trials.add(new Trial("same pre-health guard works on the non-player LivingEntity implementation",() -> {
                other=(LivingEntity)net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getOptional(net.minecraft.resources.Identifier.parse("minecraft:cow")).orElseThrow().create(level,EntitySpawnReason.TRIGGERED); check(other!=null,"Native cow creation failed");
                other.snapTo(base.getX()+3.5,base.getY(),base.getZ()+3.5,0,0); other.setNoGravity(true); level.addFreshEntity(other);
                check(WorldAbilityRuntime.start(level,other,"mob_guard",other.position(),null,1,false),"Mob guard did not enqueue");
            },() -> {
                if(!flag(other,"mob_guard","ready")) return false;
                float before=other.getHealth(); other.invulnerableTime=0; other.hurtServer(level,other.damageSources().generic(),4);
                check(other.getHealth()==before-2,"LivingEntity guard hook differs from Player hook"); WorldAbilityRuntime.cancelOwner(other); other.discard(); other=null; return true;
            }));
            trials.add(new Trial("leased custom stacks apply native attributes and restore on cancellation",() -> start("effects"),() -> {
                if(!flag(player,"effects","ready")) return false;
                check(value(player,"effects","stacks")==2,"Custom effect stacks were not exposed");
                check(player.getAttributeValue(Attributes.ARMOR)==4,"Stacked native modifier was not applied");
                WorldAbilityRuntime.cancelProgram(player,"effects"); check(player.getAttributeValue(Attributes.ARMOR)==0,"Effect cleanup left an attribute modifier"); return true;
            }));
            trials.add(new Trial("owned summons use real creature identity and retire with source",() -> start("summon"),() -> {
                if(!flag(player,"summon","ready")) return false;
                var handle=WorldAbilityRuntime.state(player,"summon").get("spawned"); owned=UUID.fromString(((AbilityValue.EntityValue)handle).getId());
                check(level.getEntity(owned) instanceof CreatureEntity,"Summon did not create an actual custom creature");
                WorldAbilityRuntime.cancelProgram(player,"summon"); check(level.getEntity(owned)==null || level.getEntity(owned).isRemoved(),"Source cancellation left an owned summon"); return true;
            }));
            trials.add(new Trial("runtime trace is opt-in bounded native evidence rather than fixture execution",() -> {
                var started=debug("start_trace"); check(started.get("tracing").getAsBoolean(),"Live trace was not enabled"); start("trace");
            },() -> {
                if(!flag(player,"trace","done")) return false;
                var snapshot=debug("snapshot"); check(snapshot.get("liveRuntime").getAsBoolean(),"Snapshot did not report native origin");
                var result=debug("stop_trace"); var entries=result.getAsJsonArray("entries");
                check(entries.size()>0 && entries.size()<=256 && !result.get("tracing").getAsBoolean(),"Native trace did not stop with a bounded buffer");
                check(entries.toString().contains("STATE_WRITE") && entries.toString().contains("line"),"Trace lacks executed source locations/state evidence"); return true;
            }));
            trials.add(new Trial("explicit nearby scene membership delivers cross-actor signals and shared state",() -> {
                spawnCow(); check(WorldAbilityRuntime.start(level,other,"scene_listener",other.position(),player,1,false),"Scene listener did not enqueue");
            },() -> {
                if(!flag(other,"scene_listener","ready")) return false;
                if(stage++==0) { start("scene_sender"); return false; }
                if(value(other,"scene_listener","phase")!=77) return false;
                check(flag(other,"scene_listener","heard") && value(player,"scene_sender","recipients")>=1,"Cross-actor scene signal was not accepted");
                WorldAbilityRuntime.cancelOwner(other); other.discard(); other=null; return true;
            }));
            trials.add(new Trial("native scoped raycast returns the closest exact block normal",() -> {
                level.setBlock(base.offset(0,1,4),Blocks.STONE.defaultBlockState(),Block.UPDATE_ALL); start("raycast");
            },() -> {
                if(!flag(player,"raycast","done")) return false;
                check(text(player,"raycast","kind").equals("block") && flag(player,"raycast","exact"),"Raycast missed the native solid block or lost exactness");
                check(text(player,"raycast","block").equals("minecraft:stone"),"Raycast returned the wrong block identity");
                var normal=WorldAbilityRuntime.state(player,"raycast").get("normal"); check(AbilityValues.vector(0,0,-1).equals(normal),"Raycast normal is not the native NORTH face: "+normal);
                level.setBlock(base.offset(0,1,4),Blocks.AIR.defaultBlockState(),Block.UPDATE_ALL); return true;
            }));
            trials.add(new Trial("entity serialization preserves actor resources and shared values without resetting pools",() -> {
                spawnCow(); check(WorldAbilityRuntime.start(level,other,"resource_save",other.position(),null,1,false),"Persistent resource writer did not enqueue");
            },() -> {
                if(stage==0) {
                    if(!flag(other,"resource_save","done")) return false;
                    var output=net.minecraft.world.level.storage.TagValueOutput.createWithContext(net.minecraft.util.ProblemReporter.DISCARDING,level.registryAccess());
                    other.saveWithoutId(output); var tag=output.buildResult(); tag.remove("UUID"); other.discard();
                    other=(LivingEntity)net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getOptional(net.minecraft.resources.Identifier.parse("minecraft:cow")).orElseThrow().create(level,EntitySpawnReason.TRIGGERED);
                    other.load(net.minecraft.world.level.storage.TagValueInput.create(net.minecraft.util.ProblemReporter.DISCARDING,level.registryAccess(),tag));
                    check(level.addFreshEntity(other),"Reloaded persistent resource actor was rejected");
                    check(WorldAbilityRuntime.start(level,other,"resource_read",other.position(),null,1,false),"Persistent resource reader did not enqueue"); stage=1; return false;
                }
                if(!flag(other,"resource_read","done")) return false;
                check(value(other,"resource_read","balance")==6 && value(other,"resource_read","shared")==42,"Entity save/reload reset spent resources or shared state");
                other.discard(); other=null; return true;
            }));
            trials.add(new Trial("combined damage multipliers are capped after the full product",() -> { player.setHealth(100); start("effect_product"); },() -> {
                if(!flag(player,"effect_product","ready")) return false;
                player.invulnerableTime=0; player.hurtServer(level,player.damageSources().generic(),100);
                check(Math.abs(player.getHealth()-83.616F)<.0001F,"Intermediate multiplier clipping changed damage: "+player.getHealth());
                WorldAbilityRuntime.cancelProgram(player,"effect_product"); return true;
            }));
            trials.add(new Trial("exported three-program sample runs from the actual bound item and completes its restoration",() -> {
                player.setHealth(100);
                var sigil=com.wjz.worldsmith.content.item.CustomItemRuntime.snapshot(level).stack("worldsmith:item/"+AbilityGameplayExample.SIGIL,1);
                player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND,sigil);
                check(sigil.use(level,player,net.minecraft.world.InteractionHand.MAIN_HAND).consumesAction(),"Sample item did not accept native use");
            },() -> {
                if(!flag(player,AbilityGameplayExample.CONDUCTOR,"completed")) return false;
                check(!flag(player,AbilityGameplayExample.CONDUCTOR,"failed"),"Sample composition entered its failure/refund branch");
                if(stage++==0) {
                    check(level.getBlockState(base.offset(2,0,0)).is(Blocks.GLASS),"Sample builder did not create the actual temporary glass");
                    player.invulnerableTime=0; player.hurtServer(level,player.damageSources().generic(),10);
                    check(player.getHealth()==96,"Sample conductor guard did not reduce real damage: health="+player.getHealth()+" state="+WorldAbilityRuntime.state(player,AbilityGameplayExample.CONDUCTOR)+" failure="+WorldAbilityRuntime.lastFailure(player,AbilityGameplayExample.CONDUCTOR));
                }
                if(WorldAbilityRuntime.isActive(player,AbilityGameplayExample.CONDUCTOR)) return false;
                check(value(player,AbilityGameplayExample.CONDUCTOR,"last_prevented")==6,"Sample damage handler did not record its real event");
                check(level.getBlockState(base.offset(2,0,0)).isAir(),"Sample builder cleanup did not restore its glass");
                check(!WorldAbilityRuntime.isActive(player,AbilityGameplayExample.BUILDER) && !WorldAbilityRuntime.isActive(player,AbilityGameplayExample.WITNESS),"Sample left a child invocation active"); return true;
            }));
        }
        void spawnCow() {
            other=(LivingEntity)net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getOptional(net.minecraft.resources.Identifier.parse("minecraft:cow")).orElseThrow().create(level,EntitySpawnReason.TRIGGERED);
            check(other!=null,"Native cow creation failed"); other.snapTo(base.getX()+3.5,base.getY(),base.getZ()+3.5,0,0); other.setNoGravity(true); check(level.addFreshEntity(other),"Cow insertion failed");
        }
        void tick() {
            if(closed) return; player.connection.tick(); stand();
            if(index<0) {
                if(!level.isPositionEntityTicking(base)) { check(level.getGameTime()-started<200,"Native fixture tickets did not become entity-ticking"); return; }
                next(); return;
            }
            check(level.getGameTime()-started<140,"Timed out gameplay trial: "+trials.get(index).name+" failures="+WorldAbilityRuntime.lastFailure(player,currentProgram()));
            if(trials.get(index).poll.getAsBoolean()) { System.out.println("PASS ability gameplay / "+trials.get(index).name); next(); }
        }
        String currentProgram() { return switch(index) { case 0 -> "temporary"; case 1 -> "cas"; case 2 -> "write_limit"; case 3 -> "resources"; case 4 -> "parent"; case 5 -> "guard"; case 6 -> "mob_guard"; case 7 -> "effects"; case 8 -> "summon"; default -> "trace"; }; }
        void next() {
            WorldAbilityRuntime.cancelOwner(player); stage=0; started=level.getGameTime();
            if(++index>=trials.size()) { close(); helper.succeed(); return; }
            trials.get(index).start.run();
        }
        void start(String program) { check(WorldAbilityRuntime.start(level,player,program,player.position(),null,1,false),"Failed to enqueue "+program); }
        void stand() { player.snapTo(base.getX()+.5,base.getY(),base.getZ()+.5,0,0); player.setDeltaMovement(Vec3.ZERO); level.getChunkSource().move(player); }
        com.google.gson.JsonObject debug(String action) {
            var args=(kotlinx.serialization.json.JsonObject)kotlinx.serialization.json.Json.Default.parseToJsonElement("{\"action\":\""+action+"\",\"actor\":\""+player.getUUID()+"\",\"limit\":256}");
            return JsonParser.parseString(new NativeAbilityDebug().inspect(args).toString()).getAsJsonObject();
        }
        void close() {
            if(closed) return; closed=true;
            if(other!=null) { WorldAbilityRuntime.cancelOwner(other); other.discard(); }
            if(player!=null) { WorldAbilityRuntime.cancelOwner(player); level.getServer().getPlayerList().remove(player); player.discard(); }
            if(bound) WorldContentRuntime.unbindLevel(level);
            if(prior!=null) level.getDataStorage().set(WorldMechanicSavedData.TYPE,prior); else level.getDataStorage().set(WorldMechanicSavedData.TYPE,new WorldMechanicSavedData());
            forced.forEach((chunk,was) -> level.setChunkForced(chunk.x(),chunk.z(),was));
        }
    }
    private static WorldsmithPack fixture() {
        var base=AbilityGameplayExample.create(); var programs=new ArrayList<>(base.getAbilities().getPrograms());
        add(programs,"temporary","on start { state.lease = world.set_block(vector.add(origin, vec(4,0,0)), \"minecraft:gold_block\", map.of([]), 20); state.ready = true; }");
        add(programs,"cas","on start { state.lease = world.set_block(vector.add(origin, vec(4,0,0)), \"minecraft:gold_block\", map.of([]), 60); state.ready = true; }");
        add(programs,"write_limit","on start { let i = 0; while (i < 17) { world.set_block(vector.add(origin, vec(i-8,-1,5)), \"minecraft:emerald_block\", map.of([]), 0); i = i + 1; } }");
        add(programs,"resources","""
            on start {
                resource.define("mana",10,10,0); resource.define("focus",2,2,0);
                state.bad_pay = resource.pay(map.of([["mana",4],["focus",3]]));
                state.mana = resource.get("mana"); state.focus = resource.get("focus");
                state.paid = resource.pay(map.of([["mana",4],["focus",1]])); state.mana_after = resource.get("mana"); state.focus_after = resource.get("focus");
                state.bad_take = inventory.take("minecraft:diamond",4); state.taken = inventory.take("minecraft:diamond",2);
                state.given = inventory.give(self,"minecraft:emerald",2); state.diamonds = inventory.count("minecraft:diamond"); state.done = true;
            }
            """);
        add(programs,"parent","""
            on start { scene.join("coord",origin); shared.scene_set("phase",42); state.child = program.start("child",map.of([["count",7],["parent",program.self()]])); }
            on signal { state.received = event_tag == "ready"; state.shared = shared.scene_get("phase"); }
            """);
        add(programs,"child","on start { state.argument = map.get(args,\"count\"); wait 2; signal.send(map.get(args,\"parent\"),\"ready\",state.argument); wait 80; }");
        add(programs,"guard","""
            on start { resource.define("shield",6,6,0); combat.guard(map.of([["multiplier",0.5],["absorb",1],["pool","shield"],["tag","test"]]),100); state.ready = true; }
            on damage_guarded { state.blocked = event_amount; state.balance = resource.get("shield"); }
            """);
        add(programs,"mob_guard","on start { combat.guard(map.of([[\"multiplier\",0.5]]),80); state.ready = true; }");
        add(programs,"effects","on start { effect.apply(self,\"fortify\",2,80,map.of([[\"armor\",2]])); state.stacks = effect.stacks(self,\"fortify\"); state.ready = true; }");
        String creature=base.getCreatures().getCreatures().getFirst().getId();
        add(programs,"summon","on start { state.spawned = entity.spawn(\""+creature+"\",vector.add(origin,vec(6,0,0)),80); state.ready = true; }");
        add(programs,"trace","on start { state.counter = 1; wait 2; state.counter = state.counter + 1; state.done = true; }");
        add(programs,"scene_listener","on start { scene.join(\"multiactor\", entity.position(target)); state.ready = true; wait 100; } on signal { state.heard = event_tag == \"phase\"; state.phase = shared.scene_get(\"phase\"); }");
        add(programs,"scene_sender","on start { scene.join(\"multiactor\", origin); shared.scene_set(\"phase\",77); state.recipients = scene.emit(\"phase\",77); }");
        add(programs,"raycast","on start { let hit = world.raycast(vector.add(origin,vec(0,1,0)),vector.add(origin,vec(0,1,8))); state.kind = map.get(hit,\"kind\"); state.exact = map.get(hit,\"normalExact\"); state.block = map.get(hit,\"blockId\"); state.normal = map.get(hit,\"normal\"); state.done = true; }");
        add(programs,"resource_save","on start { resource.define(\"charge\",10,10,0); resource.consume(\"charge\",4); shared.actor_set(\"saved\",42); state.done = true; }");
        add(programs,"resource_read","on start { resource.define(\"charge\",10,10,0); state.balance = resource.get(\"charge\"); state.shared = shared.actor_get(\"saved\"); state.done = true; }");
        add(programs,"effect_product","on start { repeat 7 { effect.apply(self,\"product\",1,80,map.of([[\"incoming\",4]])); } effect.apply(self,\"product\",1,80,map.of([[\"incoming\",0.00001]])); state.ready = true; }");
        return WorldContentBundleIO.create("Generic gameplay tests","Native state, resources, ownership and damage",base.getTerrain(),base.getBiomes(),base.getFeatures(),base.getStructures(),base.getTheme(),base.getBlocks(),base.getCreatures(),base.getAssets(),base.getItems(),base.getQuests(),base.getManifest().getRepresentativeContent(),base.getMechanics(),new AbilityLibrary(1,programs));
    }
    private static void add(List<AbilityProgramDefinition> programs,String id,String source) { programs.add(new AbilityProgramDefinition(id,id,source,200,4096)); }
    private static boolean flag(LivingEntity actor,String program,String key) { return WorldAbilityRuntime.state(actor,program).get(key) instanceof AbilityValue.BoolValue b && b.getValue(); }
    private static double value(LivingEntity actor,String program,String key) { return WorldAbilityRuntime.state(actor,program).get(key) instanceof AbilityValue.NumberValue n ? n.getValue():0; }
    private static String text(LivingEntity actor,String program,String key) { return WorldAbilityRuntime.state(actor,program).get(key) instanceof AbilityValue.TextValue t ? t.getValue():""; }
    private static void check(boolean value,String message) { if(!value) throw new IllegalStateException(message); }
}

package com.wjz.worldsmith.gametest;

import com.mojang.authlib.GameProfile;
import com.wjz.worldsmith.ability.WorldAbilityRuntime;
import com.wjz.worldsmith.content.WorldContentRuntime;
import com.wjz.worldsmith.content.creature.CreatureEntity;
import com.wjz.worldsmith.content.item.CustomItemRuntime;
import com.wjz.worldsmith.content.interaction.WorldMechanicSavedData;
import com.wjz.worldsmith.content.quest.QuestProtocol;
import com.wjz.worldsmith.content.quest.server.QuestRuntime;
import com.wjz.worldsmith.content.story.*;
import com.wjz.worldsmith.core.ability.*;
import com.wjz.worldsmith.core.examples.ImmersiveVillageExample;
import com.wjz.worldsmith.core.model.WorldsmithPack;
import com.wjz.worldsmith.core.story.StoryFactRef;
import com.wjz.worldsmith.core.structure.BuildPos;
import com.wjz.worldsmith.core.structure.StructureGeometryCompiler;
import com.wjz.worldsmith.worldgen.CompiledPack;
import com.wjz.worldsmith.worldgen.WorldsmithStructureTemplates;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.*;
import java.util.function.BooleanSupplier;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.*;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ServerboundPlayerLoadedPacket;
import net.minecraft.server.level.*;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.clock.WorldClocks;
import net.minecraft.world.entity.*;
import net.minecraft.world.item.*;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.storage.*;
import net.minecraft.world.phys.*;

/** Two real authored villages, including native template rotation; choices are never written as fixture facts. */
public final class StoryRuntimeGameTests {
    @GameTest(environment="worldsmith_mechanics_smoke:story_journey",maxTicks=6000,skyAccess=true,padding=40)
    public void residentsChoicesTransactionsReloadAndReturnAreReal(GameTestHelper helper) throws Exception {
        var run=new Run(helper);
        try {run.begin();} catch(Throwable failure) {run.close();throw failure;}
        helper.onEachTick(() -> {try {run.tick();} catch(Throwable failure) {run.close();throw failure;}});
    }
    private record Step(String name,Runnable start,BooleanSupplier poll,int timeout) {}
    private record SavedEntity(EntityType<?> type,CompoundTag tag) {}
    private static final class Run {
        final GameTestHelper helper;
        final ServerLevel level;
        final WorldsmithPack pack=ImmersiveVillageExample.create();
        final List<Step> steps=new ArrayList<>();
        final Map<ChunkPos,Boolean> forced=new LinkedHashMap<>();
        final Map<String,UUID> beforeActors=new LinkedHashMap<>();
        final List<ServerPlayer> guests=new ArrayList<>();
        WorldContentRuntime.Prepared prepared;
        StorySavedData priorStory,reloaded;
        WorldMechanicSavedData priorMechanic;
        ServerPlayer player;
        ServerPlayer opposing;
        BlockPos base,lamp,wrongLamp;
        AABB bounds;
        BlockState wrongBefore;
        CompoundTag playerTag;
        List<SavedEntity> entitiesToReload=List.of();
        StoryProtocol.Action oldChoice;
        StorySavedData.Character deadKeeper;
        UUID oldKeeper;
        String stableKeeperKey;
        long initialGameTime,initialClock,nextAt,stepAt,deathAt,availableAt,conflictUntil;
        int scenario,stepIndex,storyNonce,questNonce;
        boolean bound,closed,started,capturedPriorState;
        Run(GameTestHelper helper){this.helper=helper;level=helper.getLevel();}
        void begin() throws Exception {
            com.wjz.worldsmith.worldgen.StoryWorldgenChecks.verify(java.nio.file.Path.of(System.getProperty("user.dir")).resolve("story-generation"));
            check(WorldContentRuntime.boundLevelCount()==0,"Story fixture overlaps another world binding");
            var names=new LinkedHashMap<String,String>();
            pack.getBiomes().getBiomes().forEach(b -> names.put(b.getId(),"worldsmith:generated/"+pack.getComputedId()+"/"+b.getId()));
            prepared=WorldContentRuntime.prepare(pack,names);
            priorStory=level.getServer().overworld().getDataStorage().get(StorySavedData.TYPE);
            priorMechanic=level.getDataStorage().get(WorldMechanicSavedData.TYPE);
            initialGameTime=level.getGameTime();initialClock=level.getOverworldClockTime();
            capturedPriorState=true;
            setup(0);
        }
        void setup(int index) {
            scenario=index;storyNonce=0;questNonce=0;stepIndex=0;started=false;steps.clear();beforeActors.clear();
            base=helper.absolutePos(new BlockPos(12+index*110,3,12));
            bounds=index==0?new AABB(Vec3.atLowerCornerOf(base.offset(-4,-2,-4)),Vec3.atLowerCornerOf(base.offset(30,15,68)))
                :new AABB(Vec3.atLowerCornerOf(base.offset(-68,-2,-4)),Vec3.atLowerCornerOf(base.offset(5,15,31)));
            for(int x=((int)Math.floor(bounds.minX)-12)>>4;x<=((int)Math.floor(bounds.maxX)+12)>>4;x++)
                for(int z=((int)Math.floor(bounds.minZ)-12)>>4;z<=((int)Math.floor(bounds.maxZ)+12)>>4;z++) {
                    var chunk=new ChunkPos(x,z);forced.putIfAbsent(chunk,level.getChunkSource().getForceLoadedChunks().contains(chunk.pack()));
                    level.setChunkForced(x,z,true);level.getChunk(x,z);
                }
            level.getServer().overworld().getDataStorage().set(StorySavedData.TYPE,new StorySavedData(StorySavedData.State.empty(pack.getComputedId())));
            level.getDataStorage().set(WorldMechanicSavedData.TYPE,new WorldMechanicSavedData());
            WorldContentRuntime.bindLevel(level,prepared);bound=true;clock(12000);
            // Direct template placement bypasses the generator's authored FILL foundation. Supply actual
            // native stone below this isolated flat fixture so its gravel path obeys normal gravity.
            for(int x=(int)Math.floor(bounds.minX);x<=(int)Math.floor(bounds.maxX);x++)
                for(int z=(int)Math.floor(bounds.minZ);z<=(int)Math.floor(bounds.maxZ);z++)
                    level.setBlock(new BlockPos(x,base.getY()-1,z),Blocks.STONE.defaultBlockState(),Block.UPDATE_ALL);
            var geometry=StructureGeometryCompiler.compile(pack.getStructures().getStructures().getFirst().getBlueprint());
            var nativeTemplate=new StructureTemplate();
            nativeTemplate.load(BuiltInRegistries.BLOCK,WorldsmithStructureTemplates.encode(geometry,level.registryAccess(),CompiledPack.scoped(pack)));
            var settings=new StructurePlaceSettings().setIgnoreEntities(false).setRotation(index==0?Rotation.NONE:Rotation.CLOCKWISE_90);
            check(nativeTemplate.placeInWorld(level,base,base,settings,level.getRandom(),Block.UPDATE_ALL),"Actual authored village failed native placement");
            var cookie=CommonListenerCookie.createInitial(new GameProfile(UUID.randomUUID(),"story-survival-"+index),false);
            player=new ServerPlayer(level.getServer(),level,cookie.gameProfile(),cookie.clientInformation());
            var connection=new Connection(PacketFlow.SERVERBOUND);new EmbeddedChannel(connection);
            level.getServer().getPlayerList().placeNewPlayer(connection,player,cookie);
            player.connection.handleAcceptPlayerLoad(new ServerboundPlayerLoadedPacket());
            player.setGameMode(GameType.SURVIVAL);player.setNoGravity(true);player.setInvulnerable(true);player.getInventory().clearContent();
            stand(at(ImmersiveVillageExample.ENTRY_POSITION));define();nextAt=level.getGameTime()+3;
        }
        void define() {
            add("native markers materialize exactly two places and three stable residents",() -> {},() -> {
                if(living().size()!=3||data().state().places().size()!=2||data().state().characters().size()!=3)return false;
                for(var resident:living())check(WorldStoryRuntime.characterKey(resident)!=null,"A spawned resident lacks marker-owned identity");
                check(place(ImmersiveVillageExample.VILLAGE).quarterTurns()==scenario,"Native template marker rotation was not retained");
                check(place(ImmersiveVillageExample.VILLAGE).position().equals(at(ImmersiveVillageExample.VILLAGE_POSITION)),"Actual native place differs from its transformed marker");
                lamp=place(ImmersiveVillageExample.VILLAGE).position().above(3).offset(rotate(0,0,2));
                check(lamp.equals(at(ImmersiveVillageExample.LAMP_POSITION)),"Story-local lamp offset diverges from the real rotated structure");
                wrongLamp=place(ImmersiveVillageExample.VILLAGE).position().offset(0,3,2);wrongBefore=level.getBlockState(wrongLamp);
                sync();
                var view=WorldStoryRuntime.journalSnapshot(player);
                check(view.places().stream().anyMatch(p -> p.id().equals(ImmersiveVillageExample.VILLAGE)),"Arrival did not discover the actual nearby village");
                check(view.places().stream().noneMatch(p -> p.id().equals(ImmersiveVillageExample.RUIN))&&view.knowledge().isEmpty(),"Arrival leaked unseen places or knowledge");
                var journal=QuestRuntime.journalSnapshot(player);
                check(journal.quests().size()==1&&journal.quests().getFirst().id().equals(ImmersiveVillageExample.INTRO),"Initial journal disclosed a future chapter");
                stand(place(ImmersiveVillageExample.VILLAGE).position().north(3));
                var route=StoryRouteProbe.probe(player,place(ImmersiveVillageExample.RUIN));
                System.out.println("[StoryRuntime] actual route feet="+player.position()+" startSupport="+level.getBlockState(player.blockPosition().below())
                    +" target="+place(ImmersiveVillageExample.RUIN).position()+" targetSupport="+level.getBlockState(place(ImmersiveVillageExample.RUIN).position().below()));
                check(route.status().name().equals("VERIFIED")&&route.path().size()>10,"Actual marker-to-marker walking route failed: "+route);
                System.out.println("[StoryRuntime] route "+scenario+" "+route.status()+" nodes="+route.visitedNodes()+" path="+route.path().size());
                return true;
            },500);
            add("day work is real native movement, not a displayed activity label",() -> clock(1000),() -> {
                var keeper=resident(ImmersiveVillageExample.KEEPER);
                var home=character(ImmersiveVillageExample.KEEPER).home();
                return keeper.position().distanceToSqr(Vec3.atBottomCenterOf(home))>2;
            },260);
            add("night routine returns home before residents hold a conversation",() -> clock(12000),() ->
                resident(ImmersiveVillageExample.KEEPER).position().distanceToSqr(Vec3.atBottomCenterOf(character(ImmersiveVillageExample.KEEPER).home()))<2,320);
            add("entity use opens filtered dialogue; forged option and retired token do not advance it",() -> {
                var keeper=resident(ImmersiveVillageExample.KEEPER);stand(keeper.blockPosition().offset(10,0,0));
                check(!WorldStoryRuntime.openDialogue(player,keeper),"Out-of-reach resident opened a session");
                open(ImmersiveVillageExample.KEEPER);
                check(view().choices().stream().noneMatch(c -> c.id().equals("after_loss")||c.id().startsWith("return_")),"Future/loss dialogue options leaked");
                choose("rekindle");check(view().nodeId().equals("greeting")&&!bool(ImmersiveVillageExample.MET_KEEPER),"Forged option changed a fact");
                var old=request("hear_history");WorldStoryRuntime.handle(player,old);
                check(view().nodeId().equals("history")&&bool(ImmersiveVillageExample.MET_KEEPER),"Actual dialogue did not set its fact");
                replay(old);check(view().nodeId().equals("history"),"Retired greeting token advanced the current node");
                choose("depart");check(WorldStoryRuntime.journalSnapshot(player).dialogue()==null,"Terminal choice retained a session");
                quest(ImmersiveVillageExample.INTRO,QuestProtocol.ActionKind.CLAIM);
                check(keys()==1&&player.getInventory().countItem(Items.BREAD)==3,"Native quest reward did not produce the exact initial key and bread");
            },() -> WorldStoryRuntime.journalSnapshot(player).knowledge().stream().anyMatch(k -> k.id().equals("keeper_account")),80);
            add("lost key trade enforces material and full-inventory preflight before facts",() -> {
                int keySlot=keySlot();check(keySlot>=0,"The earned key was missing before loss");
                var lost=player.drop(player.getInventory().removeItem(keySlot,1),false,false);
                check(lost!=null&&keys()==0,"Actual key loss did not remove the carried reward");lost.discard();
                open(ImmersiveVillageExample.ARTISAN);
                var terms=view().choices().stream().filter(c -> c.id().equals("replace_key")).findFirst().orElseThrow().details();
                check(terms.contains("3"),"Dialogue omitted canonical material costs");
                choose("replace_key");check(keys()==0&&number("keys_recast")==0&&view().nodeId().equals("greeting"),"Missing materials partially committed the trade");
                var chest=(Container)level.getBlockEntity(at(ImmersiveVillageExample.SUPPLIES_POSITION));
                check(chest!=null&&chest.getItem(0).is(Items.COPPER_INGOT),"Actual supply chest was not exported");
                var copper=chest.removeItem(0,4);check(copper.getCount()==4,"Authored copper supply could not cover the transaction fixture");
                for(int i=0;i<36;i++)player.getInventory().setItem(i,new ItemStack(Items.STONE,64));
                player.getInventory().setItem(0,copper);
                choose("replace_key");
                check(player.getInventory().countItem(Items.COPPER_INGOT)==4&&keys()==0&&number("keys_recast")==0,"Full inventory partially charged materials or trade history");
                var retained=player.getInventory().removeItem(0,4);player.getInventory().clearContent();player.getInventory().setItem(0,retained);
                var action=request("replace_key");WorldStoryRuntime.handle(player,action);
                check(keys()==1&&player.getInventory().countItem(Items.COPPER_INGOT)==1&&number("keys_recast")==1&&view().nodeId().equals("recast"),"Atomic recovery trade produced incorrect actual costs/output/facts");
                replay(action);check(keys()==1&&player.getInventory().countItem(Items.COPPER_INGOT)==1&&number("keys_recast")==1,"Old trade token replayed rewards or material costs");
                choose("thank");
            },() -> true,80);
            add("a resident's legend remains attributed and unrevealed endings stay absent",() -> {
                open(ImmersiveVillageExample.FORAGER);choose("listen");choose("thank");
            },() -> {
                var knowledge=WorldStoryRuntime.journalSnapshot(player).knowledge();
                if(knowledge.stream().noneMatch(k -> k.id().equals("forest_legend")))return false;
                check(knowledge.stream().filter(k -> k.id().equals("forest_legend")).allMatch(k -> k.truth().equals("LEGEND")),"A belief was promoted to a world fact");
                check(knowledge.stream().noneMatch(k -> k.id().startsWith("living_")),"Unchosen ending knowledge leaked");
                return true;
            },80);
            add("actual arrival unlocks the ruin record and the player's explicit branch",() -> {
                stand(place(ImmersiveVillageExample.RUIN).position());
                var before=data().state();
                player.setGameMode(GameType.SPECTATOR);sync();
                check(data().state()==before&&!bool(ImmersiveVillageExample.RUIN_SEEN)
                    &&WorldStoryRuntime.journalSnapshot(player).places().stream().noneMatch(p -> p.id().equals(ImmersiveVillageExample.RUIN)),
                    "Spectator SYNC bypassed authoritative discovery filters");
                player.setGameMode(GameType.SURVIVAL);player.setInvulnerable(true);sync();
                check(bool(ImmersiveVillageExample.RUIN_SEEN),"Arrival at the actual ruin did not execute onDiscover");
                quest(ImmersiveVillageExample.ROAD,QuestProtocol.ActionKind.CLAIM);
                quest(branch(),QuestProtocol.ActionKind.ACCEPT);
                check(number(ImmersiveVillageExample.ROUTE)==scenario+1,"Explicit quest acceptance did not select the promised story route");
            },() -> WorldStoryRuntime.journalSnapshot(player).knowledge().stream().anyMatch(k -> k.id().equals("beacon_record")),80);
            if(scenario==0)add("another real player accepts the opposite promise before the shared world settles",() -> {
                opposing=guest("story-opposite");
                asPlayer(opposing,() -> {beginGuestJourney(ImmersiveVillageExample.REMEMBER);check(number(ImmersiveVillageExample.ROUTE)==2&&number(ImmersiveVillageExample.RESOLUTION)==0,"Opposing promise was not accepted in the same unresolved world");});
            },() -> true,80);
            add("distance invalidation and filtered choices protect the real ending trade",() -> {
                open(ImmersiveVillageExample.KEEPER);choose("discuss_beacon");
                String chosen=scenario==0?"rekindle":"remember",other=scenario==0?"remember":"rekindle";
                check(view().choices().stream().anyMatch(c -> c.id().equals(chosen))&&view().choices().stream().noneMatch(c -> c.id().equals(other)),"Dialogue did not filter the unaccepted alternative");
                choose(other);check(number(ImmersiveVillageExample.RESOLUTION)==0&&keys()==1,"Forged unchosen option consumed a key or changed the world");
                var stale=request(chosen);stand(resident(ImmersiveVillageExample.KEEPER).blockPosition().offset(10,0,0));
                WorldStoryRuntime.handle(player,stale);
                check(WorldStoryRuntime.journalSnapshot(player).dialogue()==null&&number(ImmersiveVillageExample.RESOLUTION)==0&&keys()==1,"Out-of-reach choice committed its transaction");
                open(ImmersiveVillageExample.KEEPER);choose("discuss_beacon");oldChoice=request(chosen);WorldStoryRuntime.handle(player,oldChoice);
                check(number(ImmersiveVillageExample.RESOLUTION)==scenario+1&&keys()==0,"Real ending choice did not atomically consume its actual key and set the world resolution");
                // Interrupt before the first program/projection tick. A player's cosmetic lifetime must not own the outcome.
                WorldAbilityRuntime.cancelOwner(player);
                check(number(ImmersiveVillageExample.APPLIED)==0&&level.getBlockState(lamp).is(Blocks.POLISHED_DEEPSLATE),"A queued invocation was mistaken for a completed physical outcome");
                // Fault fixture: one block reached desired storage, the other did not, and no receipt was saved.
                level.setBlock(lamp.below(),Blocks.CHISELED_STONE_BRICKS.defaultBlockState(),Block.UPDATE_ALL);
                var encoded=StorySavedData.CODEC.encodeStart(NbtOps.INSTANCE,data()).getOrThrow();
                var pending=StorySavedData.CODEC.parse(NbtOps.INSTANCE,encoded).getOrThrow();
                check(pending.state().projections().isEmpty(),"An unfinished batch already had a receipt");
                WorldContentRuntime.unbindLevel(level);bound=false;
                level.getServer().overworld().getDataStorage().set(StorySavedData.TYPE,pending);
                WorldContentRuntime.bindLevel(level,prepared);bound=true;
                if(scenario==1) {level.setBlock(lamp,Blocks.GOLD_BLOCK.defaultBlockState(),Block.UPDATE_ALL);conflictUntil=level.getGameTime()+40;}
            },() -> {
                if(scenario==1&&conflictUntil>0) {
                    check(level.getBlockState(lamp).is(Blocks.GOLD_BLOCK)&&number(ImmersiveVillageExample.APPLIED)==0,"A conflicting player edit was overwritten or acknowledged");
                    check(StoryProjections.inspect(level,place(ImmersiveVillageExample.VILLAGE).instance(),"beacon_2").status()==StoryProjections.Status.CONFLICT,"Conflict inspection did not preserve its real target evidence");
                    if(level.getGameTime()<conflictUntil)return false;
                    level.setBlock(lamp,Blocks.POLISHED_DEEPSLATE.defaultBlockState(),Block.UPDATE_ALL);conflictUntil=0;return false;
                }
                if(!level.getBlockState(lamp).is(scenario==0?Blocks.SEA_LANTERN:Blocks.AMETHYST_BLOCK))return false;
                if(scenario==1)check(level.getBlockState(wrongLamp).equals(wrongBefore),"Rotated programme modified an unrotated guessed coordinate");
                check(number(ImmersiveVillageExample.APPLIED)==scenario+1&&applications()==1&&level.getBlockState(lamp.below()).is(Blocks.CHISELED_STONE_BRICKS),"Partial desired-state recovery did not acknowledge the complete batch exactly once");
                check(data().state().projections().contains(new StorySavedData.ProjectionReceipt(place(ImmersiveVillageExample.VILLAGE).instance(),"beacon_"+(scenario+1))),"Physical outcome has no persistent actual-place receipt");
                return true;
            },180);
            add("ending token is single-use and the return changes journal knowledge",() -> {
                int output=player.getInventory().countItem(scenario==0?Items.LANTERN:Items.AMETHYST_SHARD);
                replay(oldChoice);check(number(ImmersiveVillageExample.RESOLUTION)==scenario+1&&player.getInventory().countItem(scenario==0?Items.LANTERN:Items.AMETHYST_SHARD)==output,"Retired ending token duplicated a reward");
                open(ImmersiveVillageExample.KEEPER);
                check(view().choices().stream().noneMatch(c -> c.id().startsWith("return_")),"Return-after-claim dialogue was offered before the actual claim");
                choose("leave");quest(branch(),QuestProtocol.ActionKind.CLAIM);
                open(ImmersiveVillageExample.KEEPER);choose(scenario==0?"return_light":"return_quiet");
                check(bool(ImmersiveVillageExample.RETURNED),"Return dialogue did not remember the world outcome");choose("farewell");
                quest(ImmersiveVillageExample.RETURN,QuestProtocol.ActionKind.CLAIM);
                check(QuestRuntime.journalSnapshot(player).campaignComplete()&&player.getInventory().countItem(Items.CLOCK)==1,"ANY quest merge did not complete with its exact reward");
            },() -> {
                String expected=scenario==0?"living_light":"living_memory",hidden=scenario==0?"living_memory":"living_light";
                var records=WorldStoryRuntime.journalSnapshot(player).knowledge();
                if(records.stream().noneMatch(k -> k.id().equals(expected)))return false;
                check(records.stream().noneMatch(k -> k.id().equals(hidden)),"Unchosen world outcome became known");
                return !WorldAbilityRuntime.hasActiveProgram(player);
            },180);
            if(scenario==0)add("opposing and late players personally witness the outcome without changing promises or paying twice",() -> {
                asPlayer(opposing,() -> finishGuestJourney(ImmersiveVillageExample.REMEMBER,2));
                var late=guest("late-traveler");
                asPlayer(late,() -> {beginGuestJourney(ImmersiveVillageExample.REMEMBER);finishGuestJourney(ImmersiveVillageExample.REMEMBER,2);});
                check(number(ImmersiveVillageExample.RESOLUTION)==1&&applications()==1,"Guest journeys repeated or reversed the global result");
            },() -> true,100);
            add("serialize actual story store, native player and marker-owned residents",() -> {
                for(var actor:living())beforeActors.put(WorldStoryRuntime.characterDefinition(actor).getId(),actor.getUUID());
                var output=TagValueOutput.createWithContext(ProblemReporter.DISCARDING,level.registryAccess());player.saveWithoutId(output);playerTag=output.buildResult();
                var encoded=StorySavedData.CODEC.encodeStart(NbtOps.INSTANCE,data()).getOrThrow();
                reloaded=StorySavedData.CODEC.parse(NbtOps.INSTANCE,encoded).getOrThrow();
                check(reloaded.state().equals(data().state()),"Native story store codec lost committed history");
                var saved=new ArrayList<SavedEntity>();
                for(Entity actor:ownedEntities()) {
                    var entityOut=TagValueOutput.createWithContext(ProblemReporter.DISCARDING,level.registryAccess());actor.saveWithoutId(entityOut);
                    saved.add(new SavedEntity(actor.getType(),entityOut.buildResult()));actor.discard();
                }
                entitiesToReload=List.copyOf(saved);WorldContentRuntime.unbindLevel(level);bound=false;
            },() -> ownedEntities().isEmpty(),60);
            add("native reload preserves identity, debt, knowledge, choice and once-only rewards",() -> {
                level.getServer().overworld().getDataStorage().set(StorySavedData.TYPE,reloaded);
                WorldContentRuntime.bindLevel(level,prepared);bound=true;
                player.load(TagValueInput.create(ProblemReporter.DISCARDING,level.registryAccess(),playerTag));
                for(var saved:entitiesToReload) {
                    var actor=saved.type().create(level,EntitySpawnReason.TRIGGERED);check(actor!=null,"Saved actor type did not create");
                    actor.load(TagValueInput.create(ProblemReporter.DISCARDING,level.registryAccess(),saved.tag()));
                    check(level.addFreshEntity(actor),"Native saved actor/marker UUID could not reload");
                }
            },() -> {
                if(living().size()!=3||ownedEntities().stream().filter(e -> e instanceof Marker).count()!=5)return false;
                for(var actor:living())check(actor.getUUID().equals(beforeActors.get(WorldStoryRuntime.characterDefinition(actor).getId())),"Marker reload minted a duplicate resident identity");
                check(number(ImmersiveVillageExample.RESOLUTION)==scenario+1&&number("keys_recast")==1&&number(ImmersiveVillageExample.ROUTE)==scenario+1,"Reload reset a consequence or trade debt");
                check(keys()==0&&player.getInventory().countItem(Items.CLOCK)==1&&QuestRuntime.journalSnapshot(player).campaignComplete(),"Player reload reissued a quest reward or lost the completed route");
                check(applications()==1&&data().state().projections().size()==1,"Reload repeated an acknowledged projection");
                check(level.getBlockState(lamp).is(scenario==0?Blocks.SEA_LANTERN:Blocks.AMETHYST_BLOCK)
                    &&level.getBlockState(lamp.below()).is(Blocks.CHISELED_STONE_BRICKS),"Acknowledged static scene disappeared after native neighbour updates/ticks and reload");
                quest(ImmersiveVillageExample.RETURN,QuestProtocol.ActionKind.CLAIM);replay(oldChoice);
                check(player.getInventory().countItem(Items.CLOCK)==1&&keys()==0,"Reload allowed a retired choice or claimed quest to pay again");
                return true;
            },180);
            add("completed receipts preserve later player edits across runtime rebinding",() -> {
                level.setBlock(lamp,Blocks.GOLD_BLOCK.defaultBlockState(),Block.UPDATE_ALL);
                WorldContentRuntime.unbindLevel(level);bound=false;WorldContentRuntime.bindLevel(level,prepared);bound=true;
            },() -> {
                check(level.getBlockState(lamp).is(Blocks.GOLD_BLOCK)&&applications()==1,"A completed receipt overwrote a later player edit");
                check(StoryProjections.inspect(level,place(ImmersiveVillageExample.VILLAGE).instance(),"beacon_"+(scenario+1)).status()==StoryProjections.Status.ALREADY_APPLIED,"Completed receipt was not authoritative after rebind");
                return level.getGameTime()-stepAt>=35;
            },70);
            if(scenario==0) {
                add("native resident death records history and waits the full declared 1200 ticks",() -> {
                    var keeper=resident(ImmersiveVillageExample.KEEPER);oldKeeper=keeper.getUUID();stableKeeperKey=WorldStoryRuntime.characterKey(keeper);
                    deathAt=level.getGameTime();keeper.invulnerableTime=0;
                    check(keeper.hurtServer(level,player.damageSources().playerAttack(player),1000)&&!keeper.isAlive(),"Real resident damage did not produce a native death");
                    deadKeeper=character(ImmersiveVillageExample.KEEPER);availableAt=deadKeeper.availableAt();
                    check(deadKeeper.dead()&&availableAt==deathAt+1200&&bool("keeper_was_lost"),"Death did not preserve the exact declared return deadline and onDeath history");
                },() -> {
                    long now=level.getGameTime();
                    var keepers=living().stream().filter(e -> e.creatureId().equals("keeper_resident")).toList();
                    if(now<availableAt) {check(keepers.isEmpty(),"A resident respawned before its authored 1200-tick deadline");return false;}
                    if(keepers.isEmpty())return false;
                    check(keepers.size()==1&&!keepers.getFirst().getUUID().equals(oldKeeper)&&stableKeeperKey.equals(WorldStoryRuntime.characterKey(keepers.getFirst())),"Return replaced the owner anchor or duplicated its resident");
                    check(bool("keeper_was_lost")&&QuestRuntime.journalSnapshot(player).campaignComplete(),"Return forgot the death or reset the player's chosen journey");
                    return true;
                },1350);
                add("a returned resident acknowledges loss instead of silently restarting the story",() -> {
                    open(ImmersiveVillageExample.KEEPER);
                    check(view().choices().stream().anyMatch(c -> c.id().equals("after_loss")),"Returned resident did not offer its recorded-loss conversation");
                    choose("after_loss");check(view().nodeId().equals("stone_oath")&&bool("heard_after_loss"),"Acknowledged loss did not commit its actual dialogue history");choose("continue");
                    check(player.getInventory().countItem(Items.CLOCK)==1&&number(ImmersiveVillageExample.ROUTE)==1,"Death conversation replayed old rewards or reset the route");
                },() -> true,60);
            }
        }
        void tick() {
            if(closed||level.getGameTime()<nextAt)return;
            if(stepIndex>=steps.size()) {
                finishCase();
                if(scenario==0) {setup(1);return;}
                System.out.println("[StoryRuntime] PASS two native village outcomes + transformed marker/program coordinates + authority/trades + persistence + actual 1200-tick resident return");
                close();helper.succeed();return;
            }
            var step=steps.get(stepIndex);long now=level.getGameTime();
            if(!started) {started=true;stepAt=now;step.start().run();}
            if(now-stepAt>step.timeout())throw new IllegalStateException("Story scenario "+scenario+" timed out: "+step.name()+"; "+WorldStoryRuntime.journalSnapshot(player));
            if(now-stepAt>=2&&step.poll().getAsBoolean()) {
                System.out.println("[StoryRuntime] scenario "+scenario+" PASS "+step.name());stepIndex++;started=false;nextAt=now+25;
            }
        }
        void add(String name,Runnable start,BooleanSupplier poll,int timeout){steps.add(new Step(name,start,poll,timeout));}
        ServerPlayer guest(String name) {
            var cookie=CommonListenerCookie.createInitial(new GameProfile(UUID.randomUUID(),name),false);
            var guest=new ServerPlayer(level.getServer(),level,cookie.gameProfile(),cookie.clientInformation());
            var connection=new Connection(PacketFlow.SERVERBOUND);new EmbeddedChannel(connection);
            level.getServer().getPlayerList().placeNewPlayer(connection,guest,cookie);guest.connection.handleAcceptPlayerLoad(new ServerboundPlayerLoadedPacket());
            guest.setGameMode(GameType.SURVIVAL);guest.setNoGravity(true);guest.setInvulnerable(true);guest.getInventory().clearContent();guests.add(guest);return guest;
        }
        void asPlayer(ServerPlayer guest,Runnable body) {var previous=player;player=guest;try {body.run();} finally {player=previous;}}
        void beginGuestJourney(String choice) {
            stand(at(ImmersiveVillageExample.ENTRY_POSITION));sync();open(ImmersiveVillageExample.KEEPER);choose("hear_history");choose("depart");
            quest(ImmersiveVillageExample.INTRO,QuestProtocol.ActionKind.CLAIM);
            stand(place(ImmersiveVillageExample.RUIN).position());sync();quest(ImmersiveVillageExample.ROAD,QuestProtocol.ActionKind.CLAIM);quest(choice,QuestProtocol.ActionKind.ACCEPT);
            check(keys()==1,"A guest did not actually earn their own key");stand(at(ImmersiveVillageExample.ENTRY_POSITION));sync();
        }
        void finishGuestJourney(String choice,int promise) {
            var entry=QuestRuntime.journalSnapshot(player).quests().stream().filter(q -> q.id().equals(choice)).findFirst().orElseThrow();
            check(entry.status()==QuestProtocol.Status.ACTIVE&&!bool(ImmersiveVillageExample.WITNESSED),"An opposing/late promise was locked or completed by a global fact alone");
            open(ImmersiveVillageExample.KEEPER);choose("witness_light");choose("acknowledge");choose("close");
            check(bool(ImmersiveVillageExample.WITNESSED)&&number(ImmersiveVillageExample.ROUTE)==promise&&keys()==1&&!bool(ImmersiveVillageExample.OATH_GIVEN),"Witnessing rewrote the promise or charged a second oath");
            check(player.getInventory().countItem(Items.LANTERN)==0,"A guest received the original transaction output");
            quest(choice,QuestProtocol.ActionKind.CLAIM);open(ImmersiveVillageExample.KEEPER);choose("return_light");choose("farewell");quest(ImmersiveVillageExample.RETURN,QuestProtocol.ActionKind.CLAIM);
            check(QuestRuntime.journalSnapshot(player).campaignComplete()&&player.getInventory().countItem(Items.CLOCK)==1&&keys()==1,"Guest witness path did not finish its actual personal campaign");
            quest(ImmersiveVillageExample.RETURN,QuestProtocol.ActionKind.CLAIM);check(player.getInventory().countItem(Items.CLOCK)==1,"Guest replay duplicated a final reward");
            stand(at(ImmersiveVillageExample.ENTRY_POSITION));
        }
        double applications(){return ((AbilityValue.NumberValue)WorldStoryRuntime.value(player,new StoryFactRef("beacon_applications",ImmersiveVillageExample.VILLAGE))).getValue();}
        StorySavedData data(){return level.getServer().overworld().getDataStorage().get(StorySavedData.TYPE);}
        StorySavedData.Place place(String id){return data().state().places().values().stream().filter(p -> p.definition().equals(id)).findFirst().orElseThrow();}
        StorySavedData.Character character(String id){return data().state().characters().values().stream().filter(p -> p.definition().equals(id)).findFirst().orElseThrow();}
        List<CreatureEntity> living(){return level.getEntitiesOfClass(CreatureEntity.class,bounds.inflate(20)).stream().filter(e -> e.isAlive()&&!e.isRemoved()&&e.bundleHash().equals(pack.getComputedId())).toList();}
        CreatureEntity resident(String id){return living().stream().filter(e -> e.creatureId().equals(id+"_resident")).findFirst().orElseThrow();}
        List<Entity> ownedEntities() {
            var out=new ArrayList<Entity>();
            out.addAll(level.getEntitiesOfClass(Marker.class,bounds.inflate(20)).stream().filter(e -> e.entityTags().contains("worldsmith.scope."+pack.getComputedId())).toList());
            out.addAll(level.getEntitiesOfClass(CreatureEntity.class,bounds.inflate(20)).stream().filter(e -> e.bundleHash().equals(pack.getComputedId())).toList());return out;
        }
        void stand(BlockPos point){
            var support=point.below();var shape=level.getBlockState(support).getCollisionShape(level,support);
            double y=shape.isEmpty()?point.getY():support.getY()+shape.max(net.minecraft.core.Direction.Axis.Y);
            player.snapTo(point.getX()+.5,y,point.getZ()+.5,0,0);player.setDeltaMovement(Vec3.ZERO);
        }
        void near(CreatureEntity actor) {
            for(var offset:List.of(new BlockPos(2,0,0),new BlockPos(-2,0,0),new BlockPos(0,0,2),new BlockPos(0,0,-2),new BlockPos(1,0,0))) {
                stand(new BlockPos((int)Math.floor(actor.getX()),(int)Math.ceil(actor.getY()),(int)Math.floor(actor.getZ())).offset(offset));
                if(level.noCollision(player,player.getBoundingBox())&&player.hasLineOfSight(actor)&&player.distanceToSqr(actor)<=25)return;
            }
            throw new IllegalStateException("No collision-free conversation approach to "+actor.creatureId());
        }
        void open(String id) {
            var actor=resident(id);near(actor);
            check(UseEntityCallback.EVENT.invoker().interact(player,level,InteractionHand.MAIN_HAND,actor,new EntityHitResult(actor,actor.getEyePosition())).consumesAction(),"Native main-hand entity interaction did not open "+id);
            check(WorldStoryRuntime.journalSnapshot(player).dialogue()!=null,"Entity interaction has no authoritative dialogue snapshot");
        }
        StoryProtocol.Dialogue view(){return Objects.requireNonNull(WorldStoryRuntime.journalSnapshot(player).dialogue(),"Expected a current dialogue");}
        StoryProtocol.Action request(String option) {
            var snapshot=WorldStoryRuntime.journalSnapshot(player);var dialogue=Objects.requireNonNull(snapshot.dialogue());
            return new StoryProtocol.Action(pack.getComputedId(),StoryProtocol.ActionKind.CHOOSE,++storyNonce,snapshot.revision(),dialogue.token(),dialogue.actor(),option);
        }
        void choose(String option){WorldStoryRuntime.handle(player,request(option));}
        void replay(StoryProtocol.Action old){WorldStoryRuntime.handle(player,new StoryProtocol.Action(old.scope(),old.kind(),++storyNonce,old.expectedRevision(),old.token(),old.actor(),old.optionId()));}
        void sync(){WorldStoryRuntime.handle(player,new StoryProtocol.Action(pack.getComputedId(),StoryProtocol.ActionKind.SYNC,++storyNonce,WorldStoryRuntime.revision(level),null,null,""));}
        void quest(String id,QuestProtocol.ActionKind kind){var snapshot=QuestRuntime.journalSnapshot(player);QuestRuntime.handle(player,new QuestProtocol.Action(snapshot.scope(),id,kind,++questNonce,snapshot.revision()));}
        String branch(){return scenario==0?ImmersiveVillageExample.REKINDLE:ImmersiveVillageExample.REMEMBER;}
        boolean bool(String id){return WorldStoryRuntime.value(player,new StoryFactRef(id)) instanceof AbilityValue.BoolValue b&&b.getValue();}
        double number(String id){return ((AbilityValue.NumberValue)WorldStoryRuntime.value(player,new StoryFactRef(id))).getValue();}
        int keySlot(){for(int i=0;i<36;i++){var identity=player.getInventory().getItem(i).get(CustomItemRuntime.identityComponent());if(identity!=null&&identity.itemId().equals(ImmersiveVillageExample.KEY))return i;}return -1;}
        int keys(){int result=0;for(int i=0;i<36;i++){var stack=player.getInventory().getItem(i);var identity=stack.get(CustomItemRuntime.identityComponent());if(identity!=null&&identity.itemId().equals(ImmersiveVillageExample.KEY))result+=stack.getCount();}return result;}
        BlockPos rotate(int x,int y,int z){return scenario==0?new BlockPos(x,y,z):new BlockPos(-z,y,x);}
        BlockPos at(BuildPos local){return base.offset(rotate(local.getX(),local.getY(),local.getZ()));}
        void clock(long value){level.getServer().clockManager().setTotalTicks(level.registryAccess().get(WorldClocks.OVERWORLD).orElseThrow(),value);}
        void finishCase() {
            if(player!=null)WorldAbilityRuntime.cancelOwner(player);
            for(var guest:guests){WorldAbilityRuntime.cancelOwner(guest);level.getServer().getPlayerList().remove(guest);guest.discard();}guests.clear();opposing=null;
            for(var actor:ownedEntities()){if(actor instanceof CreatureEntity c)WorldAbilityRuntime.cancelOwner(c);actor.discard();}
            if(player!=null){level.getServer().getPlayerList().remove(player);player.discard();player=null;}
            if(bound){WorldContentRuntime.unbindLevel(level);bound=false;}
        }
        void close() {
            if(closed)return;closed=true;
            if(bounds!=null)finishCase();
            if(!capturedPriorState)return;
            if(priorStory!=null)level.getServer().overworld().getDataStorage().set(StorySavedData.TYPE,priorStory);
            else level.getServer().overworld().getDataStorage().set(StorySavedData.TYPE,new StorySavedData(StorySavedData.State.empty(pack.getComputedId())));
            level.getDataStorage().set(WorldMechanicSavedData.TYPE,priorMechanic==null?new WorldMechanicSavedData():priorMechanic);
            if(initialGameTime!=0)clock(initialClock+Math.max(0,level.getGameTime()-initialGameTime));
            forced.forEach((chunk,was) -> level.setChunkForced(chunk.x(),chunk.z(),was));
        }
    }
    private static void check(boolean value,String message){if(!value)throw new IllegalStateException(message);}
}

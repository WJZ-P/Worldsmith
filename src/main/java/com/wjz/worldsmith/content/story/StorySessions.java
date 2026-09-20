package com.wjz.worldsmith.content.story;

import com.wjz.worldsmith.Worldsmith;
import com.wjz.worldsmith.ability.WorldAbilityRuntime;
import com.wjz.worldsmith.content.WorldInventoryTransaction;
import com.wjz.worldsmith.content.WorldRewardItems;
import com.wjz.worldsmith.content.creature.CreatureEntity;
import com.wjz.worldsmith.content.item.CustomItemRuntime;
import com.wjz.worldsmith.core.ability.*;
import com.wjz.worldsmith.core.story.*;
import java.util.*;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.world.entity.*;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

/** One-use, server-issued dialogue sessions. Inventory, facts and trade debt commit before any queued program runs. */
final class StorySessions {
    private static final Map<UUID,Session> SESSIONS=new HashMap<>();
    private static final Map<ServerPlayer,StoryRequestGate> GATES=new WeakHashMap<>();
    private static final Map<ServerPlayer,StoryProtocol.Snapshot> SENT=new WeakHashMap<>();
    private static final int MAX_SESSIONS=64;
    private StorySessions() {}
    private static final class Session {
        final ServerPlayer player;final ServerLevel level;final UUID actor,character;final String dialogue;
        String node;UUID token=UUID.randomUUID();long revision,until;
        Session(ServerPlayer player,Entity actor,StorySavedData.Character character,String dialogue,String node,long revision) {
            this.player=player;this.level=(ServerLevel)player.level();this.actor=actor.getUUID();this.character=character.anchor();this.dialogue=dialogue;this.node=node;
            this.revision=revision;until=level.getGameTime()+12000;
        }
        void rotate(long revision){token=UUID.randomUUID();this.revision=revision;until=level.getGameTime()+12000;}
    }
    private static final class Rejected extends RuntimeException {
        final StoryProtocol.Feedback feedback;
        Rejected(StoryProtocol.Feedback feedback,String message){super(message);this.feedback=feedback;}
    }
    static boolean open(ServerPlayer player,Entity actor) {
        if(!(actor instanceof CreatureEntity creature) || !near(player,actor))return false;
        var bound=WorldStoryRuntime.WORLDS.get((ServerLevel)player.level());if(bound==null)return false;
        var character=StoryCharacters.record(bound,creature);if(character==null||character.dead())return false;
        var definition=bound.snapshot.characters.get(character.definition());
        var dialogue=definition.getDialogue()==null?null:bound.snapshot.dialogues.get(definition.getDialogue());if(dialogue==null)return false;
        WorldStoryRuntime.discover(player);
        var context=context(player,creature,character,bound);
        var start=node(dialogue,dialogue.getStart());
        if(!WorldStoryRuntime.test(context,start.getCondition()))return false;
        if(SESSIONS.size()>=MAX_SESSIONS&&!SESSIONS.containsKey(player.getUUID()))return false;
        if(!StoryCharacters.beginConversation(creature))return false;
        var session=new Session(player,actor,character,dialogue.getId(),start.getId(),bound.store().state().revision());
        SESSIONS.put(player.getUUID(),session);
        send(player,0,StoryProtocol.Feedback.NONE,"");return true;
    }
    static void action(ServerPlayer player,StoryProtocol.Action action) {
        WorldStoryRuntime.requireThread((ServerLevel)player.level());
        var gate=GATES.computeIfAbsent(player,ignored -> new StoryRequestGate());
        // Scope errors and duplicate nonces consume this budget too. Over-budget requests receive no large reply.
        if(!gate.allowRequest(player.level().getGameTime()))return;
        var bound=WorldStoryRuntime.WORLDS.get((ServerLevel)player.level());
        if(bound==null||!bound.snapshot.scope().equals(action.scope())) {send(player,action.requestId(),StoryProtocol.Feedback.UNAVAILABLE,"这段见闻不属于当前世界。");return;}
        if(!gate.advanceNonce(action.requestId())){send(player,action.requestId(),StoryProtocol.Feedback.EXPIRED,"这个选择已经处理，请查看当前对话。");return;}
        if(action.kind()==StoryProtocol.ActionKind.SYNC){WorldStoryRuntime.discover(player);send(player,action.requestId(),StoryProtocol.Feedback.NONE,"");return;}
        var session=SESSIONS.get(player.getUUID());
        if(session==null||session.player!=player||!session.actor.equals(action.actor())||!session.token.equals(action.token())) {
            send(player,action.requestId(),StoryProtocol.Feedback.EXPIRED,"对话已经变化，请使用当前选项。");return;
        }
        if(action.kind()==StoryProtocol.ActionKind.CLOSE){SESSIONS.remove(player.getUUID(),session);send(player,action.requestId(),StoryProtocol.Feedback.NONE,"");return;}
        var context=valid(session);
        if(context==null){SESSIONS.remove(player.getUUID(),session);send(player,action.requestId(),StoryProtocol.Feedback.EXPIRED,"对方已经离开，请靠近后再交谈。");return;}
        if(action.expectedRevision()!=session.revision||session.revision!=bound.store().state().revision()) {
            session.rotate(bound.store().state().revision());send(player,action.requestId(),StoryProtocol.Feedback.STALE_REVISION,"事情有了新变化，请重新选择。");return;
        }
        try {
            var dialogue=bound.snapshot.dialogues.get(session.dialogue);var current=node(dialogue,session.node);
            if(!WorldStoryRuntime.test(context,current.getCondition()))throw new Rejected(StoryProtocol.Feedback.LOCKED,"这段话现在已有不同的答案。");
            var option=current.getOptions().stream().filter(o -> o.getId().equals(action.optionId())).findFirst().orElseThrow(() -> new Rejected(StoryProtocol.Feedback.EXPIRED,"这项回答已经不在当前对话中。"));
            if(!WorldStoryRuntime.test(context,option.getCondition()))throw new Rejected(StoryProtocol.Feedback.LOCKED,"现在还不满足这项选择的条件。");
            choose(context,session,option);
            send(player,action.requestId(),StoryProtocol.Feedback.CHANGED,"");
        } catch(Rejected rejected){send(player,action.requestId(),rejected.feedback,rejected.getMessage());}
        catch(RuntimeException failure){Worldsmith.LOGGER.error("Story choice failed for {}",player.getUUID(),failure);send(player,action.requestId(),StoryProtocol.Feedback.ERROR,"这次交谈未能完成，请稍后再试。");}
    }
    private static void choose(WorldStoryRuntime.Context context,Session session,StoryDialogueOption option) {
        var player=context.player();var bound=context.bound();var changes=new ArrayList<>(option.getChanges());
        StoryTrade trade=option.getTrade()==null?null:bound.snapshot.trades.get(option.getTrade());
        var memory=bound.store().state().player(player.getUUID());var inventory=new WorldInventoryTransaction(player);
        if(trade!=null) {
            if(!WorldStoryRuntime.test(context,trade.getCondition()))throw new Rejected(StoryProtocol.Feedback.LOCKED,"这项交换目前尚未开放。");
            var used=memory.trades().getOrDefault(trade.getId(),new StorySavedData.TradeUse(0,0));
            if(used.readyAt()>bound.level.getGameTime())throw new Rejected(StoryProtocol.Feedback.COOLDOWN,"对方还在准备，请稍后再来。");
            if(trade.getMaxUsesPerPlayer()>0&&used.count()>=trade.getMaxUsesPerPlayer())throw new Rejected(StoryProtocol.Feedback.LOCKED,"这项约定已经完成。");
            for(var input:trade.getInputs()) {
                ItemStack prototype=WorldRewardItems.stack(input.getItem(),1,bound.snapshot.blocks,bound.snapshot.items);
                if(inventory.consume(stack -> matches(bound,stack,input.getItem(),prototype),input.getCount())!=input.getCount())
                    throw new Rejected(StoryProtocol.Feedback.NO_MATERIALS,"你带来的材料还不够；没有扣除物品。");
            }
            for(var output:trade.getOutputs())if(!inventory.insert(WorldRewardItems.stack(output.getItem(),output.getCount(),bound.snapshot.blocks,bound.snapshot.items)))
                throw new Rejected(StoryProtocol.Feedback.NO_SPACE,"背包放不下这些物品；请先腾出空间。");
            changes.addAll(trade.getChanges());
        }
        var facts=WorldStoryRuntime.prepareChanges(context,changes);
        if(trade!=null)facts.player(player.getUUID(),memory.trade(trade.getId(),Math.addExact(bound.level.getGameTime(),trade.getCooldownTicks())));
        WorldAbilityRuntime.PreparedCast program=null;
        if(option.getProgram()!=null) {
            program=WorldAbilityRuntime.prepareStart(bound.level,player,option.getProgram(),player.position(),(LivingEntity)bound.level.getEntity(session.actor),20,false,programArgs(context));
            if(program==null)throw new Rejected(StoryProtocol.Feedback.COOLDOWN,"回应尚未准备好，请稍后再试；没有扣除材料。");
        }
        try {
            inventory.assertUnchanged();facts.assertUnchanged();
            try {inventory.apply();facts.commit();if(program!=null)program.commit();}
            catch(RuntimeException failure) {
                try{facts.rollback();}catch(RuntimeException rollback){failure.addSuppressed(rollback);}
                try{inventory.rollback();}catch(RuntimeException rollback){failure.addSuppressed(rollback);}
                throw failure;
            }
        } finally {if(program!=null)program.close();}
        // Retire the old choice token even when the accepted choice made no fact writes.
        if(option.getNext()==null)SESSIONS.remove(player.getUUID(),session);
        else {session.node=option.getNext();session.rotate(bound.store().state().revision());}
        player.getInventory().setChanged();WorldStoryRuntime.changed(player);
        try{player.inventoryMenu.broadcastChanges();if(player.containerMenu!=player.inventoryMenu)player.containerMenu.broadcastChanges();}
        catch(RuntimeException sync){Worldsmith.LOGGER.warn("Story transaction committed but inventory display refresh failed",sync);}
    }
    private static boolean matches(WorldStoryRuntime.Bound bound,ItemStack stack,String reference,ItemStack prototype) {
        if(stack.isEmpty()||stack.getItem()!=prototype.getItem())return false;
        if(reference.startsWith(CustomItemRuntime.LOGICAL_PREFIX))return bound.snapshot.items.isValidWorldStack(stack)
            && Objects.equals(stack.get(CustomItemRuntime.identityComponent()),prototype.get(CustomItemRuntime.identityComponent()));
        return true;
    }
    private static AbilityValue programArgs(WorldStoryRuntime.Context context) {
        var place=context.place();if(place==null)throw new IllegalArgumentException("A dialogue program requires its actual place instance");
        return AbilityValues.map(Map.of("place_origin",vector(Vec3.atBottomCenterOf(place.position())),"place_x",vector(rotate(new Vec3(1,0,0),place.quarterTurns())),
            "place_z",vector(rotate(new Vec3(0,0,1),place.quarterTurns())),"character",AbilityValues.text(context.character().definition()),"place",AbilityValues.text(place.definition())));
    }
    static Vec3 rotate(Vec3 vector,int turns){return switch(Math.floorMod(turns,4)){case 0 -> vector;case 1 -> new Vec3(-vector.z,vector.y,vector.x);case 2 -> new Vec3(-vector.x,vector.y,-vector.z);default -> new Vec3(vector.z,vector.y,-vector.x);};}
    private static AbilityValue vector(Vec3 value){return AbilityValues.vector(value.x,value.y,value.z);}
    private static boolean near(ServerPlayer player,Entity actor) {
        return player.isAlive()&&!player.isSpectator()&&actor.isAlive()&&!actor.isRemoved()&&actor.level()==player.level()&&player.distanceToSqr(actor)<=36&&player.hasLineOfSight(actor);
    }
    private static WorldStoryRuntime.Context context(ServerPlayer player,Entity actor,StorySavedData.Character character,WorldStoryRuntime.Bound bound) {
        return new WorldStoryRuntime.Context(bound,player,player,character,bound.store().state().places().get(character.place()));
    }
    private static WorldStoryRuntime.Context valid(Session session) {
        var player=session.player;var bound=WorldStoryRuntime.WORLDS.get(session.level);
        if(bound==null||player.level()!=session.level||session.level.getServer().getPlayerList().getPlayer(player.getUUID())!=player||session.until<=session.level.getGameTime())return null;
        Entity actor=session.level.getEntity(session.actor);if(actor==null||!near(player,actor))return null;
        var character=StoryCharacters.record(bound,actor);if(character==null||character.dead()||!character.anchor().equals(session.character))return null;
        return context(player,actor,character,bound);
    }
    private static StoryDialogueNode node(StoryDialogue dialogue,String id){return dialogue.getNodes().stream().filter(n -> n.getId().equals(id)).findFirst().orElseThrow(() -> new IllegalArgumentException("Missing dialogue node: "+id));}
    static StoryProtocol.Snapshot snapshot(ServerPlayer player,int request,StoryProtocol.Feedback feedback,String message) {
        var bound=WorldStoryRuntime.WORLDS.get((ServerLevel)player.level());
        if(bound==null)return new StoryProtocol.Snapshot("",request,0,null,List.of(),List.of(),List.of(),feedback,message);
        if(bound.snapshot.empty())return new StoryProtocol.Snapshot(bound.snapshot.scope(),request,0,null,List.of(),List.of(),List.of(),feedback,message);
        var state=bound.store().state();var memory=state.player(player.getUUID());var session=SESSIONS.get(player.getUUID());StoryProtocol.Dialogue view=null;
        if(session!=null) {
            var context=valid(session);
            if(context==null)SESSIONS.remove(player.getUUID(),session);
            else {
                var dialogue=bound.snapshot.dialogues.get(session.dialogue);var current=node(dialogue,session.node);
                if(!WorldStoryRuntime.test(context,current.getCondition()))SESSIONS.remove(player.getUUID(),session);
                else {
                    if(session.revision!=state.revision())session.rotate(state.revision());
                    var choices=current.getOptions().stream().filter(o -> WorldStoryRuntime.test(context,o.getCondition()))
                        .filter(o -> o.getTrade()==null || WorldStoryRuntime.test(context,bound.snapshot.trades.get(o.getTrade()).getCondition()))
                        .map(o -> new StoryProtocol.Choice(o.getId(),o.getText(),tradeDetails(bound,o.getTrade()))).toList();
                    view=new StoryProtocol.Dialogue(session.token,session.actor,current.getId(),bound.snapshot.characters.get(context.character().definition()).getName(),current.getText(),choices);
                }
            }
        }
        var knowledge=bound.snapshot.library().getKnowledge().stream().filter(k -> memory.knowledge().contains(k.getId()))
            .map(k -> new StoryProtocol.Knowledge(k.getId(),k.getTitle(),k.getText(),k.getTruth().name())).toList();
        String dimension=player.level().dimension().identifier().toString();
        var places=memory.places().stream().map(state.places()::get).filter(Objects::nonNull)
            .sorted(Comparator.comparing((StorySavedData.Place p) -> !p.dimension().equals(dimension))
                .thenComparingDouble(p -> player.position().distanceToSqr(Vec3.atCenterOf(p.position()))).thenComparing(StorySavedData.Place::instance))
            .limit(StoryProtocol.MAX_PLACES).map(p -> {
                var definition=bound.snapshot.places.get(p.definition());return new StoryProtocol.Place(p.definition(),p.instance(),p.dimension(),p.position().getX(),p.position().getY(),p.position().getZ(),definition.getName(),definition.getDescription(),definition.getClue());
            }).toList();
        List<StoryProtocol.SoundCue> sounds=List.of();var context=WorldStoryRuntime.context(player);
        if(context!=null&&context.place()!=null) {
            var place=bound.snapshot.places.get(context.place().definition());var soundscape=place.getSoundscape()==null?null:bound.snapshot.soundscapes.get(place.getSoundscape());
            if(soundscape!=null)sounds=soundscape.getLayers().stream().filter(s -> WorldStoryRuntime.test(context,s.getCondition()))
                .sorted(Comparator.comparingInt(StorySoundLayer::getPriority).reversed().thenComparing(StorySoundLayer::getId)).limit(StoryProtocol.MAX_SOUNDS)
                .map(s -> new StoryProtocol.SoundCue(context.place().instance()+":"+s.getId(),s.getSound(),s.getVolume(),s.getPitch(),s.getPeriodTicks(),s.getFadeTicks(),s.getPriority(),s.getMusic(),s.getSubtitle())).toList();
        }
        return new StoryProtocol.Snapshot(bound.snapshot.scope(),request,state.revision(),view,knowledge,places,sounds,feedback,message);
    }
    private static String tradeDetails(WorldStoryRuntime.Bound bound,String id) {
        if(id==null)return "";var trade=bound.snapshot.trades.get(id);var text=new StringBuilder();
        text.append("需要：");if(trade.getInputs().isEmpty())text.append("无");
        for(var value:trade.getInputs())text.append('\n').append(value.getCount()).append(" × ").append(WorldRewardItems.stack(value.getItem(),1,bound.snapshot.blocks,bound.snapshot.items).getHoverName().getString());
        text.append("\n获得：");for(var value:trade.getOutputs())text.append('\n').append(value.getCount()).append(" × ").append(WorldRewardItems.stack(value.getItem(),1,bound.snapshot.blocks,bound.snapshot.items).getHoverName().getString());
        if(text.length()>8192)throw new IllegalArgumentException("Trade description exceeds its presentation budget");return text.toString();
    }
    private static void send(ServerPlayer player,int request,StoryProtocol.Feedback feedback,String message) {
        var snapshot=snapshot(player,request,feedback,message);SENT.put(player,snapshot);
        if(ServerPlayNetworking.canSend(player,StoryProtocol.Snapshot.TYPE))ServerPlayNetworking.send(player,snapshot);
    }
    static void refresh(ServerPlayer player) {
        if(!WorldStoryRuntime.WORLDS.containsKey((ServerLevel)player.level()))return;
        var view=snapshot(player,0,StoryProtocol.Feedback.NONE,"");var previous=SENT.get(player);
        if(previous!=null&&previous.scope().equals(view.scope())&&previous.revision()==view.revision()&&Objects.equals(previous.dialogue(),view.dialogue())
            &&previous.knowledge().equals(view.knowledge())&&previous.places().equals(view.places())&&previous.sounds().equals(view.sounds()))return;
        SENT.put(player,view);if(ServerPlayNetworking.canSend(player,StoryProtocol.Snapshot.TYPE))ServerPlayNetworking.send(player,view);
    }
    static void tick(MinecraftServer server) {
        for(var entry:List.copyOf(SESSIONS.entrySet()))if(entry.getValue().level.getServer()==server&&valid(entry.getValue())==null) {
            SESSIONS.remove(entry.getKey(),entry.getValue());if(server.getPlayerList().getPlayer(entry.getKey())==entry.getValue().player)refresh(entry.getValue().player);
        }
    }
    static ServerPlayer speaker(CreatureEntity actor) {
        for(var session:SESSIONS.values())if(session.actor.equals(actor.getUUID())&&valid(session)!=null)return session.player;
        return null;
    }
    /** Publish a real session close before an emergency controller moves the resident away. */
    static void interruptActor(CreatureEntity actor) {
        for(var session:List.copyOf(SESSIONS.values()))if(session.level==actor.level()&&session.actor.equals(actor.getUUID())) {
            SESSIONS.remove(session.player.getUUID(),session);
            if(session.level.getServer().getPlayerList().getPlayer(session.player.getUUID())==session.player)refresh(session.player);
        }
    }
    static void unbind(ServerLevel level) {
        SESSIONS.values().removeIf(s -> s.level==level);SENT.keySet().removeIf(p -> p.level()==level);GATES.keySet().removeIf(p -> p.level()==level);
    }
}

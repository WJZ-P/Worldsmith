package com.wjz.worldsmith.client.quest;

import com.wjz.worldsmith.content.interaction.MechanicGuideProtocol;
import com.wjz.worldsmith.content.interaction.MechanicGuideAccess;
import com.wjz.worldsmith.content.interaction.MechanicGuideRequest;
import com.wjz.worldsmith.content.interaction.WorldMechanicRuntime;
import com.wjz.worldsmith.content.quest.QuestProtocol;
import java.util.List;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.BlockHitResult;

/** Static plans use the active bundle. The only server request is an explicit, read-only inspection. */
final class MechanicGuideClient {
    private static int sequence;
    private static MechanicGuideRequest pending;
    private static Component notice = Component.empty();
    private static String noticeMechanic;
    private static String noticeScope;
    private static String noticeDimension;
    private static String resultRuleId;
    private static ClientPacketListener pendingConnection;
    private static boolean initialized;
    private MechanicGuideClient() {}

    static void initialize() {
        if (initialized) return;
        MechanicGuideProtocol.registerTypes();
        ClientPlayNetworking.registerGlobalReceiver(MechanicGuideProtocol.Reply.TYPE, (reply, context) -> {
            if (context.player() != context.client().player || pending == null ||
                !QuestJournalClient.current(pending.scope(), pendingConnection) ||
                context.client().level == null || !pending.dimension().equals(context.client().level.dimension().identifier().toString()) ||
                !pending.accepts(reply.scope(), reply.dimension(), reply.mechanicId(), reply.anchor().asLong(), reply.requestId(), System.nanoTime())) return;
            notice = reply.result().message();
            resultRuleId = reply.result().ruleId();
            noticeMechanic = pending.mechanicId(); noticeScope = pending.scope(); pending = null;
        });
        initialized = true;
    }

    static List<String> references(QuestProtocol.Entry quest) {
        return MechanicGuideAccess.references(quest);
    }

    static boolean available(String scope, String id) {
        var active = WorldMechanicRuntime.clientSnapshot();
        return active != null && scope.equals(active.scope()) && active.definitions().containsKey(id);
    }

    static void inspect(String scope, String id, ClientPacketListener connection) {
        Minecraft client = Minecraft.getInstance();
        if (!QuestJournalClient.current(scope, connection) || !available(scope, id) || pending != null) return;
        noticeScope = scope; noticeDimension = client.level.dimension().identifier().toString(); noticeMechanic = id; resultRuleId = null;
        if (!(client.hitResult instanceof BlockHitResult hit) || hit.getType() != net.minecraft.world.phys.HitResult.Type.BLOCK) {
            notice = Component.translatable("worldsmith.mechanics.guide.aim"); return;
        }
        if (!ClientPlayNetworking.canSend(MechanicGuideProtocol.Query.TYPE)) {
            notice = Component.translatable("worldsmith.mechanics.guide.channel_unavailable"); return;
        }
        if (sequence == Integer.MAX_VALUE) sequence = 0;
        String dimension = client.level.dimension().identifier().toString();
        pending = new MechanicGuideRequest(scope, dimension, id, hit.getBlockPos().asLong(), ++sequence, System.nanoTime());
        pendingConnection = connection;
        notice = Component.translatable("worldsmith.mechanics.guide.checking");
        try { ClientPlayNetworking.send(new MechanicGuideProtocol.Query(scope, dimension, id, hit.getBlockPos(), sequence)); }
        catch (RuntimeException error) { pending = null; notice = Component.translatable("worldsmith.mechanics.guide.channel_unavailable"); }
    }

    static void tick() {
        var client = Minecraft.getInstance();
        if (noticeDimension != null && (client.level == null || !noticeDimension.equals(client.level.dimension().identifier().toString()))) { reset(); return; }
        if (pending != null && pending.expired(System.nanoTime())) {
            pending = null; notice = Component.translatable("worldsmith.mechanics.guide.timeout");
        }
    }

    static boolean pending() { return pending != null; }
    static Component notice(String scope, String id) {
        var level = Minecraft.getInstance().level;
        return level != null && level.dimension().identifier().toString().equals(noticeDimension) && scope.equals(noticeScope) && id.equals(noticeMechanic) ? notice : Component.empty();
    }
    static String resultRuleId() { return resultRuleId; }
    static void reset() {
        pending = null; pendingConnection = null; notice = Component.empty(); noticeScope = null; noticeDimension = null; noticeMechanic = null; resultRuleId = null;
    }
}

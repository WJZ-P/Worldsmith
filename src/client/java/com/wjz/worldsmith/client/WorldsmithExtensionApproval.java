package com.wjz.worldsmith.client;

import com.wjz.worldsmith.Worldsmith;
import com.wjz.worldsmith.core.ability.extension.AbilityExtensionApproval;
import com.wjz.worldsmith.core.ability.extension.AbilityExtensionService;
import com.wjz.worldsmith.core.ability.extension.AbilityExtensionStage;
import com.wjz.worldsmith.mcp.WorldsmithMcpService;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.AlertScreen;
import net.minecraft.client.gui.screens.GenericMessageScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** No automatic approval mode. Exact hash confirmation is separate from all drawing/session grants. */
public final class WorldsmithExtensionApproval {
    private record Request(AbilityExtensionApproval approval, AbilityExtensionService host) {}
    private static final ArrayBlockingQueue<Request> QUEUE = new ArrayBlockingQueue<>(8);
    private static final ExecutorService COMMITS = Executors.newSingleThreadExecutor(task -> { var thread = new Thread(task, "worldsmith-extension-confirmation"); thread.setDaemon(true); return thread; });
    private static boolean showing;
    private WorldsmithExtensionApproval() {}

    public static void initialize() { WorldsmithMcpService.setExtensionApprovalListener(WorldsmithExtensionApproval::request); }

    public static void request(AbilityExtensionApproval approval) {
        var host = WorldsmithMcpService.abilityExtensions();
        if (host == null || !host.getInstallationAvailable()) throw new IllegalStateException("Extension installation host is not connected");
        if (!QUEUE.offer(new Request(approval, host))) throw new IllegalStateException("Extension confirmation queue is full; finish the current requests first");
        Minecraft.getInstance().execute(WorldsmithExtensionApproval::showNext);
    }

    private static void showNext() {
        if (showing) return;
        Request request;
        do {
            request = QUEUE.poll();
            if (request == null) return;
        } while (request.host != WorldsmithMcpService.abilityExtensions() || !request.host.getInstallationAvailable()
            || request.host.get(request.approval.getJobId()).getStage() != AbilityExtensionStage.WAITING_INSTALL_APPROVAL);
        showing = true;
        var selected = request;
        var client = Minecraft.getInstance();
        var previous = client.gui.screen();
        client.gui.setScreen(new WorldsmithExtensionApprovalScreen(selected.approval, accepted -> decide(selected, previous, accepted)));
    }

    private static void decide(Request request, Screen previous, boolean accepted) {
        var client = Minecraft.getInstance();
        client.gui.setScreen(new GenericMessageScreen(Component.translatable("worldsmith.extension.approval.working")));
        CompletableFuture.supplyAsync(() -> {
            var approval = request.approval;
            return accepted
                ? request.host.approveInstall(approval.getJobId(), approval.getSourceHash(), approval.getArtifactHash())
                : request.host.denyInstall(approval.getJobId(), approval.getSourceHash(), approval.getArtifactHash());
        }, COMMITS).whenComplete((job, failure) -> client.execute(() -> {
            String message;
            if (failure != null) {
                Throwable cause = failure.getCause() == null ? failure : failure.getCause();
                message = cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
                Worldsmith.LOGGER.warn("Ability extension confirmation did not complete", cause);
            } else message = job.getMessage();
            String bounded = message.substring(0, Math.min(512, message.length()));
            client.gui.setScreen(new AlertScreen(() -> {
                showing = false; client.gui.setScreen(previous); client.execute(WorldsmithExtensionApproval::showNext);
            }, Component.translatable("worldsmith.extension.approval.result"), Component.literal(bounded)));
        }));
    }
}

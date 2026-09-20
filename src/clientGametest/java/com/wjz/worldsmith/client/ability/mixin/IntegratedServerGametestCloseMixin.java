package com.wjz.worldsmith.client.ability.mixin;

import java.util.concurrent.TimeUnit;
import net.fabricmc.fabric.impl.client.gametest.threading.ThreadingImpl;
import net.minecraft.client.server.IntegratedServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Test-mod-only bridge for Fabric 6.0 / Minecraft 26.2's shutdown phase gap.
 * Vanilla halt joins a server task before reaching Fabric's renderFrame yield.
 * Joining without the phase handshake parks server, client and test in a cycle.
 */
@Mixin(IntegratedServer.class)
public abstract class IntegratedServerGametestCloseMixin {
    @Redirect(method = "halt", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/server/IntegratedServer;executeBlocking(Ljava/lang/Runnable;)V"))
    private void worldsmith$joinHaltCooperatively(IntegratedServer server, Runnable action) {
        if (ThreadingImpl.testThread == null || server.isSameThread() || !ThreadingImpl.isServerRunning
            || !ThreadingImpl.unsafeClientInstance.isSameThread()) {
            server.executeBlocking(action);
            return;
        }
        // Execute the exact vanilla action on its owning server thread; never pretend it ran.
        var completion = server.submit(action).orTimeout(20, TimeUnit.SECONDS);
        while (!completion.isDone()) worldsmith$yieldClientTestPhase();
        completion.join();
    }

    @Unique
    private static void worldsmith$yieldClientTestPhase() {
        // This is the same public ThreadingImpl barrier/semaphore handshake used by
        // Fabric's MinecraftMixin.postRunTasks. Here it is needed one blocking call
        // earlier, inside halt. The test thread can still run its normal close predicate.
        ThreadingImpl.clientCanAcceptTasks = true;
        ThreadingImpl.enterPhase(ThreadingImpl.PHASE_TEST);
        if (ThreadingImpl.testThread != null) {
            while (true) {
                try { ThreadingImpl.CLIENT_SEMAPHORE.acquire(); }
                catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Interrupted while closing the test-owned server", interrupted);
                }
                var task = ThreadingImpl.taskToRun;
                if (task == null) break;
                task.run();
            }
        }
        ThreadingImpl.enterPhase(ThreadingImpl.PHASE_TICK);
    }
}

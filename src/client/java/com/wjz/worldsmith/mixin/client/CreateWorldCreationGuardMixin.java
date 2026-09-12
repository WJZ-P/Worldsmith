package com.wjz.worldsmith.mixin.client;

import com.wjz.worldsmith.client.WorldsmithWorldCreationBridge;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Native button/Enter/confirmation guards, plus ownership for vanilla's async data-pack callbacks. */
@Mixin(CreateWorldScreen.class)
public abstract class CreateWorldCreationGuardMixin {
    @Inject(method="onCreate",at=@At("HEAD"),cancellable=true)
    private void worldsmith$gateCreate(CallbackInfo ci) {
        if(!WorldsmithWorldCreationBridge.allowCreate((CreateWorldScreen)(Object)this))ci.cancel();
    }

    @Inject(method="createWorldAndCleanup",at=@At("HEAD"),cancellable=true)
    private void worldsmith$gateCommit(CallbackInfo ci) {
        if(!WorldsmithWorldCreationBridge.allowCreationCommit((CreateWorldScreen)(Object)this))ci.cancel();
    }

    @Redirect(method="applyNewPackConfig",at=@At(value="INVOKE",target="Ljava/util/concurrent/CompletableFuture;thenAcceptAsync(Ljava/util/function/Consumer;Ljava/util/concurrent/Executor;)Ljava/util/concurrent/CompletableFuture;"))
    private CompletableFuture<Void> worldsmith$ownedSettings(CompletableFuture<?> future,Consumer<Object> consumer,Executor executor) {
        var screen=(CreateWorldScreen)(Object)this;
        var ownership=WorldsmithWorldCreationBridge.captureNativeReload(screen);
        return future.thenAcceptAsync(value->{
            if(WorldsmithWorldCreationBridge.acceptsNativeReload(screen,ownership))consumer.accept(value);
        },executor);
    }

    @Redirect(method="applyNewPackConfig",at=@At(value="INVOKE",target="Ljava/util/concurrent/CompletableFuture;handleAsync(Ljava/util/function/BiFunction;Ljava/util/concurrent/Executor;)Ljava/util/concurrent/CompletableFuture;"))
    private CompletableFuture<Object> worldsmith$ownedCompletion(CompletableFuture<?> future,BiFunction<Object,Throwable,Object> handler,Executor executor) {
        var screen=(CreateWorldScreen)(Object)this;
        var ownership=WorldsmithWorldCreationBridge.captureNativeReload(screen);
        var completion=future.handleAsync((value,failure)->
            WorldsmithWorldCreationBridge.acceptsNativeReload(screen,ownership) ? handler.apply(value,failure) : null,executor);
        WorldsmithWorldCreationBridge.trackNativeReload(screen,ownership,completion);
        return completion;
    }
}

package com.wjz.worldsmith.client.ability;

import com.wjz.worldsmith.client.WorldsmithExtensionApprovalScreen;
import com.wjz.worldsmith.core.ability.AbilityCapabilitySpec;
import com.wjz.worldsmith.core.ability.AbilityType;
import com.wjz.worldsmith.core.ability.extension.AbilityExtensionApproval;
import com.wjz.worldsmith.core.ability.extension.AbilityExtensionManifest;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import org.lwjgl.glfw.GLFW;

/** Actual rendered confirmation UI; decisions target a test callback, never a real install directory. */
public final class AbilityExtensionApprovalClientChecks {
    private AbilityExtensionApprovalClientChecks() {}

    public static List<Path> capture(ClientGameTestContext context) {
        var images = new ArrayList<Path>();
        var decisions = new ArrayList<Boolean>();
        var parent = context.computeOnClient(client -> client.gui.screen());
        String previousLanguage = context.computeOnClient(client -> client.options.languageCode);
        int previousScale = context.computeOnClient(client -> client.options.guiScale().get());
        int previousWidth = context.computeOnClient(client -> client.getWindow().getScreenWidth());
        int previousHeight = context.computeOnClient(client -> client.getWindow().getScreenHeight());
        String source = "0123456789abcdef".repeat(4), artifact = "fedcba9876543210".repeat(4);
        var spec = new AbilityCapabilitySpec("example.scale", 1, List.of(AbilityType.NUMBER), AbilityType.NUMBER, false,
            "example.scale(value): a user-defined provider, not a new built-in attack type. This screen is an interface test; its decision callback does not install any JAR.");
        var manifest = new AbilityExtensionManifest(1, "ui_fixture", "User-authored provider / 自定义提供者", source,
            List.of("example.ScaleProvider"), Map.of("example.ScaleProvider", spec), 21, "static_abi_only");
        var approval = new AbilityExtensionApproval("a".repeat(32), manifest.getId(), manifest.getName(), source, artifact, "b".repeat(64), manifest,
            "ui-fixture/source-project.json", "ui-fixture/candidate.jar", "Trusted JVM code; interface test only", true);
        try {
            language(context, "en_us");
            context.runOnClient(client -> client.options.guiScale().set(2));
            context.getInput().resizeWindow(1120, 700);
            context.runOnClient(client -> client.gui.setScreen(new WorldsmithExtensionApprovalScreen(approval, accepted -> {
                decisions.add(accepted); client.gui.setScreen(parent);
            })));
            images.add(screenshot(context, "extension-01-independent-approval-en"));
            context.getInput().pressKey(GLFW.GLFW_KEY_END);
            images.add(screenshot(context, "extension-02-provider-declarations-en"));
            context.clickScreenButton("worldsmith.extension.approval.deny");
            context.waitFor(client -> client.gui.screen() == parent);
            check(decisions.equals(List.of(false)), "Deny must call the independent decision exactly once");

            language(context, "zh_cn");
            context.runOnClient(client -> client.options.guiScale().set(1));
            context.getInput().resizeWindow(320, 180);
            context.runOnClient(client -> client.gui.setScreen(new WorldsmithExtensionApprovalScreen(approval, accepted -> {
                decisions.add(accepted); client.gui.setScreen(parent);
            })));
            context.waitFor(client -> client.gui.screen() instanceof WorldsmithExtensionApprovalScreen screen && screen.width == 320 && screen.height == 180);
            images.add(screenshot(context, "extension-03-compact-warning-zh"));
            context.getInput().pressKey(GLFW.GLFW_KEY_PAGE_DOWN);
            images.add(screenshot(context, "extension-04-compact-full-hashes-zh"));
            context.getInput().pressKey(GLFW.GLFW_KEY_END);
            images.add(screenshot(context, "extension-05-compact-provider-zh"));
            context.clickScreenButton("worldsmith.extension.approval.install");
            context.waitFor(client -> client.gui.screen() == parent);
            check(decisions.equals(List.of(false, true)), "Only the explicit install button should accept");

            context.runOnClient(client -> client.gui.setScreen(new WorldsmithExtensionApprovalScreen(approval, accepted -> {
                decisions.add(accepted); client.gui.setScreen(parent);
            })));
            context.waitForScreen(WorldsmithExtensionApprovalScreen.class);
            context.getInput().pressKey(GLFW.GLFW_KEY_ESCAPE);
            context.waitFor(client -> client.gui.screen() == parent);
            check(decisions.equals(List.of(false, true, false)), "Escape must decline, not install");
            String all = WorldsmithExtensionApprovalScreen.reviewLines(approval).stream().map(component -> component.getString())
                .collect(java.util.stream.Collectors.joining()).replaceAll("\\s+", "");
            check(all.contains(source) && all.contains(artifact), "Review text lost part of a SHA-256 hash");
        } finally {
            context.runOnClient(client -> client.gui.setScreen(parent));
            language(context, previousLanguage);
            context.runOnClient(client -> client.options.guiScale().set(previousScale));
            context.getInput().resizeWindow(previousWidth, previousHeight);
        }
        return List.copyOf(images);
    }

    private static void language(ClientGameTestContext context, String language) {
        CompletableFuture<Void> future = context.computeOnClient(client -> {
            client.options.languageCode = language; client.getLanguageManager().setSelected(language); return client.reloadResourcePacks();
        });
        context.waitFor(client -> future.isDone(), 20 * 120); future.join();
        context.waitFor(client -> client.gui.overlay() == null, 20 * 120);
    }
    private static Path screenshot(ClientGameTestContext context, String name) {
        context.waitFor(client -> client.gui.screen() instanceof WorldsmithExtensionApprovalScreen && client.gui.overlay() == null);
        context.waitTicks(3);
        return context.takeScreenshot(name);
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}

package com.wjz.worldsmith.client;

import com.wjz.worldsmith.core.drawhost.DrawingHost;
import com.wjz.worldsmith.mcp.WorldsmithMcpService;
import java.util.ArrayDeque;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.network.chat.Component;

/**
 * Asks once per world-authoring session before AI-authored Java may run.
 *
 * Only reached when {@code mcp.autoApproveSourceExecution} is off; the default
 * is to approve inside {@link DrawingHost} without involving any UI. MCP still
 * exposes no approval method, so the decision stays host-side either way.
 */
public final class WorldsmithSourceApproval {
	private record Request(String session,DrawingHost host) {}
	private static final ArrayDeque<Request> QUEUE=new ArrayDeque<>();
	private static boolean showing;
	private WorldsmithSourceApproval() {}
	public static void request(String session) {
		var host=WorldsmithMcpService.drawingHost();if(host==null)return;
		Minecraft.getInstance().execute(()->{QUEUE.add(new Request(session,host));showNext();});
	}
	private static void showNext() {
		if(showing||QUEUE.isEmpty())return;showing=true;
		var request=QUEUE.remove();var mc=Minecraft.getInstance();var previous=mc.gui.screen();
		mc.gui.setScreen(new ConfirmScreen(accepted->{
			if(accepted)request.host.approve(request.session);else request.host.deny(request.session);
			showing=false;mc.gui.setScreen(previous);mc.execute(WorldsmithSourceApproval::showNext);
		},Component.translatable("worldsmith.draw.approval.title"),Component.translatable("worldsmith.draw.approval.body",request.session.substring(0,8))));
	}
}

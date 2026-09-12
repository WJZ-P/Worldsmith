package com.wjz.worldsmith.client;

import com.wjz.worldsmith.Worldsmith;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.fabricmc.fabric.api.event.Event;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.SpriteIconButton;
import net.minecraft.client.gui.components.WidgetSprites;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Appends to the actual small-button row, including optional Mod Menu icons. */
public final class WorldsmithTitleMenu {
    private static final int BUTTON_SIZE = 20;
    private static final int GAP = 4;
    private static final Identifier AFTER_MENU_ICONS = Identifier.fromNamespaceAndPath("worldsmith", "after_menu_icons");
    private static final Identifier PACK_ICON = Identifier.fromNamespaceAndPath("worldsmith", "icon/world_pack");

    private WorldsmithTitleMenu() {}

    public static void initialize() {
        // Include icons contributed by ordinary AFTER_INIT callbacks as well as vanilla/mixins.
        ScreenEvents.AFTER_INIT.addPhaseOrdering(Event.DEFAULT_PHASE, AFTER_MENU_ICONS);
        ScreenEvents.AFTER_INIT.register(AFTER_MENU_ICONS, (client, screen, width, height) -> {
            if (screen instanceof TitleScreen) {
                if (appendTo(Screens.getWidgets(screen), width,
                        () -> client.gui.setScreen(new WorldsmithResourcePackScreen(screen))) == null) {
                    Worldsmith.LOGGER.warn("Worldsmith menu icon: title screen has no standard language/accessibility icon row");
                }
            }
        });
    }

    /** No screen or graphics context is needed to arrange the actual native widgets. */
    static PackIconButton appendTo(List<AbstractWidget> widgets, int screenWidth, Runnable openLibrary) {
        // Resize/reinitialization must not duplicate our entry or remove somebody else's widget.
        widgets.removeIf(PackIconButton.class::isInstance);
        var anchor = widgets.stream().filter(WorldsmithTitleMenu::isSmallVisibleButton)
                .filter(widget -> hasKey(widget, "options.language") || hasKey(widget, "options.accessibility"))
                .findFirst().orElse(null);
        if (anchor == null) return null;

        int y = anchor.getY();
        var row = new ArrayList<>(widgets.stream().filter(WorldsmithTitleMenu::isSmallVisibleButton)
                .filter(widget -> widget.getY() == y).sorted(Comparator.comparingInt(AbstractWidget::getX)).toList());
        int insertionIndex = widgets.indexOf(row.getLast()) + 1;
        var icon = new PackIconButton(openLibrary);
        row.add(icon);

        int totalWidth = row.size() * BUTTON_SIZE + (row.size() - 1) * GAP;
        int left = screenWidth / 2 - totalWidth / 2;
        for (int i = 0; i < row.size(); i++) row.get(i).setPosition(left + i * (BUTTON_SIZE + GAP), y);
        widgets.add(insertionIndex, icon);
        return icon;
    }

    private static boolean isSmallVisibleButton(AbstractWidget widget) {
        return widget.visible && widget.getWidth() == BUTTON_SIZE && widget.getHeight() == BUTTON_SIZE;
    }

    private static boolean hasKey(AbstractWidget widget, String key) {
        return widget.getMessage().getContents() instanceof TranslatableContents text && key.equals(text.getKey());
    }

    static final class PackIconButton extends SpriteIconButton.CenteredIcon {
        private PackIconButton(Runnable openLibrary) {
            super(BUTTON_SIZE, BUTTON_SIZE, Component.translatable("worldsmith.packs.open"),
                    // The source includes transparent padding; its visible mark fits inside the native bevel.
                    20, 20, 0, 0, new WidgetSprites(PACK_ICON), button -> openLibrary.run(),
                    Component.translatable("worldsmith.packs.open").append("\n")
                            .append(Component.translatable("worldsmith.packs.open.hint")), null, false);
        }
    }
}

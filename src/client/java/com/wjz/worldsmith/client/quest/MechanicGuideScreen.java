package com.wjz.worldsmith.client.quest;

import com.wjz.worldsmith.content.WorldBlockBindings;
import com.wjz.worldsmith.content.WorldRewardItems;
import com.wjz.worldsmith.content.creature.CreatureRuntime;
import com.wjz.worldsmith.content.interaction.MechanicGuideLayout;
import com.wjz.worldsmith.content.interaction.WorldMechanicRuntime;
import com.wjz.worldsmith.content.item.CustomItemRuntime;
import com.wjz.worldsmith.core.content.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.FormattedCharSequence;
import org.lwjgl.glfw.GLFW;

/** A book of actual rules, not a live world scanner. Diagrams share one coordinate frame across layers. */
final class MechanicGuideScreen extends Screen {
    private static final int TEXT = 0xFFD9DFE8, HEADING = 0xFFF3D992, MUTED = 0xFFAFBFD1;
    private final QuestJournalScreen parent;
    private final String scope;
    private final ClientPacketListener connection;
    private final String questId;
    private final List<String> mechanics;
    private int mechanicIndex, ruleIndex, layerIndex, scroll, contentHeight;
    private MechanicGuide guide;
    private WorldBlockBindings.Resolver blocks;
    private MechanicGuideLayout layout;
    private Button inspect;
    private boolean tooSmall;
    private List<Row> cachedRows = List.of();
    private Component cachedNotice;
    private String cachedResultRuleId;
    private java.util.Map<Long, MechanicPatternCell> layerCells = java.util.Map.of();

    MechanicGuideScreen(QuestJournalScreen parent, String scope, ClientPacketListener connection, String questId, List<String> mechanics) {
        super(tr("title"));
        this.parent = parent; this.scope = scope; this.connection = connection; this.questId = questId; this.mechanics = List.copyOf(mechanics);
    }

    private String mechanicId() { return mechanics.get(mechanicIndex); }
    private boolean current() {
        if (mechanics.isEmpty() || !QuestJournalClient.current(scope, connection) || !MechanicGuideClient.available(scope, mechanicId())) return false;
        var journal = QuestJournalClient.snapshot(scope);
        return journal != null && journal.quests().stream().filter(quest -> quest.id().equals(questId))
            .anyMatch(quest -> MechanicGuideClient.references(quest).contains(mechanicId()));
    }

    @Override protected void init() {
        if (!current()) return;
        guide = MechanicGuides.describe(WorldMechanicRuntime.clientSnapshot().definitions().get(mechanicId()));
        var binding = WorldBlockBindings.active();
        blocks = binding != null && scope.equals(binding.getScope()) ? WorldBlockBindings.resolver(binding) : null;
        ruleIndex = Math.min(ruleIndex, guide.getRules().size() - 1);
        var rule = rule(); layerIndex = Math.min(layerIndex, rule.getLayers().size() - 1);
        tooSmall = width < 180 || height < 140;
        if (tooSmall) {
            addRenderableWidget(Button.builder(tr("back"), button -> onClose()).bounds(Math.max(0, width / 2 - 45), Math.max(30, height - 24), Math.min(90, width), 20).build());
            return;
        }
        int rows = (mechanics.size() > 1 ? 1 : 0) + (guide.getRules().size() > 1 ? 1 : 0) + 1;
        layout = MechanicGuideLayout.of(width, height, rows, rule.getMaxX() - rule.getMinX() + 1);
        int y = 26;
        if (mechanics.size() > 1) {
            controls(y, tr("target", mechanicIndex + 1, mechanics.size(), guide.getTitle()), () -> changeMechanic(-1), () -> changeMechanic(1)); y += 22;
        }
        if (guide.getRules().size() > 1) {
            controls(y, tr("rule", ruleIndex + 1, guide.getRules().size(), state(rule.getRule().getFromState())), () -> changeRule(-1), () -> changeRule(1)); y += 22;
        }
        controls(y, tr("layer", signed(layer().getY()), layerIndex + 1, rule.getLayers().size()), () -> changeLayer(-1), () -> changeLayer(1));
        int backWidth = Math.min(92, width / 3);
        inspect = addRenderableWidget(Button.builder(tr("inspect"), button -> MechanicGuideClient.inspect(scope, mechanicId(), connection))
            .bounds(layout.left(), height - 26, layout.contentWidth() - backWidth - 6, 20)
            .tooltip(Tooltip.create(tr("inspect_hint"))).build());
        addRenderableWidget(Button.builder(tr("back"), button -> onClose()).bounds(width - layout.left() - backWidth, height - 26, backWidth, 20).build());
        java.util.Map<Long, MechanicPatternCell> cells = new java.util.HashMap<>();
        for (var cell : layer().getCells()) cells.put(cellKey(cell.getOffset().getX(), cell.getOffset().getZ()), cell);
        layerCells = java.util.Map.copyOf(cells);
        refreshRows();
    }

    private void controls(int y, Component label, Runnable previous, Runnable next) {
        addRenderableWidget(Button.builder(Component.literal("<"), button -> previous.run()).bounds(layout.left(), y, 22, 20).build());
        var caption = addRenderableWidget(Button.builder(label, button -> {}).bounds(layout.left() + 26, y, layout.contentWidth() - 52, 20).tooltip(Tooltip.create(label)).build());
        caption.active = false;
        addRenderableWidget(Button.builder(Component.literal(">"), button -> next.run()).bounds(width - layout.left() - 22, y, 22, 20).build());
    }

    private void changeMechanic(int delta) { mechanicIndex = Math.floorMod(mechanicIndex + delta, mechanics.size()); ruleIndex = 0; layerIndex = 0; scroll = 0; MechanicGuideClient.reset(); rebuildWidgets(); }
    private void changeRule(int delta) { ruleIndex = Math.floorMod(ruleIndex + delta, guide.getRules().size()); layerIndex = 0; scroll = 0; rebuildWidgets(); }
    private void changeLayer(int delta) { layerIndex = Math.floorMod(layerIndex + delta, rule().getLayers().size()); rebuildWidgets(); }
    private MechanicRuleGuide rule() { return guide.getRules().get(ruleIndex); }
    private MechanicGuideLayer layer() { return rule().getLayers().get(layerIndex); }

    @Override public void tick() {
        if (!current()) { minecraft.gui.setScreen(null); return; }
        MechanicGuideClient.tick();
        if (inspect != null) inspect.active = !MechanicGuideClient.pending();
        if (!tooSmall && (!java.util.Objects.equals(cachedNotice, MechanicGuideClient.notice(scope, mechanicId())) ||
            !java.util.Objects.equals(cachedResultRuleId, MechanicGuideClient.resultRuleId()))) { scroll = 0; refreshRows(); }
    }

    @Override public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        if (!current() || guide == null) return;
        graphics.centeredText(font, font.plainSubstrByWidth(guide.getTitle(), Math.max(30, width - 24)), width / 2, 10, HEADING);
        if (tooSmall) {
            graphics.textWithWordWrap(font, Component.translatable("worldsmith.quests.small_window"), 8, 30, Math.max(30, width - 16), TEXT);
            super.extractRenderState(graphics, mouseX, mouseY, partialTick); return;
        }
        graphics.fill(layout.left(), layout.contentTop(), width - layout.left(), layout.contentBottom(), 0xDA17212F);
        scroll = MechanicGuideLayout.scroll(scroll, 0, contentHeight, viewport());
        graphics.enableScissor(layout.left(), layout.contentTop(), width - layout.left(), layout.contentBottom());
        int y = layout.contentTop() + 6 - scroll;
        for (Row row : cachedRows) {
            if (y + row.height() >= layout.contentTop() && y < layout.contentBottom()) {
                if (row.gridZ != null) drawGridRow(graphics, y, row.gridZ);
                else graphics.text(font, row.text, layout.left() + 6, y, row.color);
            }
            y += row.height();
        }
        graphics.disableScissor();
        int maximum = Math.max(0, contentHeight - viewport());
        if (maximum > 0) {
            int track = viewport() - 4, thumb = Math.max(8, track * viewport() / contentHeight);
            int top = layout.contentTop() + 2 + (track - thumb) * scroll / maximum;
            graphics.fill(width - layout.left() - 3, top, width - layout.left() - 1, top + thumb, 0xFF8195AE);
        }
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
    }

    private void refreshRows() {
        cachedNotice = MechanicGuideClient.notice(scope, mechanicId());
        cachedResultRuleId = MechanicGuideClient.resultRuleId();
        cachedRows = List.copyOf(rows()); contentHeight = cachedRows.stream().mapToInt(Row::height).sum() + 12;
    }

    private List<Row> rows() {
        List<Row> rows = new ArrayList<>(); var projected = rule(); var actual = projected.getRule();
        Component notice = MechanicGuideClient.notice(scope, mechanicId());
        if (!notice.getString().isEmpty()) {
            add(rows, tr("inspection_result", notice), 0xFF9BC9F7);
            for (int i = 0; i < guide.getRules().size(); i++) if (guide.getRules().get(i).getRule().getId().equals(MechanicGuideClient.resultRuleId())) {
                add(rows, tr("inspection_rule", i + 1), MUTED); break;
            }
            blank(rows);
        }
        if (!guide.getDescription().isBlank()) add(rows, Component.literal(guide.getDescription()), TEXT);
        add(rows, tr("purpose"), HEADING);
        for (var action : actual.getActions()) add(rows, action(action), TEXT);
        blank(rows); add(rows, tr("build"), HEADING);
        add(rows, tr("orientation"), MUTED);
        add(rows, tr(actual.getRotateY() ? "rotation_allowed" : "rotation_fixed"), MUTED);
        add(rows, tr("legend"), MUTED);
        add(rows, tr("layer_display", signed(layer().getY())), HEADING);
        if (layout.cellList()) {
            add(rows, tr("cell_list"), MUTED);
            for (var cell : layer().getCells()) add(rows, tr("cell", coordinate(cell.getOffset()), symbol(cell), block(cell.getBlock())), TEXT);
        } else {
            rows.add(new Row(null, 0, projected.getMinZ() - 1, 13));
            for (int z = projected.getMinZ(); z <= projected.getMaxZ(); z++) rows.add(new Row(null, 0, z, layout.cellSize()));
        }
        for (var cell : layer().getCells()) if (WorldMechanicValidation.isAir(cell.getBlock().getBlock()) && !cell.getBlock().getBlock().equals("minecraft:air"))
            add(rows, tr("specific_air", coordinate(cell.getOffset()), block(cell.getBlock())), MUTED);
        blank(rows); add(rows, tr("materials"), HEADING);
        if (projected.getPalette().isEmpty()) add(rows, tr("no_blocks"), TEXT);
        for (var material : projected.getPalette()) {
            add(rows, tr("material", material.getSymbol(), block(material.getBlock()), material.getCount(), material.getConsumeCount()), TEXT);
            if (!material.getBlock().getProperties().isEmpty()) add(rows, tr("properties", properties(material.getBlock())), MUTED);
        }
        add(rows, tr("material_note"), MUTED);
        blank(rows); add(rows, tr("operate"), HEADING);
        add(rows, tr(actual.getEvent() == WorldMechanicEvent.USE_BLOCK ? "use_block" : "block_placed"), TEXT);
        if (actual.getHeldItem() == null) add(rows, tr(actual.getEvent() == WorldMechanicEvent.USE_BLOCK ? "empty_hand" : "no_hand_cost"), TEXT);
        else add(rows, tr("hand_cost", item(actual.getHeldItem().getItem()), actual.getHeldItem().getCount()), TEXT);
        add(rows, tr("state_transition", state(actual.getFromState()), state(actual.getToState()), state(guide.getInitialState())), MUTED);
        add(rows, tr("cooldown", String.format(Locale.ROOT, "%.1f", actual.getCooldownTicks() / 20.0)), MUTED);
        if (actual.getBiomes().isEmpty()) add(rows, tr("any_biome"), MUTED);
        else for (String biome : actual.getBiomes()) add(rows, tr("biome", biome(biome)), MUTED);
        blank(rows); add(rows, tr("readonly"), MUTED); add(rows, tr("scroll"), MUTED);
        return rows;
    }

    private void drawGridRow(GuiGraphicsExtractor graphics, int y, int z) {
        var projected = rule(); int size = layout.cellSize(), startX = layout.left() + 30;
        if (z < projected.getMinZ()) {
            graphics.text(font, "X", layout.left() + 7, y, MUTED);
            for (int x = projected.getMinX(); x <= projected.getMaxX(); x++) graphics.centeredText(font, String.valueOf(x), startX + (x - projected.getMinX()) * size + size / 2, y, MUTED);
            return;
        }
        graphics.text(font, String.valueOf(z), layout.left() + 7, y + (size - 8) / 2, MUTED);
        for (int x = projected.getMinX(); x <= projected.getMaxX(); x++) {
            int cellX = startX + (x - projected.getMinX()) * size;
            var cell = layerCells.get(cellKey(x, z));
            String symbol = cell == null ? "" : symbol(cell);
            graphics.fill(cellX, y, cellX + size - 1, y + size - 1, cell == null ? 0xFF202832 : cell.getConsume() ? 0xFF6C4C2D : WorldMechanicValidation.isAir(cell.getBlock().getBlock()) ? 0xFF344B60 : 0xFF3A4657);
            if (x == 0 && z == 0 && layer().getY() == 0) graphics.outline(cellX, y, size - 1, size - 1, 0xFFF3D992);
            if (!symbol.isEmpty()) graphics.centeredText(font, symbol, cellX + size / 2, y + (size - 8) / 2, TEXT);
        }
    }

    private Component action(MechanicAction action) {
        if (action instanceof MechanicAction.RunProgram run) {
            var snapshot = com.wjz.worldsmith.ability.WorldAbilityRuntime.clientSnapshot();
            return tr("run_program", snapshot != null && scope.equals(snapshot.scope()) ? snapshot.programName(run.getProgram()) : run.getProgram());
        }
        if (action instanceof MechanicAction.GiveItem give) return tr("give", item(give.getItem()), give.getCount());
        if (action instanceof MechanicAction.SpawnCreature spawn) {
            var snapshot = CreatureRuntime.clientSnapshot();
            var creature = snapshot != null && scope.equals(snapshot.bundleHash()) ? snapshot.definitions().get(spawn.getCreature()) : null;
            return tr("spawn", creature == null ? spawn.getCreature() : creature.getDisplayName(), coordinate(spawn.getOffset()));
        }
        if (action instanceof MechanicAction.SetBlock set) {
            Component result = tr("replace", coordinate(set.getOffset()), block(set.getBlock()));
            return set.getBlock().getProperties().isEmpty() ? result : result.copy().append(" ").append(tr("properties", properties(set.getBlock())));
        }
        return Component.empty();
    }

    private Component block(MechanicBlockPredicate predicate) {
        if (predicate.getBlock().equals("minecraft:cave_air")) return tr("air.cave_air");
        if (predicate.getBlock().equals("minecraft:void_air")) return tr("air.void_air");
        try {
            if (predicate.getBlock().startsWith("worldsmith:content/") && blocks != null) return blocks.resolve(predicate.getBlock()).getBlock().getName();
            var block = BuiltInRegistries.BLOCK.getOptional(Identifier.parse(predicate.getBlock())).orElse(null);
            if (block != null) return block.getName();
        } catch (RuntimeException ignored) { /* Display an honest identifier if a resource name is missing. */ }
        return Component.literal(predicate.getBlock());
    }

    private Component item(String reference) {
        try {
            var items = CustomItemRuntime.clientSnapshot();
            return WorldRewardItems.stack(reference, 1, blocks, items != null && scope.equals(items.bundleHash()) ? items : null).getHoverName();
        } catch (RuntimeException ignored) { return Component.literal(reference); }
    }

    private Component biome(String reference) {
        var creatures = CreatureRuntime.clientSnapshot();
        String id = creatures != null && scope.equals(creatures.bundleHash()) ? creatures.biomeBindings().getOrDefault(reference, reference) : reference;
        Identifier identifier = Identifier.tryParse(id);
        return identifier == null ? Component.literal(reference) : Component.translatableWithFallback(identifier.toLanguageKey("biome"), reference);
    }

    private String symbol(MechanicPatternCell cell) { return MechanicGuides.symbolAt(rule(), cell.getOffset().getX(), cell.getOffset().getY(), cell.getOffset().getZ()); }
    private static Component coordinate(MechanicOffset offset) { return Component.literal("(" + signed(offset.getX()) + ", " + signed(offset.getY()) + ", " + signed(offset.getZ()) + ")"); }
    private static String signed(int value) { return value > 0 ? "+" + value : String.valueOf(value); }
    private static long cellKey(int x, int z) { return ((long)x << 32) ^ (z & 0xffffffffL); }
    private static Component state(String value) {
        return switch (value) { case "idle", "active", "spent", "open", "closed", "ready" -> tr("state." + value); default -> Component.literal(value); };
    }
    private static String properties(MechanicBlockPredicate predicate) {
        return predicate.getProperties().entrySet().stream().sorted(java.util.Map.Entry.comparingByKey()).map(entry -> entry.getKey() + "=" + entry.getValue()).collect(java.util.stream.Collectors.joining(", "));
    }
    private static Component tr(String key, Object... args) { return Component.translatable("worldsmith.mechanics.guide." + key, args); }
    private void add(List<Row> rows, Component text, int color) { for (var line : font.split(text, Math.max(30, layout.contentWidth() - 14))) rows.add(new Row(line, color, null, 12)); }
    private static void blank(List<Row> rows) { rows.add(new Row(FormattedCharSequence.EMPTY, 0, null, 6)); }
    private record Row(FormattedCharSequence text, int color, Integer gridZ, int height) {}
    private int viewport() { return Math.max(1, layout.contentBottom() - layout.contentTop()); }
    private void move(int delta) { scroll = MechanicGuideLayout.scroll(scroll, delta, contentHeight, viewport()); }

    @Override public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (layout != null && mouseY >= layout.contentTop() && mouseY < layout.contentBottom()) { move(-(int)Math.round(scrollY * 24)); return true; }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }
    @Override public boolean keyPressed(KeyEvent event) {
        if (QuestJournalClient.matchesOpenKey(event)) { MechanicGuideClient.reset(); minecraft.gui.setScreen(null); return true; }
        if (layout != null) {
            if (event.key() == GLFW.GLFW_KEY_PAGE_DOWN || event.key() == GLFW.GLFW_KEY_PAGE_UP) { move((event.key() == GLFW.GLFW_KEY_PAGE_DOWN ? 1 : -1) * Math.max(24, viewport() - 12)); return true; }
            if (event.key() == GLFW.GLFW_KEY_HOME || event.key() == GLFW.GLFW_KEY_END) { scroll = event.key() == GLFW.GLFW_KEY_HOME ? 0 : Math.max(0, contentHeight - viewport()); return true; }
        }
        return super.keyPressed(event);
    }
    @Override public void onClose() { MechanicGuideClient.reset(); minecraft.gui.setScreen(QuestJournalClient.current(scope, connection) ? parent : null); }
    @Override public boolean isPauseScreen() { return false; }
    @Override public boolean isInGameUi() { return true; }
}

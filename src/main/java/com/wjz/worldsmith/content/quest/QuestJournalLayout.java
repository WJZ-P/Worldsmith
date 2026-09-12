package com.wjz.worldsmith.content.quest;

/** GUI-scaled geometry, kept independent of rendering so small-window pagination is testable. */
public record QuestJournalLayout(int bodyTop, int bodyBottom, int listWidth, int detailX, int detailWidth, int pageSize) {
    public static QuestJournalLayout of(int width, int height) {
        int top = height < 270 ? 40 : 50;
        // Only action buttons and transient two-line feedback live below the panels.
        int bottom = height - 54;
        int list = Math.max(110, Math.min(216, width / 3));
        int detail = 24 + list;
        int rows = Math.max(1, (bottom - top - 27) / 26);
        return new QuestJournalLayout(top, bottom, list, detail, width - detail - 12, rows);
    }
}

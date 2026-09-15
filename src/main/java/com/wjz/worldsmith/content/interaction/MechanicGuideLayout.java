package com.wjz.worldsmith.content.interaction;

/** Pure full-width guide geometry: large diagrams scroll vertically, narrow ones become cell lists. */
public record MechanicGuideLayout(int left, int contentTop, int contentBottom, int contentWidth, int cellSize, boolean cellList) {
    public static MechanicGuideLayout of(int width, int height, int controlRows, int columns) {
        int left = Math.min(12, Math.max(4, width / 20));
        int available = Math.max(30, width - 2 * left);
        int cell = Math.min(22, Math.max(1, (available - 34) / Math.max(1, columns)));
        return new MechanicGuideLayout(left, 28 + 22 * controlRows, Math.max(32, height - 34), available, cell, cell < 14);
    }

    public static int scroll(int current, int delta, int contentHeight, int viewport) {
        return Math.max(0, Math.min(Math.max(0, contentHeight - Math.max(0, viewport)), current + delta));
    }
}

package com.wyattfleming.frametv;

/** Keeps vertical remote navigation inside a visible favorites row, even when it is partial. */
public final class FavoriteGridNavigation {
    private FavoriteGridNavigation() {}

    public static int verticalNeighbor(int index, int count, int columns, int direction) {
        if (index < 0 || index >= count || columns < 1 || (direction != -1 && direction != 1)) return -1;
        int targetRow = index / columns + direction;
        if (targetRow < 0 || targetRow > (count - 1) / columns) return -1;
        int targetStart = targetRow * columns;
        return Math.min(targetStart + index % columns, count - 1);
    }
}

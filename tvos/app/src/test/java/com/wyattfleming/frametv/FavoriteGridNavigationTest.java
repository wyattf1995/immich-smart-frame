package com.wyattfleming.frametv;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class FavoriteGridNavigationTest {
    @Test
    public void followsTheTvFiveColumnLayoutWithFullAndPartialRows() {
        assertEquals(9, neighbor(4, 10, 5, 1));
        assertEquals(4, neighbor(9, 10, 5, -1));
        assertEquals(8, neighbor(4, 9, 5, 1));
        assertEquals(5, neighbor(4, 6, 5, 1));
        assertEquals(-1, neighbor(4, 5, 5, 1));
    }

    @Test
    public void movesVerticallyAcrossTwoFullRowsOfSixFavorites() {
        assertEquals(3, neighbor(0, 6, 3, 1));
        assertEquals(1, neighbor(4, 6, 3, -1));
        assertEquals(-1, neighbor(5, 6, 3, 1));
    }

    @Test
    public void clampsDownwardToTheLastFavoriteInAPartialRow() {
        assertEquals(6, neighbor(5, 7, 3, 1));
        assertEquals(6, neighbor(4, 7, 3, 1));
    }

    @Test
    public void keepsFocusInsideTenFavoriteGridInsteadOfExitingToHome() {
        assertEquals(9, neighbor(5, 10, 4, 1));
        assertEquals(9, neighbor(7, 10, 4, 1));
        assertEquals(1, neighbor(5, 10, 4, -1));
    }

    @Test
    public void supportsMultipleFullRowsAndTopBottomExits() {
        assertEquals(10, neighbor(6, 12, 4, 1));
        assertEquals(2, neighbor(6, 12, 4, -1));
        assertEquals(-1, neighbor(2, 12, 4, -1));
        assertEquals(-1, neighbor(11, 12, 4, 1));
    }

    @Test
    public void rejectsInvalidIndicesCountsColumnsAndDirections() {
        assertEquals(-1, neighbor(-1, 7, 3, 1));
        assertEquals(-1, neighbor(7, 7, 3, 1));
        assertEquals(-1, neighbor(0, 0, 3, 1));
        assertEquals(-1, neighbor(0, -1, 3, 1));
        assertEquals(-1, neighbor(0, 7, 0, 1));
        assertEquals(-1, neighbor(0, 7, -1, 1));
        assertEquals(-1, neighbor(0, 7, 3, 0));
        assertEquals(-1, neighbor(0, 7, 3, 2));
    }

    private static int neighbor(int index, int count, int columns, int direction) {
        return FavoriteGridNavigation.verticalNeighbor(index, count, columns, direction);
    }
}

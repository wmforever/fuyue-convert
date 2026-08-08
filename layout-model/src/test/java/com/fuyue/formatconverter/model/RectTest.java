package com.fuyue.formatconverter.model;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RectTest {
    @Test void computesIntersectionAndUnion() {
        Rect a = new Rect(0, 0, 10, 10);
        Rect b = new Rect(5, 5, 10, 10);
        assertEquals(25d, a.intersectionArea(b));
        assertEquals(new Rect(0, 0, 15, 15), a.union(b));
        assertTrue(a.contains(new Point(10.1, 5), 0.2));
    }

    @Test void rejectsNegativeSize() {
        assertThrows(IllegalArgumentException.class, () -> new Rect(0, 0, -1, 1));
    }

    @Test void derivesPhysicalRowsFromTableGrid() {
        TableModel table = new TableModel("table", 1, new Rect(10, 20, 50, 20),
                List.of(10d, 35d, 60d), List.of(20d, 30d, 40d),
                List.of(), List.of(), 1d, List.of());

        assertEquals(2, table.rows().size());
        assertEquals(new Rect(10, 30, 50, 10), table.rows().get(1).box());
    }
}

package cn.tensafe.ofd2word.model;

import java.util.List;

/** A physical table row independent of any DOCX implementation. */
public record RowModel(int index, Rect box, double heightMm, boolean exactHeight,
                       List<CellModel> cells) {
    public RowModel {
        if (index < 0) throw new IllegalArgumentException("Row index must be non-negative");
        if (heightMm < 0) throw new IllegalArgumentException("Row height must be non-negative");
        cells = cells == null ? List.of() : List.copyOf(cells);
    }
}

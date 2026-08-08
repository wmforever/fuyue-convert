package com.fuyue.formatconverter.table;

import com.fuyue.formatconverter.model.PageModel;
import com.fuyue.formatconverter.model.TableModel;

import java.util.List;

public interface BorderlessTableRecognizer {
    List<TableModel> recognize(PageModel page);
}

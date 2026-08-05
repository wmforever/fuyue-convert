package cn.tensafe.ofd2word.table;

import cn.tensafe.ofd2word.model.PageModel;
import cn.tensafe.ofd2word.model.TableModel;

import java.util.List;

public interface BorderlessTableRecognizer {
    List<TableModel> recognize(PageModel page);
}


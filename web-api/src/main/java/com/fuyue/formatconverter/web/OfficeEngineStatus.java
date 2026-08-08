package com.fuyue.formatconverter.web;

public record OfficeEngineStatus(boolean enabled, boolean available, String binary, String message) {
    public static OfficeEngineStatus disabled() {
        return new OfficeEngineStatus(false, false, null, "Office 引擎未启用");
    }

    public static OfficeEngineStatus available(String binary) {
        return new OfficeEngineStatus(true, true, binary, "Office 引擎可用");
    }

    public static OfficeEngineStatus unavailable() {
        return new OfficeEngineStatus(true, false, null, "未发现可用的 LibreOffice/soffice 可执行文件");
    }
}

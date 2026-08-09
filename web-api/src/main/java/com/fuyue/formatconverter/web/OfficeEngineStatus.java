package com.fuyue.formatconverter.web;

public record OfficeEngineStatus(boolean enabled, boolean available, String binary, String version, String message) {
    public static OfficeEngineStatus disabled() {
        return new OfficeEngineStatus(false, false, null, null, "Office 引擎未启用");
    }

    public static OfficeEngineStatus available(String binary, String version) {
        return new OfficeEngineStatus(true, true, binary, version, "Office 引擎可用");
    }

    public static OfficeEngineStatus unavailable() {
        return new OfficeEngineStatus(true, false, null, null, "未发现可用的 LibreOffice/soffice 可执行文件");
    }

    public static OfficeEngineStatus incompatible(String version, String requiredVersion) {
        return new OfficeEngineStatus(true, false, null, version,
                "LibreOffice 版本不符合要求：需要 " + requiredVersion);
    }
}

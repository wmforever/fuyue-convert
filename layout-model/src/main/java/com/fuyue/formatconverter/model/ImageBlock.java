package com.fuyue.formatconverter.model;

import java.util.Arrays;

public record ImageBlock(String id, int pageNumber, Rect box, String mimeType, byte[] data,
                         String role, int zOrder) {
    public ImageBlock {
        id = id == null ? "" : id;
        mimeType = mimeType == null ? "application/octet-stream" : mimeType;
        data = data == null ? new byte[0] : Arrays.copyOf(data, data.length);
        role = role == null ? "IMAGE" : role;
    }

    @Override public byte[] data() { return Arrays.copyOf(data, data.length); }
}


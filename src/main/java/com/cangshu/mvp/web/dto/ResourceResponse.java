package com.cangshu.mvp.web.dto;

import com.cangshu.mvp.domain.ResourceItem;

import java.time.Instant;
import java.time.format.DateTimeFormatter;

public record ResourceResponse(
        Long id,
        String originalName,
        String contentType,
        long sizeBytes,
        String sha256,
        String createdAt) {

    public static ResourceResponse from(ResourceItem item) {
        return new ResourceResponse(
                item.getId(),
                item.getOriginalName(),
                item.getContentType(),
                item.getSizeBytes() == null ? 0L : item.getSizeBytes(),
                item.getSha256(),
                DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(item.getCreatedAt())));
    }
}

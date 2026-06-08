package com.council.api.dto;

import org.springframework.data.domain.Page;

import java.util.List;

/**
 * Stable paginated response for trace listings.
 */
public record TracePageResponse(
        List<TraceSummaryResponse> content,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean first,
        boolean last,
        boolean hasNext,
        boolean hasPrevious
) {
    public static TracePageResponse from(Page<TraceSummaryResponse> page) {
        return new TracePageResponse(
                page.getContent(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages(),
                page.isFirst(),
                page.isLast(),
                page.hasNext(),
                page.hasPrevious()
        );
    }
}

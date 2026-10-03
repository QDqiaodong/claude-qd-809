package com.blood.center.dto;

import java.time.LocalDateTime;

/**
 * 输血不良反应处置事件视图（含同源隔离 / 释放 / 报废 / 追溯 的袋数汇总）。
 */
public record ReactionEventVO(Long id,
                              String eventNo,
                              Long matchId,
                              String matchNo,
                              Long bagId,
                              String bagNo,
                              String bloodType,
                              String component,
                              String componentName,
                              Long collectionId,
                              String hospital,
                              String reporter,
                              String symptoms,
                              LocalDateTime occurredAt,
                              LocalDateTime reportedAt,
                              String status,
                              String statusName,
                              String conclusion,
                              String conclusionName,
                              String reviewer,
                              LocalDateTime reviewedAt,
                              String reviewNote,
                              int isolatedCount,
                              int releasedCount,
                              int scrappedCount,
                              int traceCount) {
}

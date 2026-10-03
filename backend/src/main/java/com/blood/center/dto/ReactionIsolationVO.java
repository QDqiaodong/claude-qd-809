package com.blood.center.dto;

import java.time.LocalDateTime;

/**
 * 同源血袋隔离 / 追溯记录视图：登记时状态 + 当前处置结果。
 */
public record ReactionIsolationVO(Long id,
                                  Long bagId,
                                  String bagNo,
                                  String bloodType,
                                  String component,
                                  String componentName,
                                  Integer volumeMl,
                                  String registerStatus,
                                  String registerStatusName,
                                  String status,
                                  String statusName,
                                  LocalDateTime createdAt,
                                  LocalDateTime resolvedAt) {
}

package com.blood.center.dto;

import java.time.LocalDateTime;

/**
 * 处置台账步骤视图。
 */
public record ReactionStepVO(Long id,
                             String action,
                             String actionName,
                             String bagNo,
                             String detail,
                             String operator,
                             LocalDateTime createdAt) {
}

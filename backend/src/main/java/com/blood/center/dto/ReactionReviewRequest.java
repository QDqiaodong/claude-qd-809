package com.blood.center.dto;

/**
 * 质量复核请求：EXCLUDED 排除关联 / CONFIRMED 确认风险。
 */
public record ReactionReviewRequest(String conclusion, String reviewer, String note) {
}

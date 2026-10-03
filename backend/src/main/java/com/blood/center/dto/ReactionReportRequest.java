package com.blood.center.dto;

import java.time.LocalDateTime;

/**
 * 输血不良反应上报请求：针对一条已发血记录登记症状、发生时间、上报医院与处置人。
 */
public record ReactionReportRequest(Long matchId,
                                    String symptoms,
                                    LocalDateTime occurredAt,
                                    String hospital,
                                    String reporter) {
}

package com.blood.center.dto;

/**
 * 上报结果：duplicated=true 表示相同反应已存在，合并为同一处置事件；
 * siblingEventNo 非空表示该发血记录已有其他在办事件（本次作为独立事件登记）。
 */
public record ReactionReportVO(ReactionEventVO event,
                               boolean duplicated,
                               String siblingEventNo) {
}

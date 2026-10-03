package com.blood.center.dto;

import java.util.List;

/**
 * 事件详情：事件本体 + 来源链 + 同源隔离清单 + 处置台账。
 */
public record ReactionDetailVO(ReactionEventVO event,
                               SourceChainVO chain,
                               List<ReactionIsolationVO> isolations,
                               List<ReactionStepVO> steps) {
}

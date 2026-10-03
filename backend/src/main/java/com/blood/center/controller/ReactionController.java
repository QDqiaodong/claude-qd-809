package com.blood.center.controller;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.blood.center.common.ApiResponse;
import com.blood.center.dto.ReactionDetailVO;
import com.blood.center.dto.ReactionEventVO;
import com.blood.center.dto.ReactionReportRequest;
import com.blood.center.dto.ReactionReportVO;
import com.blood.center.dto.ReactionReviewRequest;
import com.blood.center.dto.SourceChainVO;
import com.blood.center.service.ReactionService;

/**
 * 模块六：输血不良反应追溯台 —— 医院上报、来源链反查、同源隔离、质量复核与处置台账。
 */
@RestController
@RequestMapping("/api/reactions")
public class ReactionController {

    private final ReactionService reactionService;

    public ReactionController(ReactionService reactionService) {
        this.reactionService = reactionService;
    }

    /**
     * 医院上报输血不良反应：相同发血记录 + 发生时间 + 症状 ⇒ 合并为同一事件；
     * 内容有差异 ⇒ 独立事件并提示已有在办事件；建单即隔离同源未发出血袋。
     */
    @PostMapping
    public ApiResponse<ReactionReportVO> report(@RequestBody ReactionReportRequest request) {
        ReactionReportVO result = reactionService.report(request);
        String message;
        if (result.duplicated()) {
            message = "相同反应已上报过（事件 " + result.event().eventNo()
                    + "），已合并为同一处置事件，未重复建单";
        } else if (result.siblingEventNo() != null) {
            message = "已登记为独立事件 " + result.event().eventNo()
                    + "；注意：该发血记录已有在办事件 " + result.siblingEventNo();
        } else {
            message = "不良反应事件 " + result.event().eventNo() + " 已建立，同源未发出血袋已立即隔离";
        }
        return ApiResponse.success(message, result);
    }

    /** 处置事件台账（可按血型过滤） */
    @GetMapping
    public ApiResponse<List<ReactionEventVO>> list(@RequestParam(required = false) String bloodType) {
        return ApiResponse.success(reactionService.listEvents(bloodType));
    }

    /** 事件详情：来源链 + 同源隔离清单 + 处置台账 */
    @GetMapping("/{id}")
    public ApiResponse<ReactionDetailVO> detail(@PathVariable Long id) {
        return ApiResponse.success(reactionService.detail(id));
    }

    /** 质量复核：EXCLUDED 排除关联（释放合格血袋）/ CONFIRMED 确认风险（隔离血袋转报废） */
    @PostMapping("/{id}/review")
    public ApiResponse<ReactionDetailVO> review(@PathVariable Long id, @RequestBody ReactionReviewRequest request) {
        return ApiResponse.success("质量复核已登记，处置链已更新", reactionService.review(id, request));
    }

    /** 来源链反查：发血记录 → 供血血袋 → 制备批次 → 采血记录 → 检验标本 */
    @GetMapping("/trace/{matchId}")
    public ApiResponse<SourceChainVO> trace(@PathVariable Long matchId) {
        return ApiResponse.success(reactionService.trace(matchId));
    }
}

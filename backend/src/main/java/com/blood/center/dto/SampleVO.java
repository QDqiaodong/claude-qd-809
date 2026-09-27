package com.blood.center.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 标本检验视图：6 个检验项目的结果值 / 阴阳性判定 / 复核人 / 状态。
 */
public record SampleVO(Long id,
                       String barcode,
                       Long collectionId,
                       String collectNo,
                       String donorName,
                       String bloodType,
                       BigDecimal altValue,
                       BigDecimal hbValue,
                       String hbvResult,
                       String hcvResult,
                       String hivResult,
                       String syphilisResult,
                       String reviewer,
                       String status,
                       String statusName,
                       LocalDateTime testedAt,
                       LocalDateTime releasedAt,
                       List<ItemVO> items) {

    /**
     * 单个检验项目的结果展示（ALT / 血红蛋白 / HBV / HCV / HIV / 梅毒）
     */
    public record ItemVO(String code, String name, String value, String unit, String conclusion) {
    }
}

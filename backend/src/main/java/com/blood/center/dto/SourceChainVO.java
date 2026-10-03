package com.blood.center.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 来源链：从一条已发血记录反查 供血血袋 → 制备批次 → 采血记录（献血者）→ 检验标本。
 * 任一环节缺失（如手工入库血袋无采血来源）时对应节点为 null。
 */
public record SourceChainVO(MatchNode match,
                            BagNode bag,
                            BatchNode batch,
                            CollectionNode collection,
                            SampleNode sample) {

    /** 发血记录 */
    public record MatchNode(Long id,
                            String matchNo,
                            String hospital,
                            String recipient,
                            String bloodType,
                            String component,
                            String componentName,
                            Integer unitsU,
                            LocalDateTime issueTime,
                            String issueOperator) {
    }

    /** 供血血袋 */
    public record BagNode(Long id,
                          String bagNo,
                          String bloodType,
                          String component,
                          String componentName,
                          Integer volumeMl,
                          LocalDate collectDate,
                          LocalDate expiryDate,
                          String status,
                          String statusName,
                          String donorName) {
    }

    /** 制备批次 */
    public record BatchNode(Long id,
                            String batchNo,
                            String bloodType,
                            Integer rbcBags,
                            Integer rbcVolumeMl,
                            Integer plasmaBags,
                            Integer plasmaVolumeMl,
                            Integer plateletBags,
                            Integer plateletVolumeMl,
                            Integer cryoBags,
                            Integer cryoVolumeMl,
                            String operator,
                            LocalDateTime preparedTime,
                            String status) {
    }

    /** 采血记录（含献血者） */
    public record CollectionNode(Long id,
                                 String collectNo,
                                 String stationCode,
                                 Integer volumeMl,
                                 LocalDate collectDate,
                                 String nurse,
                                 String status,
                                 String statusName,
                                 String donorNo,
                                 String donorName,
                                 String donorBloodType,
                                 String donorGender,
                                 Integer donorAge) {
    }

    /** 检验标本 */
    public record SampleNode(Long id,
                             String barcode,
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
                             LocalDateTime releasedAt) {
    }
}

package com.blood.center.domain;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * 输血不良反应处置事件：医院针对一条已发血记录上报反应。
 * 同一发血记录 + 相同发生时间 + 相同症状 ⇒ 幂等合并为一宗事件（dedup_hash 唯一索引兜底并发）；
 * 内容有差异 ⇒ 独立事件。事件建立即对同一采血来源仍未发出的成分血袋建立同源隔离。
 */
@Entity
@Table(name = "reaction_event")
@Getter
@Setter
public class ReactionEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_no", nullable = false, unique = true, length = 32)
    private String eventNo;

    /** 发血记录（已出库的配血单） */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "match_id")
    private CrossMatch match;

    @Column(name = "match_no", length = 32)
    private String matchNo;

    /** 供血血袋 */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "bag_id")
    private BloodBag bag;

    @Column(name = "bag_no", length = 32)
    private String bagNo;

    @Column(name = "blood_type", nullable = false, length = 4)
    private String bloodType;

    @Column(name = "component", nullable = false, length = 8)
    private String component;

    /** 采血来源（同源隔离的依据） */
    @Column(name = "collection_id")
    private Long collectionId;

    /** 上报医院 */
    @Column(name = "hospital", nullable = false, length = 128)
    private String hospital;

    /** 处置人（上报人） */
    @Column(name = "reporter", nullable = false, length = 32)
    private String reporter;

    /** 反应症状 */
    @Column(name = "symptoms", nullable = false, length = 255)
    private String symptoms;

    /** 反应发生时间 */
    @Column(name = "occurred_at", nullable = false)
    private LocalDateTime occurredAt;

    /** 上报时间 */
    @Column(name = "reported_at", nullable = false)
    private LocalDateTime reportedAt;

    /** 幂等指纹：发血记录 + 发生时间 + 规范化症状 的 SHA-256 */
    @Column(name = "dedup_hash", nullable = false, unique = true, length = 64)
    private String dedupHash;

    /** OPEN 在办 / EXCLUDED 已排除关联 / CONFIRMED 已确认风险 */
    @Column(name = "status", nullable = false, length = 16)
    private String status;

    /** 复核结论 EXCLUDED 排除关联 / CONFIRMED 确认风险 */
    @Column(name = "conclusion", length = 16)
    private String conclusion;

    /** 质量复核人 */
    @Column(name = "reviewer", length = 32)
    private String reviewer;

    @Column(name = "reviewed_at")
    private LocalDateTime reviewedAt;

    @Column(name = "review_note", length = 255)
    private String reviewNote;

    @Column(name = "created_at")
    private LocalDateTime createdAt;
}

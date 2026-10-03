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
 * 处置台账：一宗事件从上报、同源隔离到复核释放 / 报废的逐步记录（只增不改）。
 */
@Entity
@Table(name = "reaction_step")
@Getter
@Setter
public class ReactionStep {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "event_id")
    private ReactionEvent event;

    /** REPORT 上报 / ISOLATE 隔离 / TRACE 追溯 / RELEASE 释放 / SCRAP 报废 / KEEP 保持隔离 / REVIEW 复核 */
    @Column(name = "action", nullable = false, length = 16)
    private String action;

    @Column(name = "bag_no", length = 32)
    private String bagNo;

    @Column(name = "detail", nullable = false, length = 255)
    private String detail;

    @Column(name = "operator", length = 32)
    private String operator;

    @Column(name = "created_at")
    private LocalDateTime createdAt;
}

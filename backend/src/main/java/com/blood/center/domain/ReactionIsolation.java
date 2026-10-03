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
 * 同源血袋隔离 / 追溯记录：一宗事件 × 一条同源血袋 一行。
 * 仍未发出的血袋建立隔离（ISOLATED）；已发出、已报废的血袋只进追溯清单（TRACE），不改写血袋状态。
 */
@Entity
@Table(name = "reaction_isolation")
@Getter
@Setter
public class ReactionIsolation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "event_id")
    private ReactionEvent event;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "bag_id")
    private BloodBag bag;

    @Column(name = "bag_no", nullable = false, length = 32)
    private String bagNo;

    @Column(name = "blood_type", nullable = false, length = 4)
    private String bloodType;

    @Column(name = "component", nullable = false, length = 8)
    private String component;

    @Column(name = "volume_ml")
    private Integer volumeMl;

    /** 登记时血袋状态（AVAILABLE / ISOLATED / ISSUED / SCRAPPED） */
    @Column(name = "bag_status_at_register", nullable = false, length = 16)
    private String bagStatusAtRegister;

    /** ISOLATED 隔离中 / RELEASED 已释放 / SCRAPPED 已报废 / TRACE 仅追溯 */
    @Column(name = "status", nullable = false, length = 16)
    private String status;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    /** 了结时间（释放 / 转报废 / 追溯登记） */
    @Column(name = "resolved_at")
    private LocalDateTime resolvedAt;
}

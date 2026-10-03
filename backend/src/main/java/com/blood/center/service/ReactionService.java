package com.blood.center.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.blood.center.common.BizException;
import com.blood.center.common.BloodDict;
import com.blood.center.domain.BloodBag;
import com.blood.center.domain.CollectionRecord;
import com.blood.center.domain.CrossMatch;
import com.blood.center.domain.Donor;
import com.blood.center.domain.PreparationBatch;
import com.blood.center.domain.ReactionEvent;
import com.blood.center.domain.ReactionIsolation;
import com.blood.center.domain.ReactionStep;
import com.blood.center.domain.ScrapRecord;
import com.blood.center.domain.TestSample;
import com.blood.center.dto.ReactionDetailVO;
import com.blood.center.dto.ReactionEventVO;
import com.blood.center.dto.ReactionIsolationVO;
import com.blood.center.dto.ReactionReportRequest;
import com.blood.center.dto.ReactionReportVO;
import com.blood.center.dto.ReactionReviewRequest;
import com.blood.center.dto.ReactionStepVO;
import com.blood.center.dto.SourceChainVO;
import com.blood.center.repository.BloodBagRepository;
import com.blood.center.repository.CrossMatchRepository;
import com.blood.center.repository.ReactionEventRepository;
import com.blood.center.repository.ReactionIsolationRepository;
import com.blood.center.repository.ReactionStepRepository;
import com.blood.center.repository.ScrapRecordRepository;
import com.blood.center.repository.TestSampleRepository;

/**
 * 输血不良反应追溯台：上报 → 同源隔离 → 质量复核（排除关联 / 确认风险）→ 台账。
 *
 * 闭环要点：
 * 1. 同一发血记录 + 相同发生时间 + 相同症状 ⇒ 幂等合并为一宗事件（dedup_hash 唯一索引兜底并发）；
 *    内容有差异 ⇒ 独立事件并提示已有在办事件。
 * 2. 事件建立即对同一采血来源仍未发出的成分血袋建立同源隔离（AVAILABLE → ISOLATED），
 *    配血与发血都会拒绝这些血袋；已发出 / 已报废的血袋只进追溯清单，不改写状态。
 * 3. 复核「排除关联」只释放仍满足原检验放行、未过期且未被其他在办事件隔离的血袋；
 *    「确认风险」把隔离血袋转报废并保留处置链。
 * 4. 事件行锁 + 血袋行锁（均按 id 升序）串行化并发复核与上报，杜绝重复报废、重复扣减与状态倒退。
 */
@Service
public class ReactionService {

    private static final DateTimeFormatter SEQ_DAY = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final DateTimeFormatter MINUTE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final ReactionEventRepository eventRepository;
    private final ReactionIsolationRepository isolationRepository;
    private final ReactionStepRepository stepRepository;
    private final CrossMatchRepository matchRepository;
    private final BloodBagRepository bagRepository;
    private final TestSampleRepository sampleRepository;
    private final ScrapRecordRepository scrapRepository;
    private final InventoryService inventoryService;
    private final TransactionTemplate transactionTemplate;

    public ReactionService(ReactionEventRepository eventRepository,
                           ReactionIsolationRepository isolationRepository,
                           ReactionStepRepository stepRepository,
                           CrossMatchRepository matchRepository,
                           BloodBagRepository bagRepository,
                           TestSampleRepository sampleRepository,
                           ScrapRecordRepository scrapRepository,
                           InventoryService inventoryService,
                           org.springframework.transaction.PlatformTransactionManager txManager) {
        this.eventRepository = eventRepository;
        this.isolationRepository = isolationRepository;
        this.stepRepository = stepRepository;
        this.matchRepository = matchRepository;
        this.bagRepository = bagRepository;
        this.sampleRepository = sampleRepository;
        this.scrapRepository = scrapRepository;
        this.inventoryService = inventoryService;
        this.transactionTemplate = new TransactionTemplate(txManager);
    }

    // ---------------- 上报 ----------------

    /**
     * 医院上报输血不良反应。重复上报（相同发血记录 + 发生时间 + 症状）返回已有事件；
     * 并发撞唯一索引时改读已存在的事件，保证「只形成一宗处置事件」。
     */
    public ReactionReportVO report(ReactionReportRequest request) {
        if (request == null || request.matchId() == null) {
            throw new BizException("发血记录不能为空");
        }
        String symptoms = normalizeSymptoms(request.symptoms());
        if (symptoms.isEmpty()) {
            throw new BizException("反应症状不能为空");
        }
        if (request.occurredAt() == null) {
            throw new BizException("反应发生时间不能为空");
        }
        if (request.hospital() == null || request.hospital().isBlank()) {
            throw new BizException("上报医院不能为空");
        }
        if (request.reporter() == null || request.reporter().isBlank()) {
            throw new BizException("处置人不能为空");
        }
        String dedupHash = dedupHash(request.matchId(), request.occurredAt(), symptoms);

        // 幂等预检：相同上报直接返回已有事件
        if (eventRepository.findByDedupHash(dedupHash).isPresent()) {
            return duplicatedResult(dedupHash);
        }
        // 建单 + 同源隔离在同一事务内完成；唯一索引兜底并发，撞约束时改读已有事件
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                return transactionTemplate.execute(status -> doReport(request, symptoms, dedupHash));
            } catch (DataIntegrityViolationException conflict) {
                if (eventRepository.findByDedupHash(dedupHash).isPresent()) {
                    return duplicatedResult(dedupHash);      // 并发相同上报 ⇒ 合并
                }
                if (attempt == 2) {
                    throw conflict;                          // 事件编号连续竞争，如实抛出
                }
            }
        }
        throw new BizException("上报失败，请重试");
    }

    /**
     * 事务内建单：登记事件、锁定同源血袋并建立隔离、写台账、同步库存。
     */
    private ReactionReportVO doReport(ReactionReportRequest request, String symptoms, String dedupHash) {
        CrossMatch match = matchRepository.findById(request.matchId())
                .orElseThrow(() -> new BizException("发血记录不存在：id=" + request.matchId()));
        if (!Boolean.TRUE.equals(match.getIssued())) {
            throw new BizException("配血单 " + match.getMatchNo() + " 尚未发血出库，不能上报输血反应");
        }
        BloodBag sourceBag = match.getBag();
        if (sourceBag == null) {
            throw new BizException("配血单 " + match.getMatchNo() + " 未关联供血血袋，无法追溯");
        }

        // 同源血袋：同一采血记录分离出的全部成分血袋；按 id 升序加行锁（与复核路径同序，避免死锁）
        List<BloodBag> sourceBags = sourceBag.getCollection() == null
                ? List.of(sourceBag)
                : bagRepository.findByCollectionIdOrderByIdAsc(sourceBag.getCollection().getId());
        List<Long> bagIds = sourceBags.stream().map(BloodBag::getId).sorted().toList();
        List<BloodBag> lockedBags = bagRepository.findAllByIdForUpdate(bagIds);

        LocalDateTime now = LocalDateTime.now();
        ReactionEvent event = new ReactionEvent();
        event.setEventNo("RE" + LocalDate.now().format(SEQ_DAY) + "-"
                + String.format("%03d", eventRepository.count() + 1));
        event.setMatch(match);
        event.setMatchNo(match.getMatchNo());
        event.setBag(sourceBag);
        event.setBagNo(sourceBag.getBagNo());
        event.setBloodType(match.getBloodType());
        event.setComponent(match.getComponent());
        event.setCollectionId(sourceBag.getCollection() == null ? null : sourceBag.getCollection().getId());
        event.setHospital(request.hospital().trim());
        event.setReporter(request.reporter().trim());
        event.setSymptoms(symptoms);
        event.setOccurredAt(request.occurredAt());
        event.setReportedAt(now);
        event.setDedupHash(dedupHash);
        event.setStatus("OPEN");
        event.setCreatedAt(now);
        ReactionEvent saved = eventRepository.save(event);

        List<ReactionStep> steps = new ArrayList<>();
        List<ReactionIsolation> isolations = new ArrayList<>();
        steps.add(step(saved, "REPORT", sourceBag.getBagNo(),
                "医院「" + saved.getHospital() + "」上报输血不良反应：" + symptoms
                        + "（发生时间 " + saved.getOccurredAt().format(MINUTE) + "）", saved.getReporter()));

        Set<String> affectedCells = new HashSet<>();
        for (BloodBag bag : lockedBags) {
            ReactionIsolation isolation = new ReactionIsolation();
            isolation.setEvent(saved);
            isolation.setBag(bag);
            isolation.setBagNo(bag.getBagNo());
            isolation.setBloodType(bag.getBloodType());
            isolation.setComponent(bag.getComponent());
            isolation.setVolumeMl(bag.getVolumeMl());
            isolation.setBagStatusAtRegister(bag.getStatus());
            isolation.setCreatedAt(now);
            if ("AVAILABLE".equals(bag.getStatus())) {
                // 仍未发出 ⇒ 立即隔离，配血与发血都必须拒绝
                bag.markIsolated();
                bagRepository.save(bag);
                isolation.setStatus("ISOLATED");
                steps.add(step(saved, "ISOLATE", bag.getBagNo(),
                        "同源血袋隔离：" + bag.getBagNo() + "（" + BloodDict.componentName(bag.getComponent())
                                + "）可用 → 隔离中，禁止配血与发血", "系统自动"));
                affectedCells.add(bag.getBloodType() + "|" + bag.getComponent());
            } else if ("ISOLATED".equals(bag.getStatus())) {
                // 已被其他事件隔离 ⇒ 本事件叠加登记，任一事件未结束前都不得释放
                isolation.setStatus("ISOLATED");
                steps.add(step(saved, "ISOLATE", bag.getBagNo(),
                        "同源血袋 " + bag.getBagNo() + " 已在隔离中，本事件叠加登记隔离", "系统自动"));
            } else {
                // 已发出 / 已报废 ⇒ 只进追溯清单，不改写血袋状态
                isolation.setStatus("TRACE");
                isolation.setResolvedAt(now);
                steps.add(step(saved, "TRACE", bag.getBagNo(),
                        "血袋 " + bag.getBagNo() + " 状态为「" + BloodDict.bagStatusName(bag.getStatus())
                                + "」，仅登记追溯清单，不改写状态", "系统自动"));
            }
            isolations.add(isolationRepository.save(isolation));
        }
        steps.forEach(stepRepository::save);

        // 同源隔离立即反映到库存（可用数扣减）
        for (String cell : affectedCells) {
            String[] parts = cell.split("\\|");
            inventoryService.syncInventory(parts[0], parts[1]);
        }

        // 同一发血记录已有其他在办事件 ⇒ 提示
        String sibling = eventRepository.findOpenByMatchId(match.getId()).stream()
                .filter(e -> !e.getId().equals(saved.getId()))
                .map(ReactionEvent::getEventNo)
                .findFirst()
                .orElse(null);
        return new ReactionReportVO(toVO(saved, isolations), false, sibling);
    }

    /** 重复上报 ⇒ 返回已存在的同一宗事件 */
    private ReactionReportVO duplicatedResult(String dedupHash) {
        return transactionTemplate.execute(status -> {
            ReactionEvent event = eventRepository.findByDedupHash(dedupHash)
                    .orElseThrow(() -> new BizException("不良反应事件读取失败"));
            return new ReactionReportVO(
                    toVO(event, isolationRepository.findByEventIdOrderByIdAsc(event.getId())), true, null);
        });
    }

    // ---------------- 质量复核 ----------------

    /**
     * 质量复核：排除关联 ⇒ 只释放仍满足放行条件的血袋；确认风险 ⇒ 隔离血袋转报废。
     * 事件行锁保证并发复核 / 重复提交只有一单生效。
     */
    @Transactional
    public ReactionDetailVO review(Long id, ReactionReviewRequest request) {
        ReactionEvent event = eventRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new BizException("不良反应事件不存在：id=" + id));
        if (!"OPEN".equals(event.getStatus())) {
            throw new BizException("事件 " + event.getEventNo() + " 已由 " + event.getReviewer() + " 复核结案（"
                    + BloodDict.reactionStatusName(event.getStatus()) + "），请勿重复提交");
        }
        if (request == null || !BloodDict.isValidReactionConclusion(request.conclusion())) {
            throw new BizException("未知的复核结论：" + (request == null ? null : request.conclusion())
                    + "（可选 EXCLUDED 排除关联 / CONFIRMED 确认风险）");
        }
        if (request.reviewer() == null || request.reviewer().isBlank()) {
            throw new BizException("质量复核人不能为空");
        }
        String reviewer = request.reviewer().trim();
        boolean excluded = "EXCLUDED".equals(request.conclusion());
        LocalDateTime now = LocalDateTime.now();

        List<ReactionIsolation> isolations = isolationRepository.findByEventIdOrderByIdAsc(event.getId());
        // 按 id 升序锁定本事件涉及的血袋（与上报路径同序），并发复核 / 并发上报不会交错
        List<Long> bagIds = isolations.stream().map(i -> i.getBag().getId()).sorted().toList();
        Map<Long, BloodBag> lockedBags = bagIds.isEmpty()
                ? Map.of()
                : bagRepository.findAllByIdForUpdate(bagIds).stream()
                        .collect(Collectors.toMap(BloodBag::getId, Function.identity()));

        List<ReactionStep> steps = new ArrayList<>();
        Set<String> affectedCells = new HashSet<>();
        steps.add(step(event, "REVIEW", null,
                "质量复核结论：" + BloodDict.reactionStatusName(request.conclusion())
                        + (request.note() == null || request.note().isBlank() ? "" : "（" + request.note().trim() + "）"),
                reviewer));

        for (ReactionIsolation isolation : isolations) {
            if (!"ISOLATED".equals(isolation.getStatus())) {
                continue;    // 仅追溯（已发出 / 已报废）不改写；已了结记录不重复处置
            }
            BloodBag bag = lockedBags.get(isolation.getBag().getId());
            if (bag == null) {
                continue;
            }
            if (excluded) {
                releaseOne(event, isolation, bag, reviewer, now, steps, affectedCells);
            } else {
                scrapOne(event, isolation, bag, reviewer, now, steps, affectedCells);
            }
        }

        event.setStatus(request.conclusion());
        event.setConclusion(request.conclusion());
        event.setReviewer(reviewer);
        event.setReviewedAt(now);
        event.setReviewNote(request.note());
        eventRepository.save(event);
        steps.forEach(stepRepository::save);
        for (String cell : affectedCells) {
            String[] parts = cell.split("\\|");
            inventoryService.syncInventory(parts[0], parts[1]);
        }
        return detail(event.getId());
    }

    /**
     * 排除关联 ⇒ 释放单袋：仍满足原检验放行、未过期且未被其他在办事件隔离才回库；
     * 已过期的隔离血袋不得回库，按过期判废。
     */
    private void releaseOne(ReactionEvent event, ReactionIsolation isolation, BloodBag bag, String reviewer,
                            LocalDateTime now, List<ReactionStep> steps, Set<String> affectedCells) {
        if (!"ISOLATED".equals(bag.getStatus())) {
            steps.add(step(event, "KEEP", bag.getBagNo(),
                    "血袋 " + bag.getBagNo() + " 状态已被其他事件推进为「"
                            + BloodDict.bagStatusName(bag.getStatus()) + "」，本事件不再处置", "系统自动"));
            return;
        }
        long otherOpen = isolationRepository.countOtherOpenIsolations(bag.getId(), event.getId());
        if (otherOpen > 0) {
            steps.add(step(event, "KEEP", bag.getBagNo(),
                    "血袋 " + bag.getBagNo() + " 仍被其他在办事件隔离，任一事件未结束前不得释放", "系统自动"));
            return;
        }
        if (bag.getExpiryDate() != null && !bag.getExpiryDate().isAfter(LocalDate.now())) {
            // 不满足「未过期」⇒ 过期血液不得回库，直接判废
            bag.markScrapped();
            bagRepository.save(bag);
            ScrapRecord scrap = scrapRepository.save(buildScrap(bag, "EXPIRED", reviewer,
                    "不良反应事件 " + event.getEventNo() + " 排除关联，但血袋已过失效期，按过期判废"));
            isolation.setStatus("SCRAPPED");
            isolation.setResolvedAt(now);
            isolationRepository.save(isolation);
            steps.add(step(event, "SCRAP", bag.getBagNo(),
                    "血袋已过失效期，不满足放行条件，按过期判废并生成报废单 " + scrap.getScrapNo(), reviewer));
            sweepOtherEvents(bag, event, "SCRAPPED", "SCRAP",
                    "血袋已在事件 " + event.getEventNo() + " 中按过期判废，本事件隔离记录同步了结");
            affectedCells.add(bag.getBloodType() + "|" + bag.getComponent());
            return;
        }
        String sampleStatus = sampleStatusOf(bag);
        if (!"RELEASED".equals(sampleStatus)) {
            steps.add(step(event, "KEEP", bag.getBagNo(),
                    "检验标本状态为「" + BloodDict.sampleStatusName(sampleStatus)
                            + "」，不满足原检验放行条件，暂不释放", "系统自动"));
            return;
        }
        bag.releaseIsolation();
        bagRepository.save(bag);
        isolation.setStatus("RELEASED");
        isolation.setResolvedAt(now);
        isolationRepository.save(isolation);
        steps.add(step(event, "RELEASE", bag.getBagNo(),
                "解除同源隔离：检验已放行、未过期、无其他在办事件隔离，恢复可用库存", reviewer));
        sweepOtherEvents(bag, event, "RELEASED", "RELEASE",
                "血袋已在事件 " + event.getEventNo() + " 中解除隔离，本事件隔离记录同步了结");
        affectedCells.add(bag.getBloodType() + "|" + bag.getComponent());
    }

    /**
     * 确认风险 ⇒ 隔离血袋转报废（只进不退）；已被其他事件报废的不重复报废、不重复扣减。
     */
    private void scrapOne(ReactionEvent event, ReactionIsolation isolation, BloodBag bag, String reviewer,
                          LocalDateTime now, List<ReactionStep> steps, Set<String> affectedCells) {
        if (!"ISOLATED".equals(bag.getStatus())) {
            isolation.setStatus("SCRAPPED");
            isolation.setResolvedAt(now);
            isolationRepository.save(isolation);
            steps.add(step(event, "KEEP", bag.getBagNo(),
                    "血袋 " + bag.getBagNo() + " 已被其他事件报废，本事件不重复报废、不重复扣减", "系统自动"));
            return;
        }
        bag.markScrapped();
        bagRepository.save(bag);
        ScrapRecord scrap = scrapRepository.save(buildScrap(bag, "REACTION", reviewer,
                "不良反应事件 " + event.getEventNo() + " 确认风险，同源隔离血袋转报废"));
        isolation.setStatus("SCRAPPED");
        isolation.setResolvedAt(now);
        isolationRepository.save(isolation);
        steps.add(step(event, "SCRAP", bag.getBagNo(),
                "确认风险：隔离血袋转报废，生成报废单 " + scrap.getScrapNo(), reviewer));
        sweepOtherEvents(bag, event, "SCRAPPED", "SCRAP",
                "血袋已在事件 " + event.getEventNo() + " 中确认风险报废，本事件隔离记录同步了结");
        affectedCells.add(bag.getBloodType() + "|" + bag.getComponent());
    }

    /**
     * 血袋了结（释放 / 报废）后，同步其他事件仍挂着的同源隔离记录，保证各事件台账与血袋实况一致。
     */
    private void sweepOtherEvents(BloodBag bag, ReactionEvent actor, String newStatus, String action, String detail) {
        for (ReactionIsolation other : isolationRepository.findByBagIdAndStatus(bag.getId(), "ISOLATED")) {
            if (other.getEvent().getId().equals(actor.getId())) {
                continue;
            }
            other.setStatus(newStatus);
            other.setResolvedAt(LocalDateTime.now());
            isolationRepository.save(other);
            stepRepository.save(step(other.getEvent(), action, bag.getBagNo(), detail, "系统联动"));
        }
    }

    // ---------------- 查询 ----------------

    @Transactional(readOnly = true)
    public List<ReactionEventVO> listEvents(String bloodType) {
        List<ReactionEvent> events = bloodType == null || bloodType.isBlank()
                ? eventRepository.findAllByOrderByIdDesc()
                : eventRepository.findByBloodTypeOrderByIdDesc(bloodType);
        List<ReactionEventVO> vos = new ArrayList<>();
        for (ReactionEvent event : events) {
            vos.add(toVO(event, isolationRepository.findByEventIdOrderByIdAsc(event.getId())));
        }
        return vos;
    }

    @Transactional(readOnly = true)
    public ReactionDetailVO detail(Long id) {
        ReactionEvent event = eventRepository.findById(id)
                .orElseThrow(() -> new BizException("不良反应事件不存在：id=" + id));
        List<ReactionIsolation> isolations = isolationRepository.findByEventIdOrderByIdAsc(event.getId());
        List<ReactionStep> steps = stepRepository.findByEventIdOrderByIdAsc(event.getId());
        List<ReactionIsolationVO> isolationVOs = new ArrayList<>();
        for (ReactionIsolation isolation : isolations) {
            isolationVOs.add(toVO(isolation));
        }
        List<ReactionStepVO> stepVOs = new ArrayList<>();
        for (ReactionStep step : steps) {
            stepVOs.add(toVO(step));
        }
        return new ReactionDetailVO(toVO(event, isolations), chainOf(event.getMatch()), isolationVOs, stepVOs);
    }

    /**
     * 来源链反查：发血记录 → 供血血袋 → 制备批次 → 采血记录（献血者）→ 检验标本。
     */
    @Transactional(readOnly = true)
    public SourceChainVO trace(Long matchId) {
        CrossMatch match = matchRepository.findById(matchId)
                .orElseThrow(() -> new BizException("发血记录不存在：id=" + matchId));
        if (!Boolean.TRUE.equals(match.getIssued())) {
            throw new BizException("配血单 " + match.getMatchNo() + " 尚未发血出库，不能追溯");
        }
        return chainOf(match);
    }

    // ---------------- 内部方法 ----------------

    private SourceChainVO chainOf(CrossMatch match) {
        SourceChainVO.MatchNode matchNode = new SourceChainVO.MatchNode(match.getId(), match.getMatchNo(),
                match.getHospital(), match.getRecipient(), match.getBloodType(), match.getComponent(),
                BloodDict.componentName(match.getComponent()), match.getUnitsU(),
                match.getIssueTime(), match.getIssueOperator());

        BloodBag bag = match.getBag();
        SourceChainVO.BagNode bagNode = bag == null ? null : new SourceChainVO.BagNode(bag.getId(),
                bag.getBagNo(), bag.getBloodType(), bag.getComponent(), BloodDict.componentName(bag.getComponent()),
                bag.getVolumeMl(), bag.getCollectDate(), bag.getExpiryDate(), bag.getStatus(),
                BloodDict.bagStatusName(bag.getStatus()), bag.getDonorName());

        PreparationBatch batch = bag == null ? null : bag.getBatch();
        SourceChainVO.BatchNode batchNode = batch == null ? null : new SourceChainVO.BatchNode(batch.getId(),
                batch.getBatchNo(), batch.getBloodType(), batch.getRbcBags(), batch.getRbcVolumeMl(),
                batch.getPlasmaBags(), batch.getPlasmaVolumeMl(), batch.getPlateletBags(),
                batch.getPlateletVolumeMl(), batch.getCryoBags(), batch.getCryoVolumeMl(),
                batch.getOperator(), batch.getPreparedTime(), batch.getStatus());

        CollectionRecord collection = bag == null ? null : bag.getCollection();
        Donor donor = collection == null ? null : collection.getDonor();
        SourceChainVO.CollectionNode collectionNode = collection == null ? null : new SourceChainVO.CollectionNode(
                collection.getId(), collection.getCollectNo(), collection.getStationCode(), collection.getVolumeMl(),
                collection.getCollectDate(), collection.getNurse(), collection.getStatus(),
                BloodDict.collectionStatusName(collection.getStatus()),
                donor == null ? null : donor.getDonorNo(),
                donor == null ? null : donor.getName(),
                donor == null ? null : donor.getBloodType(),
                donor == null ? null : donor.getGender(),
                donor == null ? null : donor.getAge());

        TestSample sample = collection == null
                ? null
                : sampleRepository.findByCollectionId(collection.getId()).orElse(null);
        SourceChainVO.SampleNode sampleNode = sample == null ? null : new SourceChainVO.SampleNode(sample.getId(),
                sample.getBarcode(), sample.getBloodType(), sample.getAltValue(), sample.getHbValue(),
                sample.getHbvResult(), sample.getHcvResult(), sample.getHivResult(), sample.getSyphilisResult(),
                sample.getReviewer(), sample.getStatus(), BloodDict.sampleStatusName(sample.getStatus()),
                sample.getTestedAt(), sample.getReleasedAt());

        return new SourceChainVO(matchNode, bagNode, batchNode, collectionNode, sampleNode);
    }

    private String sampleStatusOf(BloodBag bag) {
        if (bag.getCollection() == null) {
            return "PENDING";
        }
        TestSample sample = sampleRepository.findByCollectionId(bag.getCollection().getId()).orElse(null);
        return sample == null ? "PENDING" : sample.getStatus();
    }

    /**
     * 报废单号取「SC + 日期 + 袋号」：一袋在反应追溯中至多报废一次（状态守卫），
     * 天然幂等，并发复核也不会撞号或重复报废。
     */
    private ScrapRecord buildScrap(BloodBag bag, String reason, String operator, String remark) {
        ScrapRecord record = new ScrapRecord();
        record.setScrapNo("SC" + LocalDate.now().format(SEQ_DAY) + "-" + bag.getBagNo());
        record.setBagNo(bag.getBagNo());
        record.setBloodType(bag.getBloodType());
        record.setComponent(bag.getComponent());
        record.setBags(1);
        record.setVolumeMl(bag.getVolumeMl() == null ? 0 : bag.getVolumeMl());
        record.setReason(reason);
        record.setOperator(operator);
        record.setScrapTime(LocalDateTime.now());
        record.setRemark(remark);
        record.setCreatedAt(LocalDateTime.now());
        return record;
    }

    private ReactionStep step(ReactionEvent event, String action, String bagNo, String detail, String operator) {
        ReactionStep step = new ReactionStep();
        step.setEvent(event);
        step.setAction(action);
        step.setBagNo(bagNo);
        step.setDetail(detail.length() > 255 ? detail.substring(0, 255) : detail);
        step.setOperator(operator);
        step.setCreatedAt(LocalDateTime.now());
        return step;
    }

    /** 症状规范化：去首尾空白、压缩连续空白，保证「症状相同」判定稳定 */
    private String normalizeSymptoms(String symptoms) {
        if (symptoms == null) {
            return "";
        }
        String normalized = symptoms.trim().replaceAll("\\s+", " ");
        if (normalized.length() > 255) {
            throw new BizException("反应症状过长（最多 255 字）");
        }
        return normalized;
    }

    /** 幂等指纹：发血记录 + 发生时间 + 规范化症状 的 SHA-256 */
    private String dedupHash(Long matchId, LocalDateTime occurredAt, String symptoms) {
        String raw = matchId + "|" + occurredAt + "|" + symptoms;
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(64);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    private ReactionEventVO toVO(ReactionEvent event, List<ReactionIsolation> isolations) {
        int isolated = 0;
        int released = 0;
        int scrapped = 0;
        int trace = 0;
        for (ReactionIsolation isolation : isolations) {
            switch (isolation.getStatus()) {
                case "ISOLATED" -> isolated++;
                case "RELEASED" -> released++;
                case "SCRAPPED" -> scrapped++;
                default -> trace++;
            }
        }
        return new ReactionEventVO(event.getId(), event.getEventNo(),
                event.getMatch() == null ? null : event.getMatch().getId(), event.getMatchNo(),
                event.getBag() == null ? null : event.getBag().getId(), event.getBagNo(),
                event.getBloodType(), event.getComponent(), BloodDict.componentName(event.getComponent()),
                event.getCollectionId(), event.getHospital(), event.getReporter(), event.getSymptoms(),
                event.getOccurredAt(), event.getReportedAt(), event.getStatus(),
                BloodDict.reactionStatusName(event.getStatus()), event.getConclusion(),
                event.getConclusion() == null ? null : BloodDict.reactionStatusName(event.getConclusion()),
                event.getReviewer(), event.getReviewedAt(), event.getReviewNote(),
                isolated, released, scrapped, trace);
    }

    private ReactionIsolationVO toVO(ReactionIsolation isolation) {
        return new ReactionIsolationVO(isolation.getId(),
                isolation.getBag() == null ? null : isolation.getBag().getId(), isolation.getBagNo(),
                isolation.getBloodType(), isolation.getComponent(),
                BloodDict.componentName(isolation.getComponent()), isolation.getVolumeMl(),
                isolation.getBagStatusAtRegister(), BloodDict.bagStatusName(isolation.getBagStatusAtRegister()),
                isolation.getStatus(), BloodDict.isolationStatusName(isolation.getStatus()),
                isolation.getCreatedAt(), isolation.getResolvedAt());
    }

    private ReactionStepVO toVO(ReactionStep step) {
        return new ReactionStepVO(step.getId(), step.getAction(), BloodDict.reactionStepName(step.getAction()),
                step.getBagNo(), step.getDetail(), step.getOperator(), step.getCreatedAt());
    }
}

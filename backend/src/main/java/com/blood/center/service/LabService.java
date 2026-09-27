package com.blood.center.service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.blood.center.common.BizException;
import com.blood.center.common.BloodDict;
import com.blood.center.domain.CollectionRecord;
import com.blood.center.domain.PreparationBatch;
import com.blood.center.domain.TestSample;
import com.blood.center.dto.PreparationSummaryVO;
import com.blood.center.dto.PreparationVO;
import com.blood.center.dto.ReleaseRequest;
import com.blood.center.dto.SampleVO;
import com.blood.center.dto.TestResultRequest;
import com.blood.center.repository.PreparationBatchRepository;
import com.blood.center.repository.TestSampleRepository;

/**
 * 标本检验与成分制备：待检 → 检测中 → 已放行（→ 领域事件自动生成制备单）/ 报废。
 */
@Service
public class LabService {

    private static final DateTimeFormatter SEQ_DAY = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final TestSampleRepository sampleRepository;
    private final PreparationBatchRepository preparationRepository;

    public LabService(TestSampleRepository sampleRepository, PreparationBatchRepository preparationRepository) {
        this.sampleRepository = sampleRepository;
        this.preparationRepository = preparationRepository;
    }

    // ---------------- 标本检验 ----------------

    @Transactional(readOnly = true)
    public List<SampleVO> listSamples(String bloodType) {
        List<TestSample> samples = bloodType == null || bloodType.isBlank()
                ? sampleRepository.findAll()
                : sampleRepository.findByBloodTypeOrderByIdAsc(bloodType);
        List<SampleVO> vos = new ArrayList<>();
        for (TestSample sample : samples) {
            vos.add(toVO(sample));
        }
        return vos;
    }

    /**
     * 录入检验结果：ALT / 血红蛋白 / HBV / HCV / HIV / 梅毒；任一不合格直接判为报废。
     */
    @Transactional
    public SampleVO inputResult(Long id, TestResultRequest request) {
        TestSample sample = getSample(id);
        if ("RELEASED".equals(sample.getStatus())) {
            throw new BizException("标本 " + sample.getBarcode() + " 已放行，不能再修改检验结果");
        }
        if (request.altValue() == null || request.hbValue() == null
                || request.hbvResult() == null || request.hcvResult() == null
                || request.hivResult() == null || request.syphilisResult() == null) {
            throw new BizException("ALT、血红蛋白、HBV、HCV、HIV、梅毒 六项结果必须全部录入");
        }
        sample.setAltValue(request.altValue());
        sample.setHbValue(request.hbValue());
        sample.setHbvResult(request.hbvResult());
        sample.setHcvResult(request.hcvResult());
        sample.setHivResult(request.hivResult());
        sample.setSyphilisResult(request.syphilisResult());
        sample.setTestedAt(LocalDateTime.now());
        if (request.operator() != null && !request.operator().isBlank()) {
            sample.setReviewer(request.operator());
        }
        String unqualified = unqualifiedReason(sample);
        if (unqualified != null) {
            sample.setStatus("SCRAPPED");
        } else {
            sample.setStatus("TESTING");
        }
        return toVO(sampleRepository.save(sample));
    }

    /**
     * 标本放行：必须有复核人、六项结果齐全且合格；放行后触发成分制备（领域事件）。
     */
    @Transactional
    public SampleVO release(Long id, ReleaseRequest request) {
        TestSample sample = getSample(id);
        if ("RELEASED".equals(sample.getStatus())) {
            throw new BizException("标本 " + sample.getBarcode() + " 已放行，请勿重复操作");
        }
        if (!"TESTING".equals(sample.getStatus())) {
            throw new BizException("标本 " + sample.getBarcode() + " 当前状态为「"
                    + BloodDict.sampleStatusName(sample.getStatus()) + "」，只有检测中的标本可以放行");
        }
        if (request.reviewer() == null || request.reviewer().isBlank()) {
            throw new BizException("放行前必须选择复核人");
        }
        if (sample.getAltValue() == null || sample.getHbValue() == null || sample.getHbvResult() == null
                || sample.getHcvResult() == null || sample.getHivResult() == null
                || sample.getSyphilisResult() == null) {
            throw new BizException("标本 " + sample.getBarcode() + " 检验项目结果不全，不能放行");
        }
        String unqualified = unqualifiedReason(sample);
        if (unqualified != null) {
            throw new BizException("标本 " + sample.getBarcode() + " 检验不合格（" + unqualified + "），不能放行");
        }
        sample.release(request.reviewer().trim());
        return toVO(sampleRepository.save(sample));
    }

    /**
     * 轨道拖拽：待检 → 检测中 → 已放行 / 报废。
     */
    @Transactional
    public SampleVO move(Long id, String status, String reviewer) {
        TestSample sample = getSample(id);
        if (!BloodDict.isValidSampleStatus(status)) {
            throw new BizException("未知的标本状态：" + status);
        }
        switch (status) {
            case "PENDING" -> {
                if ("RELEASED".equals(sample.getStatus())) {
                    throw new BizException("已放行的标本不能退回待检");
                }
                sample.setStatus("PENDING");
            }
            case "TESTING" -> {
                if ("RELEASED".equals(sample.getStatus())) {
                    throw new BizException("已放行的标本不能退回检测中");
                }
                sample.setStatus("TESTING");
                if (sample.getTestedAt() == null) {
                    sample.setTestedAt(LocalDateTime.now());
                }
            }
            case "RELEASED" -> {
                return release(id, new ReleaseRequest(reviewer));
            }
            case "SCRAPPED" -> {
                if ("RELEASED".equals(sample.getStatus())) {
                    throw new BizException("已放行的标本不能作废，请走血液报废流程");
                }
                sample.scrap();
            }
            default -> throw new BizException("未知的标本状态：" + status);
        }
        return toVO(sampleRepository.save(sample));
    }

    // ---------------- 成分制备 ----------------

    @Transactional(readOnly = true)
    public List<PreparationVO> listPreparations(String bloodType) {
        List<PreparationBatch> batches = bloodType == null || bloodType.isBlank()
                ? preparationRepository.findAll()
                : preparationRepository.findByBloodTypeOrderByIdDesc(bloodType);
        List<PreparationVO> vos = new ArrayList<>();
        for (PreparationBatch batch : batches) {
            vos.add(toVO(batch));
        }
        return vos;
    }

    @Transactional(readOnly = true)
    public List<PreparationSummaryVO> preparationSummary() {
        return preparationRepository.summaryByBloodType();
    }

    // ---------------- 内部方法 ----------------

    /**
     * 按全血量计算可分离成分：红细胞悬液 / 血浆 / 血小板 / 冷沉淀。
     */
    public static int[] splitComponents(Integer wholeVolumeMl) {
        int volume = wholeVolumeMl == null ? 0 : wholeVolumeMl;
        int rbc = (int) Math.round(volume * 0.45);
        int plasma = (int) Math.round(volume * 0.40);
        int platelet = volume >= 350 ? 250 : 0;
        int cryo = volume >= 350 ? 30 : 0;
        return new int[]{rbc, plasma, platelet, cryo};
    }

    /**
     * 检验不合格原因；合格返回 null。
     */
    public String unqualifiedReason(TestSample sample) {
        if (sample.getAltValue() != null && sample.getAltValue().compareTo(BloodDict.ALT_LIMIT) > 0) {
            return "ALT " + sample.getAltValue() + " U/L 超过 " + BloodDict.ALT_LIMIT + " U/L";
        }
        if (sample.getHbValue() != null) {
            BigDecimal limit = "女".equals(sample.donorGender()) ? BloodDict.HB_LIMIT_FEMALE : BloodDict.HB_LIMIT_MALE;
            if (sample.getHbValue().compareTo(limit) < 0) {
                return "血红蛋白 " + sample.getHbValue() + " g/L 低于 " + limit + " g/L";
            }
        }
        if ("POSITIVE".equals(sample.getHbvResult())) {
            return "HBV 阳性";
        }
        if ("POSITIVE".equals(sample.getHcvResult())) {
            return "HCV 阳性";
        }
        if ("POSITIVE".equals(sample.getHivResult())) {
            return "HIV 阳性";
        }
        if ("POSITIVE".equals(sample.getSyphilisResult())) {
            return "梅毒阳性";
        }
        return null;
    }

    private TestSample getSample(Long id) {
        return sampleRepository.findById(id).orElseThrow(() -> new BizException("标本不存在：id=" + id));
    }

    private SampleVO toVO(TestSample sample) {
        CollectionRecord collection = sample.getCollection();
        List<SampleVO.ItemVO> items = new ArrayList<>();
        items.add(new SampleVO.ItemVO("ALT", "ALT", str(sample.getAltValue()), "U/L",
                sample.getAltValue() == null ? "未检"
                        : (sample.getAltValue().compareTo(BloodDict.ALT_LIMIT) > 0 ? "偏高" : "合格")));
        BigDecimal hbLimit = "女".equals(sample.donorGender()) ? BloodDict.HB_LIMIT_FEMALE : BloodDict.HB_LIMIT_MALE;
        items.add(new SampleVO.ItemVO("HB", "血红蛋白", str(sample.getHbValue()), "g/L",
                sample.getHbValue() == null ? "未检"
                        : (sample.getHbValue().compareTo(hbLimit) < 0 ? "偏低" : "合格")));
        items.add(new SampleVO.ItemVO("HBV", "HBV", BloodDict.testResultName(sample.getHbvResult()), "",
                sample.getHbvResult() == null ? "未检" : BloodDict.testResultName(sample.getHbvResult())));
        items.add(new SampleVO.ItemVO("HCV", "HCV", BloodDict.testResultName(sample.getHcvResult()), "",
                sample.getHcvResult() == null ? "未检" : BloodDict.testResultName(sample.getHcvResult())));
        items.add(new SampleVO.ItemVO("HIV", "HIV", BloodDict.testResultName(sample.getHivResult()), "",
                sample.getHivResult() == null ? "未检" : BloodDict.testResultName(sample.getHivResult())));
        items.add(new SampleVO.ItemVO("SYPHILIS", "梅毒", BloodDict.testResultName(sample.getSyphilisResult()), "",
                sample.getSyphilisResult() == null ? "未检" : BloodDict.testResultName(sample.getSyphilisResult())));

        return new SampleVO(sample.getId(), sample.getBarcode(),
                collection == null ? null : collection.getId(),
                collection == null ? null : collection.getCollectNo(),
                sample.getDonorName(), sample.getBloodType(), sample.getAltValue(), sample.getHbValue(),
                sample.getHbvResult(), sample.getHcvResult(), sample.getHivResult(), sample.getSyphilisResult(),
                sample.getReviewer(), sample.getStatus(), BloodDict.sampleStatusName(sample.getStatus()),
                sample.getTestedAt(), sample.getReleasedAt(), items);
    }

    private String str(BigDecimal value) {
        return value == null ? "--" : value.toPlainString();
    }

    private PreparationVO toVO(PreparationBatch batch) {
        int rbcBags = nz(batch.getRbcBags());
        int plasmaBags = nz(batch.getPlasmaBags());
        int plateletBags = nz(batch.getPlateletBags());
        int cryoBags = nz(batch.getCryoBags());
        int rbcVol = nz(batch.getRbcVolumeMl());
        int plasmaVol = nz(batch.getPlasmaVolumeMl());
        int plateletVol = nz(batch.getPlateletVolumeMl());
        int cryoVol = nz(batch.getCryoVolumeMl());
        return new PreparationVO(batch.getId(), batch.getBatchNo(),
                batch.getCollection() == null ? null : batch.getCollection().getId(),
                batch.getCollectNo(), batch.getBloodType(),
                rbcBags, rbcVol, plasmaBags, plasmaVol, plateletBags, plateletVol, cryoBags, cryoVol,
                batch.getOperator(), batch.getPreparedTime(), batch.getStatus(),
                rbcBags + plasmaBags + plateletBags + cryoBags,
                rbcVol + plasmaVol + plateletVol + cryoVol);
    }

    private int nz(Integer value) {
        return value == null ? 0 : value;
    }
}

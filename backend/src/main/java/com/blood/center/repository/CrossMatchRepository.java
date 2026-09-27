package com.blood.center.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.blood.center.domain.CrossMatch;
import com.blood.center.dto.HospitalIssueVO;

public interface CrossMatchRepository extends JpaRepository<CrossMatch, Long> {

    List<CrossMatch> findByRequestIdOrderByIdDesc(Long requestId);

    List<CrossMatch> findByBloodTypeOrderByIdDesc(String bloodType);

    List<CrossMatch> findByIssuedTrueOrderByIdDesc();

    long countByIssuedTrue();

    /**
     * 手写 JPQL：按医院汇总发血量（发血单数与已发 U 数）
     */
    @Query("select new com.blood.center.dto.HospitalIssueVO(c.hospital, count(c), sum(c.unitsU)) "
            + "from CrossMatch c where c.issued = true "
            + "group by c.hospital order by sum(c.unitsU) desc")
    List<HospitalIssueVO> summaryByHospital();

    @Query("select coalesce(sum(c.unitsU), 0) from CrossMatch c where c.issued = true")
    long sumIssuedUnits();
}

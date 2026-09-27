package com.blood.center.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.blood.center.domain.TestSample;

public interface TestSampleRepository extends JpaRepository<TestSample, Long> {

    List<TestSample> findByStatusOrderByIdAsc(String status);

    List<TestSample> findByBloodTypeOrderByIdAsc(String bloodType);

    Optional<TestSample> findByBarcode(String barcode);

    Optional<TestSample> findByCollectionId(Long collectionId);

    long countByStatus(String status);

    @Query("select count(s) from TestSample s where s.bloodType = :bloodType and s.status = :status")
    long countByBloodTypeAndStatus(@Param("bloodType") String bloodType, @Param("status") String status);
}

package com.blood.center.repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.blood.center.domain.BloodBag;

public interface BloodBagRepository extends JpaRepository<BloodBag, Long> {

    Optional<BloodBag> findByBagNo(String bagNo);

    List<BloodBag> findByBloodTypeAndComponentOrderByExpiryDateAsc(String bloodType, String component);

    List<BloodBag> findByBloodTypeOrderByExpiryDateAsc(String bloodType);

    long countByStatus(String status);

    /** 手写 JPQL：待报废的过期可用血袋 */
    @Query("select b from BloodBag b where b.status = 'AVAILABLE' and b.expiryDate <= :today order by b.expiryDate asc")
    List<BloodBag> findExpiredAvailable(@Param("today") LocalDate today);

    @Query("select count(b) from BloodBag b where b.status = 'AVAILABLE' and b.expiryDate <= :today")
    long countExpiredAvailable(@Param("today") LocalDate today);

    @Query("select coalesce(sum(b.volumeMl), 0) from BloodBag b where b.status = :status")
    long sumVolumeByStatus(@Param("status") String status);
}

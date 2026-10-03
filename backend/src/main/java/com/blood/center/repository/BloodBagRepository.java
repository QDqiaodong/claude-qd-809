package com.blood.center.repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.blood.center.domain.BloodBag;

import jakarta.persistence.LockModeType;

public interface BloodBagRepository extends JpaRepository<BloodBag, Long> {

    Optional<BloodBag> findByBagNo(String bagNo);

    List<BloodBag> findByBloodTypeAndComponentOrderByExpiryDateAsc(String bloodType, String component);

    List<BloodBag> findByBloodTypeOrderByExpiryDateAsc(String bloodType);

    /** 同一采血来源分离出的全部成分血袋（同源隔离的对象） */
    List<BloodBag> findByCollectionIdOrderByIdAsc(Long collectionId);

    long countByStatus(String status);

    /**
     * 悲观写锁：上报建隔离与复核释放 / 报废并发时，按 id 升序逐袋加锁，
     * 保证「释放前确认无其他在办事件隔离」与「新事件叠加隔离」不会交错。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from BloodBag b where b.id in :ids order by b.id asc")
    List<BloodBag> findAllByIdForUpdate(@Param("ids") List<Long> ids);

    /** 手写 JPQL：待报废的过期可用血袋 */
    @Query("select b from BloodBag b where b.status = 'AVAILABLE' and b.expiryDate <= :today order by b.expiryDate asc")
    List<BloodBag> findExpiredAvailable(@Param("today") LocalDate today);

    @Query("select count(b) from BloodBag b where b.status = 'AVAILABLE' and b.expiryDate <= :today")
    long countExpiredAvailable(@Param("today") LocalDate today);

    @Query("select coalesce(sum(b.volumeMl), 0) from BloodBag b where b.status = :status")
    long sumVolumeByStatus(@Param("status") String status);
}

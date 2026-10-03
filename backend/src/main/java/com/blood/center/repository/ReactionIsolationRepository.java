package com.blood.center.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.blood.center.domain.ReactionIsolation;

public interface ReactionIsolationRepository extends JpaRepository<ReactionIsolation, Long> {

    List<ReactionIsolation> findByEventIdOrderByIdAsc(Long eventId);

    List<ReactionIsolation> findByBagIdAndStatus(Long bagId, String status);

    /**
     * 同一血袋仍被「其他在办事件」隔离的数量：任一事件未结束前都不得释放。
     */
    @Query("select count(i) from ReactionIsolation i where i.bag.id = :bagId and i.status = 'ISOLATED' "
            + "and i.event.id <> :eventId and i.event.status = 'OPEN'")
    long countOtherOpenIsolations(@Param("bagId") Long bagId, @Param("eventId") Long eventId);
}

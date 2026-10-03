package com.blood.center.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.blood.center.domain.ReactionEvent;

import jakarta.persistence.LockModeType;

public interface ReactionEventRepository extends JpaRepository<ReactionEvent, Long> {

    /** 幂等指纹命中 ⇒ 同一宗处置事件（重复点击 / 两人同时上报都合并） */
    Optional<ReactionEvent> findByDedupHash(String dedupHash);

    List<ReactionEvent> findAllByOrderByIdDesc();

    List<ReactionEvent> findByBloodTypeOrderByIdDesc(String bloodType);

    /** 同一发血记录的在办事件（用于「内容有差异 ⇒ 独立事件并提示已有在办事件」） */
    @Query("select e from ReactionEvent e where e.match.id = :matchId and e.status = 'OPEN' order by e.id asc")
    List<ReactionEvent> findOpenByMatchId(@Param("matchId") Long matchId);

    /** 悲观写锁：复核结论串行化，并发复核 / 重复提交不会双双通过 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select e from ReactionEvent e where e.id = :id")
    Optional<ReactionEvent> findByIdForUpdate(@Param("id") Long id);
}

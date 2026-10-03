package com.blood.center.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.blood.center.domain.ReactionStep;

public interface ReactionStepRepository extends JpaRepository<ReactionStep, Long> {

    List<ReactionStep> findByEventIdOrderByIdAsc(Long eventId);
}

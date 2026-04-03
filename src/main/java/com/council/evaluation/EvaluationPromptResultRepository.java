package com.council.evaluation;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface EvaluationPromptResultRepository extends JpaRepository<EvaluationPromptResultEntity, UUID> {

    List<EvaluationPromptResultEntity> findByEvaluationRunIdOrderByPromptIndex(UUID runId);
}


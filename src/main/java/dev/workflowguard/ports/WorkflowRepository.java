package dev.workflowguard.ports;

import dev.workflowguard.domain.Workflow;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WorkflowRepository {
    void save(Workflow workflow);

    Optional<Workflow> findById(UUID id);

    List<Workflow> findAll();

    void deleteById(UUID id);
}

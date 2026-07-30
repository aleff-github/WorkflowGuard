package dev.workflowguard.adapters.persistence;

import dev.workflowguard.domain.Workflow;
import dev.workflowguard.ports.WorkflowRepository;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public final class InMemoryWorkflowRepository implements WorkflowRepository {
    private final ConcurrentMap<UUID, Workflow> workflows = new ConcurrentHashMap<>();

    @Override
    public void save(Workflow workflow) {
        workflows.put(workflow.id(), workflow);
    }

    @Override
    public Optional<Workflow> findById(UUID id) {
        return Optional.ofNullable(workflows.get(id));
    }

    @Override
    public List<Workflow> findAll() {
        return workflows.values().stream()
                .sorted(Comparator.comparing(Workflow::createdAt).thenComparing(Workflow::id))
                .toList();
    }

    @Override
    public void deleteById(UUID id) {
        workflows.remove(id);
    }
}

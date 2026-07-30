package dev.workflowguard.application;

import dev.workflowguard.core.ExecutionPlanner;
import dev.workflowguard.core.HttpRequestText;
import dev.workflowguard.core.MutationEngine;
import dev.workflowguard.core.StepClassifier;
import dev.workflowguard.domain.ActorDefinition;
import dev.workflowguard.domain.ExecutionPlan;
import dev.workflowguard.domain.JsonInvariant;
import dev.workflowguard.domain.MutationCase;
import dev.workflowguard.domain.MutationType;
import dev.workflowguard.domain.ProbeInvariant;
import dev.workflowguard.domain.StepCategory;
import dev.workflowguard.domain.StepRole;
import dev.workflowguard.domain.VariableDefinition;
import dev.workflowguard.domain.Workflow;
import dev.workflowguard.domain.WorkflowStep;
import dev.workflowguard.ports.WorkflowRepository;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

public final class WorkflowService {
    private final WorkflowRepository repository;
    private final StepClassifier stepClassifier;
    private final MutationEngine mutationEngine;
    private final ExecutionPlanner executionPlanner = new ExecutionPlanner();
    private final CopyOnWriteArrayList<Runnable> listeners = new CopyOnWriteArrayList<>();

    private UUID activeWorkflowId;

    public WorkflowService(
            WorkflowRepository repository,
            StepClassifier stepClassifier,
            MutationEngine mutationEngine
    ) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.stepClassifier = Objects.requireNonNull(stepClassifier, "stepClassifier");
        this.mutationEngine = Objects.requireNonNull(mutationEngine, "mutationEngine");
        repository.findAll().stream().findFirst().ifPresent(
                workflow -> activeWorkflowId = workflow.id()
        );
    }

    public synchronized Workflow createWorkflow(String name) {
        Workflow workflow = Workflow.create(name);
        repository.save(workflow);
        activeWorkflowId = workflow.id();
        publishChange();
        return workflow;
    }

    public synchronized void selectWorkflow(UUID workflowId) {
        Objects.requireNonNull(workflowId, "workflowId");
        if (workflowId.equals(activeWorkflowId)) {
            return;
        }
        repository.findById(workflowId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown workflow: " + workflowId));
        activeWorkflowId = workflowId;
        publishChange();
    }

    public synchronized Workflow capture(List<CapturedRequest> requests) {
        Objects.requireNonNull(requests, "requests");
        if (requests.isEmpty()) {
            throw new IllegalArgumentException("At least one request is required");
        }

        Workflow workflow = activeWorkflow().orElseGet(() -> createWorkflow("Captured workflow"));
        int startingIndex = workflow.steps().size();
        List<WorkflowStep> newSteps = new ArrayList<>();

        for (int index = 0; index < requests.size(); index++) {
            CapturedRequest request = requests.get(index);
            String name = "S" + (startingIndex + index + 1)
                    + " — " + request.method()
                    + " " + displayPath(request.url());
            newSteps.add(new WorkflowStep(
                    UUID.randomUUID(),
                    name,
                    request.method(),
                    request.url(),
                    request.rawRequest(),
                    stepClassifier.classify(request.method(), request.url()),
                    StepRole.ACTION,
                    true,
                    request.inScope()
            ));
        }

        Workflow updated = workflow.append(newSteps);
        repository.save(updated);
        publishChange();
        return updated;
    }

    public synchronized List<MutationCase> generateMutations(
            Set<MutationType> enabledTypes,
            int maximumCases
    ) {
        Workflow workflow = activeWorkflow()
                .orElseThrow(() -> new IllegalStateException("No active workflow"));
        return mutationEngine.generate(workflow, enabledTypes, maximumCases);
    }

    public synchronized Workflow renameActiveWorkflow(String name) {
        Workflow workflow = requireActiveWorkflow();
        Workflow renamed = workflow.rename(name);
        repository.save(renamed);
        publishChange();
        return renamed;
    }

    public synchronized Workflow deleteActiveWorkflow() {
        Workflow workflow = requireActiveWorkflow();
        repository.deleteById(workflow.id());

        List<Workflow> remaining = repository.findAll();
        Workflow next;
        if (remaining.isEmpty()) {
            next = Workflow.create("Captured workflow");
            repository.save(next);
        } else {
            next = remaining.getFirst();
        }
        activeWorkflowId = next.id();
        publishChange();
        return next;
    }

    public synchronized Workflow removeStep(UUID stepId) {
        Objects.requireNonNull(stepId, "stepId");
        Workflow workflow = requireActiveWorkflow();
        List<WorkflowStep> steps = workflow.steps().stream()
                .filter(step -> !step.id().equals(stepId))
                .toList();
        if (steps.size() == workflow.steps().size()) {
            throw new IllegalArgumentException("Unknown step: " + stepId);
        }
        Workflow updated = new Workflow(
                workflow.id(),
                workflow.name(),
                steps,
                workflow.actors(),
                workflow.variables().stream()
                        .filter(variable -> !variable.sourceStepId().equals(stepId))
                        .toList(),
                workflow.invariants().stream()
                        .filter(invariant -> !invariant.probeStepId().equals(stepId))
                        .toList(),
                workflow.volatileJsonPointers(),
                workflow.createdAt()
        );
        return saveWorkflow(updated);
    }

    public synchronized Workflow moveStep(UUID stepId, int offset) {
        Objects.requireNonNull(stepId, "stepId");
        if (offset != -1 && offset != 1) {
            throw new IllegalArgumentException("offset must be -1 or 1");
        }
        Workflow workflow = requireActiveWorkflow();
        List<WorkflowStep> steps = new ArrayList<>(workflow.steps());
        int currentIndex = indexOf(steps, stepId);
        int targetIndex = currentIndex + offset;
        if (targetIndex < 0 || targetIndex >= steps.size()) {
            return workflow;
        }
        java.util.Collections.swap(steps, currentIndex, targetIndex);
        return saveSteps(workflow, steps);
    }

    public synchronized Workflow updateStep(
            UUID stepId,
            StepCategory category,
            StepRole role,
            boolean enabled
    ) {
        Workflow workflow = requireActiveWorkflow();
        WorkflowStep current = requireStep(workflow, stepId);
        return updateStep(stepId, category, role, current.actorId(), enabled);
    }

    public synchronized Workflow updateStep(
            UUID stepId,
            StepCategory category,
            StepRole role,
            UUID actorId,
            boolean enabled
    ) {
        Objects.requireNonNull(stepId, "stepId");
        Objects.requireNonNull(category, "category");
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(actorId, "actorId");
        Workflow workflow = requireActiveWorkflow();
        requireActor(workflow, actorId);
        List<WorkflowStep> steps = new ArrayList<>(workflow.steps());
        int index = indexOf(steps, stepId);
        WorkflowStep current = steps.get(index);
        steps.set(
                index,
                current.withCategory(category)
                        .withRole(role)
                        .withActorId(actorId)
                        .withEnabled(enabled)
        );
        Workflow updated = workflow.withSteps(steps);
        if (role != StepRole.PROBE) {
            updated = updated.withInvariants(
                    updated.invariants().stream()
                            .filter(invariant -> !invariant.probeStepId().equals(stepId))
                            .toList()
            );
        }
        return saveWorkflow(updated);
    }

    public synchronized Workflow addActor(String name, String initialCookieHeader) {
        return addActor(name, initialCookieHeader, "", false);
    }

    public synchronized Workflow addActor(
            String name,
            String initialCookieHeader,
            boolean seedFromCapturedRequest
    ) {
        return addActor(name, initialCookieHeader, "", seedFromCapturedRequest);
    }

    public synchronized Workflow addActor(
            String name,
            String initialCookieHeader,
            String initialAuthorizationHeader,
            boolean seedFromCapturedRequest
    ) {
        Workflow workflow = requireActiveWorkflow();
        String normalizedName = Objects.requireNonNull(name, "name").trim();
        if (workflow.actors().stream().anyMatch(
                actor -> actor.name().equalsIgnoreCase(normalizedName)
        )) {
            throw new IllegalArgumentException(
                    "An actor named '" + normalizedName + "' already exists"
            );
        }
        List<ActorDefinition> actors = new ArrayList<>(workflow.actors());
        actors.add(ActorDefinition.create(
                normalizedName,
                initialCookieHeader,
                initialAuthorizationHeader,
                seedFromCapturedRequest
        ));
        return saveWorkflow(workflow.withActors(actors));
    }

    public synchronized Workflow updateActor(
            UUID actorId,
            String name,
            String initialCookieHeader
    ) {
        Workflow workflow = requireActiveWorkflow();
        ActorDefinition current = requireActor(workflow, actorId);
        return updateActor(
                actorId,
                name,
                initialCookieHeader,
                current.initialAuthorizationHeader(),
                current.seedFromCapturedRequest()
        );
    }

    public synchronized Workflow updateActor(
            UUID actorId,
            String name,
            String initialCookieHeader,
            boolean seedFromCapturedRequest
    ) {
        Workflow workflow = requireActiveWorkflow();
        ActorDefinition current = requireActor(workflow, actorId);
        return updateActor(
                actorId,
                name,
                initialCookieHeader,
                current.initialAuthorizationHeader(),
                seedFromCapturedRequest
        );
    }

    public synchronized Workflow updateActor(
            UUID actorId,
            String name,
            String initialCookieHeader,
            String initialAuthorizationHeader,
            boolean seedFromCapturedRequest
    ) {
        Objects.requireNonNull(actorId, "actorId");
        Workflow workflow = requireActiveWorkflow();
        requireActor(workflow, actorId);
        String normalizedName = Objects.requireNonNull(name, "name").trim();
        if (workflow.actors().stream().anyMatch(actor ->
                !actor.id().equals(actorId)
                        && actor.name().equalsIgnoreCase(normalizedName))) {
            throw new IllegalArgumentException(
                    "An actor named '" + normalizedName + "' already exists"
            );
        }
        List<ActorDefinition> actors = workflow.actors().stream()
                .map(actor -> actor.id().equals(actorId)
                        ? new ActorDefinition(
                                actorId,
                                normalizedName,
                                initialCookieHeader,
                                initialAuthorizationHeader,
                                seedFromCapturedRequest
                        )
                        : actor)
                .toList();
        return saveWorkflow(workflow.withActors(actors));
    }

    public synchronized Workflow removeActor(UUID actorId) {
        Objects.requireNonNull(actorId, "actorId");
        if (actorId.equals(ActorDefinition.DEFAULT_ID)) {
            throw new IllegalArgumentException("The default actor cannot be removed");
        }
        Workflow workflow = requireActiveWorkflow();
        requireActor(workflow, actorId);
        List<ActorDefinition> actors = workflow.actors().stream()
                .filter(actor -> !actor.id().equals(actorId))
                .toList();
        List<WorkflowStep> steps = workflow.steps().stream()
                .map(step -> step.actorId().equals(actorId)
                        ? step.withActorId(ActorDefinition.DEFAULT_ID)
                        : step)
                .toList();
        Workflow updated = new Workflow(
                workflow.id(),
                workflow.name(),
                steps,
                actors,
                workflow.variables(),
                workflow.invariants(),
                workflow.volatileJsonPointers(),
                workflow.createdAt()
        );
        return saveWorkflow(updated);
    }

    public synchronized Workflow updateStepTemplate(UUID stepId, String rawRequest) {
        Objects.requireNonNull(stepId, "stepId");
        Objects.requireNonNull(rawRequest, "rawRequest");
        Workflow workflow = requireActiveWorkflow();
        List<WorkflowStep> steps = new ArrayList<>(workflow.steps());
        int index = indexOf(steps, stepId);
        WorkflowStep current = steps.get(index);
        HttpRequestText.validateTemplate(current.method(), current.url(), rawRequest);
        steps.set(index, current.withRawRequest(rawRequest));
        return saveSteps(workflow, steps);
    }

    public synchronized Workflow addVariable(VariableDefinition definition) {
        Objects.requireNonNull(definition, "definition");
        Workflow workflow = requireActiveWorkflow();
        requireStep(workflow, definition.sourceStepId());
        if (workflow.variables().stream().anyMatch(
                existing -> existing.name().equals(definition.name())
        )) {
            throw new IllegalArgumentException(
                    "A variable named '" + definition.name() + "' already exists"
            );
        }
        List<VariableDefinition> variables = new ArrayList<>(workflow.variables());
        variables.add(definition);
        return saveWorkflow(workflow.withVariables(variables));
    }

    public synchronized Workflow removeVariable(String name) {
        Objects.requireNonNull(name, "name");
        Workflow workflow = requireActiveWorkflow();
        List<VariableDefinition> variables = workflow.variables().stream()
                .filter(variable -> !variable.name().equals(name))
                .toList();
        if (variables.size() == workflow.variables().size()) {
            throw new IllegalArgumentException("Unknown variable: " + name);
        }
        return saveWorkflow(workflow.withVariables(variables));
    }

    public synchronized Workflow updateVariable(
            String originalName,
            VariableDefinition definition
    ) {
        Objects.requireNonNull(originalName, "originalName");
        Objects.requireNonNull(definition, "definition");
        Workflow workflow = requireActiveWorkflow();
        requireStep(workflow, definition.sourceStepId());
        if (!originalName.equals(definition.name())
                && workflow.variables().stream().anyMatch(
                        existing -> existing.name().equals(definition.name())
                )) {
            throw new IllegalArgumentException(
                    "A variable named '" + definition.name() + "' already exists"
            );
        }
        boolean found = workflow.variables().stream()
                .anyMatch(variable -> variable.name().equals(originalName));
        if (!found) {
            throw new IllegalArgumentException("Unknown variable: " + originalName);
        }
        List<VariableDefinition> variables = workflow.variables().stream()
                .map(variable -> variable.name().equals(originalName) ? definition : variable)
                .toList();
        Workflow updated = workflow.withVariables(variables);
        if (!originalName.equals(definition.name())) {
            String oldPlaceholder = "${" + originalName + "}";
            String newPlaceholder = "${" + definition.name() + "}";
            updated = updated.withSteps(workflow.steps().stream()
                    .map(step -> step.rawRequest().contains(oldPlaceholder)
                            ? step.withRawRequest(
                                    step.rawRequest().replace(oldPlaceholder, newPlaceholder)
                            )
                            : step)
                    .toList());
        }
        return saveWorkflow(updated);
    }

    public synchronized Workflow updateVolatileJsonPointers(Set<String> pointers) {
        Objects.requireNonNull(pointers, "pointers");
        return saveWorkflow(requireActiveWorkflow().withVolatileJsonPointers(pointers));
    }

    public synchronized Workflow importWorkflow(Workflow imported) {
        Objects.requireNonNull(imported, "imported");
        Workflow workflow = repository.findById(imported.id()).isPresent()
                ? new Workflow(
                        UUID.randomUUID(),
                        imported.name() + " (imported)",
                        imported.steps(),
                        imported.actors(),
                        imported.variables(),
                        imported.invariants(),
                        imported.volatileJsonPointers(),
                        imported.createdAt()
                )
                : imported;
        repository.save(workflow);
        activeWorkflowId = workflow.id();
        publishChange();
        return workflow;
    }

    public synchronized Workflow addInvariant(UUID probeStepId, JsonInvariant invariant) {
        Objects.requireNonNull(probeStepId, "probeStepId");
        Objects.requireNonNull(invariant, "invariant");
        Workflow workflow = requireActiveWorkflow();
        WorkflowStep probe = requireStep(workflow, probeStepId);
        if (probe.role() != StepRole.PROBE) {
            throw new IllegalArgumentException("Invariant source step must have the PROBE role");
        }
        List<ProbeInvariant> invariants = new ArrayList<>(workflow.invariants());
        invariants.add(ProbeInvariant.create(probeStepId, invariant));
        return saveWorkflow(workflow.withInvariants(invariants));
    }

    public synchronized Workflow removeInvariant(UUID invariantId) {
        Objects.requireNonNull(invariantId, "invariantId");
        Workflow workflow = requireActiveWorkflow();
        List<ProbeInvariant> invariants = workflow.invariants().stream()
                .filter(invariant -> !invariant.id().equals(invariantId))
                .toList();
        if (invariants.size() == workflow.invariants().size()) {
            throw new IllegalArgumentException("Unknown invariant: " + invariantId);
        }
        return saveWorkflow(workflow.withInvariants(invariants));
    }

    public synchronized ExecutionPlan createExecutionPlan(MutationCase mutationCase) {
        return executionPlanner.plan(requireActiveWorkflow(), mutationCase);
    }

    public synchronized Optional<Workflow> activeWorkflow() {
        return activeWorkflowId == null
                ? Optional.empty()
                : repository.findById(activeWorkflowId);
    }

    public synchronized WorkflowSnapshot snapshot() {
        return new WorkflowSnapshot(repository.findAll(), Optional.ofNullable(activeWorkflowId));
    }

    public AutoCloseable onChange(Runnable listener) {
        Objects.requireNonNull(listener, "listener");
        listeners.add(listener);
        return () -> listeners.remove(listener);
    }

    private void publishChange() {
        listeners.forEach(Runnable::run);
    }

    private Workflow requireActiveWorkflow() {
        return activeWorkflow()
                .orElseThrow(() -> new IllegalStateException("No active workflow"));
    }

    private Workflow saveSteps(Workflow workflow, List<WorkflowStep> steps) {
        return saveWorkflow(workflow.withSteps(steps));
    }

    private Workflow saveWorkflow(Workflow updated) {
        repository.save(updated);
        publishChange();
        return updated;
    }

    private WorkflowStep requireStep(Workflow workflow, UUID stepId) {
        return workflow.steps().stream()
                .filter(step -> step.id().equals(stepId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown step: " + stepId));
    }

    private ActorDefinition requireActor(Workflow workflow, UUID actorId) {
        return workflow.actors().stream()
                .filter(actor -> actor.id().equals(actorId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown actor: " + actorId));
    }

    private int indexOf(List<WorkflowStep> steps, UUID stepId) {
        for (int index = 0; index < steps.size(); index++) {
            if (steps.get(index).id().equals(stepId)) {
                return index;
            }
        }
        throw new IllegalArgumentException("Unknown step: " + stepId);
    }

    private String displayPath(String url) {
        try {
            URI uri = URI.create(url);
            String path = uri.getRawPath();
            if (path == null || path.isBlank()) {
                return "/";
            }
            return uri.getRawQuery() == null ? path : path + "?" + uri.getRawQuery();
        } catch (IllegalArgumentException ignored) {
            return url;
        }
    }
}

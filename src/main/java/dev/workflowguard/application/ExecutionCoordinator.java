package dev.workflowguard.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.workflowguard.core.InvariantEngine;
import dev.workflowguard.core.InvariantViolation;
import dev.workflowguard.core.HttpRequestText;
import dev.workflowguard.core.HttpRequestTemplateRenderer;
import dev.workflowguard.core.IsolatedActorCookieJar;
import dev.workflowguard.core.SafetyDecision;
import dev.workflowguard.core.SafetyGate;
import dev.workflowguard.core.VariableResolver;
import dev.workflowguard.domain.ActorDefinition;
import dev.workflowguard.domain.ExecutionPhase;
import dev.workflowguard.domain.ExecutionPlan;
import dev.workflowguard.domain.ExecutionPolicy;
import dev.workflowguard.domain.ExecutionRun;
import dev.workflowguard.domain.InvariantCheckResult;
import dev.workflowguard.domain.MutationCase;
import dev.workflowguard.domain.PlannedStep;
import dev.workflowguard.domain.ProbeInvariant;
import dev.workflowguard.domain.RunStatus;
import dev.workflowguard.domain.StepExecutionResult;
import dev.workflowguard.domain.StepExecutionStatus;
import dev.workflowguard.domain.VariableDefinition;
import dev.workflowguard.domain.WorkflowStep;
import dev.workflowguard.ports.DelayStrategy;
import dev.workflowguard.ports.HttpExchangeEvidence;
import dev.workflowguard.ports.RequestSender;
import dev.workflowguard.ports.ScopeChecker;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;

public final class ExecutionCoordinator implements AutoCloseable {
    private final RequestSender requestSender;
    private final ScopeChecker scopeChecker;
    private final SafetyGate safetyGate;
    private final DelayStrategy delayStrategy;
    private final VariableResolver variableResolver;
    private final HttpRequestTemplateRenderer templateRenderer;
    private final InvariantEngine invariantEngine;
    private final Clock clock;
    private final ExecutorService executor;
    private final AtomicBoolean running = new AtomicBoolean();

    public ExecutionCoordinator(RequestSender requestSender, ScopeChecker scopeChecker) {
        this(
                requestSender,
                scopeChecker,
                new SafetyGate(),
                DelayStrategy.threadSleep(),
                new VariableResolver(new ObjectMapper()),
                new InvariantEngine(new ObjectMapper()),
                Clock.systemUTC(),
                Executors.newSingleThreadExecutor(new ExecutionThreadFactory())
        );
    }

    ExecutionCoordinator(
            RequestSender requestSender,
            ScopeChecker scopeChecker,
            SafetyGate safetyGate,
            DelayStrategy delayStrategy,
            Clock clock,
            ExecutorService executor
    ) {
        this(
                requestSender,
                scopeChecker,
                safetyGate,
                delayStrategy,
                new VariableResolver(new ObjectMapper()),
                new InvariantEngine(new ObjectMapper()),
                clock,
                executor
        );
    }

    ExecutionCoordinator(
            RequestSender requestSender,
            ScopeChecker scopeChecker,
            SafetyGate safetyGate,
            DelayStrategy delayStrategy,
            VariableResolver variableResolver,
            InvariantEngine invariantEngine,
            Clock clock,
            ExecutorService executor
    ) {
        this.requestSender = Objects.requireNonNull(requestSender, "requestSender");
        this.scopeChecker = Objects.requireNonNull(scopeChecker, "scopeChecker");
        this.safetyGate = Objects.requireNonNull(safetyGate, "safetyGate");
        this.delayStrategy = Objects.requireNonNull(delayStrategy, "delayStrategy");
        this.variableResolver = Objects.requireNonNull(variableResolver, "variableResolver");
        templateRenderer = new HttpRequestTemplateRenderer(variableResolver);
        this.invariantEngine = Objects.requireNonNull(invariantEngine, "invariantEngine");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.executor = Objects.requireNonNull(executor, "executor");
    }

    public boolean isRunning() {
        return running.get();
    }

    public CompletableFuture<ExecutionRun> execute(
            MutationCase mutationCase,
            ExecutionPolicy policy,
            boolean stateChangingRunConfirmed
    ) {
        Objects.requireNonNull(mutationCase, "mutationCase");
        List<PlannedStep> steps = mutationCase.steps().stream()
                .map(step -> new PlannedStep(ExecutionPhase.MUTATION, step))
                .toList();
        return execute(
                new ExecutionPlan(mutationCase, steps, List.of(), List.of()),
                policy,
                stateChangingRunConfirmed
        );
    }

    public CompletableFuture<ExecutionRun> execute(
            ExecutionPlan plan,
            ExecutionPolicy policy,
            boolean stateChangingRunConfirmed
    ) {
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(policy, "policy");
        if (!running.compareAndSet(false, true)) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("Another WorkflowGuard run is already in progress")
            );
        }

        try {
            return CompletableFuture.supplyAsync(
                    () -> executeSequentially(plan, policy, stateChangingRunConfirmed),
                    executor
            ).whenComplete((ignored, throwable) -> running.set(false));
        } catch (RuntimeException exception) {
            running.set(false);
            throw exception;
        }
    }

    private ExecutionRun executeSequentially(
            ExecutionPlan plan,
            ExecutionPolicy policy,
            boolean stateChangingRunConfirmed
    ) {
        Instant startedAt = clock.instant();
        List<PlannedStep> executionSteps = refreshScope(plan.steps());
        SafetyDecision decision = safetyGate.evaluate(
                executionSteps.stream().map(PlannedStep::step).toList(),
                policy,
                stateChangingRunConfirmed
        );
        ActorOriginDecision actorOriginDecision = actorCredentialOrigins(
                plan,
                executionSteps
        );
        Map<UUID, IsolatedActorCookieJar> actorSessions = createActorSessions(
                plan,
                actorOriginDecision.origins()
        );
        List<String> preflightReasons = new ArrayList<>(decision.reasons());
        preflightReasons.addAll(actorOriginDecision.reasons());
        if (!preflightReasons.isEmpty()) {
            return new ExecutionRun(
                    UUID.randomUUID(),
                    plan.mutationCase().id(),
                    plan.mutationCase().name(),
                    RunStatus.BLOCKED,
                    startedAt,
                    clock.instant(),
                    List.of(),
                    Map.of(),
                    snapshotActorCookies(actorSessions),
                    List.of(),
                    preflightReasons
            );
        }

        List<StepExecutionResult> results = new ArrayList<>();
        Map<String, String> variables = new LinkedHashMap<>();
        Map<UUID, String> beforeProbeBodies = new LinkedHashMap<>();
        Map<UUID, String> afterProbeBodies = new LinkedHashMap<>();
        Map<UUID, String> postCleanupProbeBodies = new LinkedHashMap<>();
        boolean primaryFailed = false;
        boolean cleanupFailed = false;
        boolean mutationAttempted = false;
        boolean cleanupAttempted = false;
        int sentRequests = 0;

        for (PlannedStep plannedStep : executionSteps) {
            if (Thread.currentThread().isInterrupted()) {
                return completedRun(
                        plan,
                        RunStatus.CANCELLED,
                        startedAt,
                        results,
                        variables,
                        actorSessions,
                        List.of(),
                        List.of("Execution was interrupted.")
                );
            }
            if (shouldSkip(plannedStep.phase(), primaryFailed, mutationAttempted)) {
                continue;
            }
            if (sentRequests > 0 && !policy.delayBetweenRequests().isZero()) {
                try {
                    delayStrategy.pause(policy.delayBetweenRequests());
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    return completedRun(
                            plan,
                            RunStatus.CANCELLED,
                            startedAt,
                            results,
                            variables,
                            actorSessions,
                            List.of(),
                            List.of("Execution was interrupted during the inter-request delay.")
                    );
                }
            }

            IsolatedActorCookieJar actorSession = actorSessions.get(
                    plannedStep.step().actorId()
            );
            WorkflowStep renderedStep;
            try {
                if (actorSession == null) {
                    throw new IllegalArgumentException(
                            "Unknown actor for step: " + plannedStep.step().actorId()
                    );
                }
                String renderedRequest = templateRenderer.render(
                        plannedStep.step().rawRequest(),
                        variablesForStep(plan, plannedStep, variables)
                );
                String actualMethod = HttpRequestText.requestMethod(renderedRequest);
                if (!actualMethod.equals(plannedStep.step().method())) {
                    throw new IllegalArgumentException(
                            "Rendered request method " + actualMethod
                                    + " does not match the modeled method "
                                    + plannedStep.step().method()
                    );
                }
                String effectiveUrl = HttpRequestText.effectiveUrl(
                        plannedStep.step().url(),
                        renderedRequest
                );
                boolean effectiveInScope = scopeChecker.isInScope(effectiveUrl);
                if (policy.inScopeOnly() && !effectiveInScope) {
                    throw new IllegalArgumentException(
                            "Rendered request is outside the current Burp target scope: "
                                    + effectiveUrl
                    );
                }
                String sessionRequest = actorSession.apply(renderedRequest, effectiveUrl);
                renderedStep = plannedStep.step()
                        .withEffectiveRequest(effectiveUrl, sessionRequest)
                        .withInScope(effectiveInScope);
            } catch (RuntimeException exception) {
                StepExecutionResult failed = failedResult(
                        plannedStep,
                        actorName(plan, plannedStep.step().actorId()),
                        exception
                );
                results.add(failed);
                if (isCleanupPhase(plannedStep.phase())) {
                    cleanupFailed = true;
                } else {
                    primaryFailed = true;
                }
                continue;
            }

            if (plannedStep.phase() == ExecutionPhase.MUTATION) {
                mutationAttempted = true;
            }
            if (plannedStep.phase() == ExecutionPhase.CLEANUP) {
                cleanupAttempted = true;
            }
            StepExecutionResult result = executeStep(
                    plannedStep,
                    renderedStep,
                    actorSession,
                    plan.variables(),
                    variables
            );
            sentRequests++;
            results.add(result);
            captureProbeBody(
                    plannedStep,
                    result,
                    beforeProbeBodies,
                    afterProbeBodies,
                    postCleanupProbeBodies
            );
            if (result.status() != StepExecutionStatus.RESPONSE_RECEIVED) {
                if (isCleanupPhase(plannedStep.phase())) {
                    cleanupFailed = true;
                } else {
                    primaryFailed = true;
                }
            }
        }

        List<InvariantCheckResult> invariantResults = new ArrayList<>();
        invariantResults.addAll(evaluateInvariants(
                plan.invariants(),
                beforeProbeBodies,
                afterProbeBodies,
                ExecutionPhase.AFTER_PROBE,
                plan.volatileJsonPointers()
        ));
        if (cleanupAttempted) {
            invariantResults.addAll(evaluateInvariants(
                    plan.invariants(),
                    beforeProbeBodies,
                    postCleanupProbeBodies,
                    ExecutionPhase.POST_CLEANUP_PROBE,
                    plan.volatileJsonPointers()
            ));
        }

        RunStatus status = primaryFailed || cleanupFailed
                ? RunStatus.FAILED
                : RunStatus.COMPLETED;
        long failedChecks = invariantResults.stream()
                .filter(result -> !result.passed())
                .count();
        List<String> messages = new ArrayList<>();
        messages.add("Executed " + sentRequests + " request(s) sequentially.");
        if (!plan.mutationCase().variableOverrides().isEmpty()) {
            messages.add(
                    "Applied " + plan.mutationCase().variableOverrides().size()
                            + " stale variable override(s) to mutation-phase requests."
            );
        }
        if (primaryFailed) {
            messages.add("The primary workflow stopped after an execution failure.");
        }
        if (cleanupFailed) {
            messages.add("At least one cleanup or cleanup-verification request failed.");
        }
        if (failedChecks > 0) {
            messages.add(failedChecks + " invariant check(s) failed or could not be evaluated.");
        }

        return completedRun(
                plan,
                status,
                startedAt,
                results,
                variables,
                actorSessions,
                invariantResults,
                messages
        );
    }

    private List<PlannedStep> refreshScope(List<PlannedStep> steps) {
        return steps.stream()
                .map(planned -> new PlannedStep(
                        planned.phase(),
                        planned.step().withInScope(scopeChecker.isInScope(planned.step().url()))
                ))
                .toList();
    }

    private Map<String, String> variablesForStep(
            ExecutionPlan plan,
            PlannedStep plannedStep,
            Map<String, String> variables
    ) {
        if (plannedStep.phase() != ExecutionPhase.MUTATION
                || plan.mutationCase().variableOverrides().isEmpty()) {
            return variables;
        }
        Map<String, String> effective = new LinkedHashMap<>(variables);
        effective.putAll(plan.mutationCase().variableOverrides());
        return effective;
    }

    private boolean shouldSkip(
            ExecutionPhase phase,
            boolean primaryFailed,
            boolean mutationAttempted
    ) {
        if (!primaryFailed) {
            return false;
        }
        if (phase == ExecutionPhase.CLEANUP) {
            return !mutationAttempted;
        }
        return phase != ExecutionPhase.POST_CLEANUP_PROBE || !mutationAttempted;
    }

    private boolean isCleanupPhase(ExecutionPhase phase) {
        return phase == ExecutionPhase.CLEANUP
                || phase == ExecutionPhase.POST_CLEANUP_PROBE;
    }

    private StepExecutionResult executeStep(
            PlannedStep plannedStep,
            WorkflowStep renderedStep,
            IsolatedActorCookieJar actorSession,
            List<VariableDefinition> definitions,
            Map<String, String> variables
    ) {
        Instant startedAt = clock.instant();
        long startedNanos = System.nanoTime();
        try {
            HttpExchangeEvidence evidence = requestSender.send(renderedStep);
            actorSession.capture(renderedStep.url(), evidence);
            Duration duration = Duration.ofNanos(System.nanoTime() - startedNanos);
            if (!evidence.hasResponse()) {
                return new StepExecutionResult(
                        renderedStep.id(),
                        renderedStep.name(),
                        renderedStep.method(),
                        renderedStep.url(),
                        renderedStep.actorId(),
                        actorSession.actor().name(),
                        plannedStep.phase(),
                        startedAt,
                        duration,
                        StepExecutionStatus.NO_RESPONSE,
                        Optional.empty(),
                        renderedStep.rawRequest(),
                        "",
                        "",
                        Map.of(),
                        Optional.empty()
                );
            }
            Map<String, String> extracted = extractVariables(
                    definitions,
                    renderedStep.id(),
                    evidence.responseBody()
            );
            variables.putAll(extracted);
            return new StepExecutionResult(
                    renderedStep.id(),
                    renderedStep.name(),
                    renderedStep.method(),
                    renderedStep.url(),
                    renderedStep.actorId(),
                    actorSession.actor().name(),
                    plannedStep.phase(),
                    startedAt,
                    duration,
                    StepExecutionStatus.RESPONSE_RECEIVED,
                    evidence.statusCode(),
                    renderedStep.rawRequest(),
                    evidence.rawResponse(),
                    evidence.responseBody(),
                    extracted,
                    Optional.empty()
            );
        } catch (RuntimeException exception) {
            return new StepExecutionResult(
                    renderedStep.id(),
                    renderedStep.name(),
                    renderedStep.method(),
                    renderedStep.url(),
                    renderedStep.actorId(),
                    actorSession.actor().name(),
                    plannedStep.phase(),
                    startedAt,
                    Duration.ofNanos(System.nanoTime() - startedNanos),
                    StepExecutionStatus.FAILED,
                    Optional.empty(),
                    renderedStep.rawRequest(),
                    "",
                    "",
                    Map.of(),
                    errorMessage(exception)
            );
        }
    }

    private StepExecutionResult failedResult(
            PlannedStep plannedStep,
            String actorName,
            RuntimeException exception
    ) {
        WorkflowStep step = plannedStep.step();
        return new StepExecutionResult(
                step.id(),
                step.name(),
                step.method(),
                step.url(),
                step.actorId(),
                actorName,
                plannedStep.phase(),
                clock.instant(),
                Duration.ZERO,
                StepExecutionStatus.FAILED,
                Optional.empty(),
                step.rawRequest(),
                "",
                "",
                Map.of(),
                errorMessage(exception)
        );
    }

    private Optional<String> errorMessage(RuntimeException exception) {
        return Optional.ofNullable(exception.getMessage())
                .filter(message -> !message.isBlank())
                .or(() -> Optional.of(exception.getClass().getSimpleName()));
    }

    private Map<String, String> extractVariables(
            List<VariableDefinition> definitions,
            UUID sourceStepId,
            String responseBody
    ) {
        Map<String, String> extracted = new LinkedHashMap<>();
        definitions.stream()
                .filter(definition -> definition.sourceStepId().equals(sourceStepId))
                .forEach(definition -> variableResolver.extract(definition, responseBody)
                        .ifPresent(value -> extracted.put(definition.name(), value)));
        return Map.copyOf(extracted);
    }

    private void captureProbeBody(
            PlannedStep plannedStep,
            StepExecutionResult result,
            Map<UUID, String> before,
            Map<UUID, String> after,
            Map<UUID, String> postCleanup
    ) {
        if (result.status() != StepExecutionStatus.RESPONSE_RECEIVED) {
            return;
        }
        switch (plannedStep.phase()) {
            case BEFORE_PROBE -> before.put(result.stepId(), result.responseBody());
            case AFTER_PROBE -> after.put(result.stepId(), result.responseBody());
            case POST_CLEANUP_PROBE -> postCleanup.put(result.stepId(), result.responseBody());
            default -> {
            }
        }
    }

    private List<InvariantCheckResult> evaluateInvariants(
            List<ProbeInvariant> invariants,
            Map<UUID, String> beforeBodies,
            Map<UUID, String> comparisonBodies,
            ExecutionPhase comparisonPhase,
            Set<String> volatileJsonPointers
    ) {
        List<InvariantCheckResult> results = new ArrayList<>();
        for (ProbeInvariant definition : invariants) {
            String before = beforeBodies.get(definition.probeStepId());
            String comparison = comparisonBodies.get(definition.probeStepId());
            if (before == null || comparison == null) {
                results.add(new InvariantCheckResult(
                        definition.id(),
                        definition.invariant().name(),
                        definition.probeStepId(),
                        comparisonPhase,
                        List.of(),
                        Optional.of("The required before/after probe response is missing.")
                ));
                continue;
            }
            try {
                List<InvariantViolation> violations = invariantEngine.evaluate(
                        List.of(definition.invariant()),
                        before,
                        comparison,
                        volatileJsonPointers
                );
                results.add(new InvariantCheckResult(
                        definition.id(),
                        definition.invariant().name(),
                        definition.probeStepId(),
                        comparisonPhase,
                        violations,
                        Optional.empty()
                ));
            } catch (RuntimeException exception) {
                results.add(new InvariantCheckResult(
                        definition.id(),
                        definition.invariant().name(),
                        definition.probeStepId(),
                        comparisonPhase,
                        List.of(),
                        errorMessage(exception)
                ));
            }
        }
        return List.copyOf(results);
    }

    private Map<UUID, IsolatedActorCookieJar> createActorSessions(
            ExecutionPlan plan,
            Map<UUID, String> credentialOrigins
    ) {
        Map<UUID, IsolatedActorCookieJar> sessions = new LinkedHashMap<>();
        for (ActorDefinition actor : plan.actors()) {
            String credentialOrigin = credentialOrigins.get(actor.id());
            sessions.put(
                    actor.id(),
                    credentialOrigin == null
                            ? new IsolatedActorCookieJar(actor)
                            : new IsolatedActorCookieJar(actor, credentialOrigin)
            );
        }
        return sessions;
    }

    private ActorOriginDecision actorCredentialOrigins(
            ExecutionPlan plan,
            List<PlannedStep> steps
    ) {
        Map<UUID, String> origins = new LinkedHashMap<>();
        List<String> reasons = new ArrayList<>();
        for (ActorDefinition actor : plan.actors()) {
            if (!actor.hasConfiguredCredentials()) {
                continue;
            }
            Set<String> assignedOrigins = steps.stream()
                    .map(PlannedStep::step)
                    .filter(step -> step.actorId().equals(actor.id()))
                    .map(WorkflowStep::url)
                    .map(HttpRequestText::origin)
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
            if (assignedOrigins.size() > 1) {
                reasons.add(
                        "Actor '" + actor.name() + "' has configured credentials but is "
                                + "assigned to multiple origins. Split the actor before running."
                );
            } else if (assignedOrigins.size() == 1) {
                origins.put(actor.id(), assignedOrigins.iterator().next());
            }
        }
        return new ActorOriginDecision(Map.copyOf(origins), List.copyOf(reasons));
    }

    private String actorName(ExecutionPlan plan, UUID actorId) {
        return plan.actors().stream()
                .filter(actor -> actor.id().equals(actorId))
                .map(ActorDefinition::name)
                .findFirst()
                .orElse("Unknown actor");
    }

    private Map<String, Map<String, String>> snapshotActorCookies(
            Map<UUID, IsolatedActorCookieJar> actorSessions
    ) {
        Map<String, Map<String, String>> snapshot = new LinkedHashMap<>();
        for (IsolatedActorCookieJar session : actorSessions.values()) {
            snapshot.put(session.actor().name(), session.snapshot());
        }
        return Map.copyOf(snapshot);
    }

    private ExecutionRun completedRun(
            ExecutionPlan plan,
            RunStatus status,
            Instant startedAt,
            List<StepExecutionResult> results,
            Map<String, String> variables,
            Map<UUID, IsolatedActorCookieJar> actorSessions,
            List<InvariantCheckResult> invariantResults,
            List<String> messages
    ) {
        return new ExecutionRun(
                UUID.randomUUID(),
                plan.mutationCase().id(),
                plan.mutationCase().name(),
                status,
                startedAt,
                clock.instant(),
                results,
                variables,
                snapshotActorCookies(actorSessions),
                invariantResults,
                messages
        );
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }

    private static final class ExecutionThreadFactory implements ThreadFactory {
        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "workflowguard-execution");
            thread.setDaemon(true);
            return thread;
        }
    }

    private record ActorOriginDecision(
            Map<UUID, String> origins,
            List<String> reasons
    ) {
    }
}

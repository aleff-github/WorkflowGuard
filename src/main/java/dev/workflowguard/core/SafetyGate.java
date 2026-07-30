package dev.workflowguard.core;

import dev.workflowguard.domain.ExecutionPolicy;
import dev.workflowguard.domain.WorkflowStep;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public final class SafetyGate {
    private static final Set<String> READ_ONLY_METHODS = Set.of("GET", "HEAD", "OPTIONS", "TRACE");

    public SafetyDecision evaluate(
            List<WorkflowStep> steps,
            ExecutionPolicy policy,
            boolean stateChangingRunConfirmed
    ) {
        Objects.requireNonNull(steps, "steps");
        Objects.requireNonNull(policy, "policy");
        List<String> reasons = new ArrayList<>();

        if (steps.isEmpty()) {
            reasons.add("The mutation contains no requests.");
        }
        if (steps.size() > policy.maximumRequests()) {
            reasons.add("The mutation exceeds the maximum request count of " + policy.maximumRequests() + ".");
        }
        if (policy.inScopeOnly() && steps.stream().anyMatch(step -> !step.inScope())) {
            reasons.add("At least one request is outside the Burp target scope.");
        }
        for (WorkflowStep step : steps) {
            try {
                HttpRequestText.validateTemplate(
                        step.method(),
                        step.url(),
                        step.rawRequest()
                );
            } catch (IllegalArgumentException exception) {
                reasons.add(
                        "Step '" + step.name() + "' has an unsafe request template: "
                                + exception.getMessage()
                );
            }
        }

        boolean stateChanging = steps.stream().anyMatch(this::isStateChanging);
        if (stateChanging && policy.requireStateChangingConfirmation() && !stateChangingRunConfirmed) {
            reasons.add("Explicit confirmation is required for state-changing requests.");
        }

        return reasons.isEmpty()
                ? new SafetyDecision(true, List.of())
                : new SafetyDecision(false, reasons);
    }

    public boolean isStateChanging(WorkflowStep step) {
        Objects.requireNonNull(step, "step");
        try {
            String method = HttpRequestText.requestMethod(step.rawRequest());
            return step.category().isStateChanging() || !READ_ONLY_METHODS.contains(method);
        } catch (IllegalArgumentException exception) {
            return true;
        }
    }
}

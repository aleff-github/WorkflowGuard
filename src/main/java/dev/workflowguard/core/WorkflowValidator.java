package dev.workflowguard.core;

import dev.workflowguard.domain.ActorDefinition;
import dev.workflowguard.domain.ProbeInvariant;
import dev.workflowguard.domain.StepRole;
import dev.workflowguard.domain.VariableDefinition;
import dev.workflowguard.domain.Workflow;
import dev.workflowguard.domain.WorkflowStep;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Structural and resource-limit validation shared by project persistence and
 * portable workflow archives.
 */
public final class WorkflowValidator {
    public static final int MAXIMUM_WORKFLOWS = 100;
    public static final int MAXIMUM_STEPS = 1_000;
    public static final int MAXIMUM_ACTORS = 100;
    public static final int MAXIMUM_VARIABLES = 500;
    public static final int MAXIMUM_INVARIANTS = 500;
    public static final int MAXIMUM_VOLATILE_POINTERS = 500;
    public static final int MAXIMUM_RAW_REQUEST_CHARACTERS = 1_048_576;
    public static final int MAXIMUM_TOTAL_REQUEST_CHARACTERS = 8_388_608;
    public static final int MAXIMUM_URL_CHARACTERS = 8_192;
    public static final int MAXIMUM_DISPLAY_NAME_CHARACTERS = 256;
    public static final int MAXIMUM_POINTER_CHARACTERS = 4_096;
    public static final int MAXIMUM_INVARIANT_EXPRESSION_CHARACTERS = 65_536;

    public Workflow validate(Workflow workflow) {
        Objects.requireNonNull(workflow, "workflow");
        requireLength(workflow.name(), MAXIMUM_DISPLAY_NAME_CHARACTERS, "workflow name");
        requireMaximum(workflow.steps().size(), MAXIMUM_STEPS, "steps");
        requireMaximum(workflow.actors().size(), MAXIMUM_ACTORS, "actors");
        requireMaximum(workflow.variables().size(), MAXIMUM_VARIABLES, "variables");
        requireMaximum(workflow.invariants().size(), MAXIMUM_INVARIANTS, "invariants");
        requireMaximum(
                workflow.volatileJsonPointers().size(),
                MAXIMUM_VOLATILE_POINTERS,
                "volatile JSON pointers"
        );

        requireUnique(
                workflow.steps().stream().map(WorkflowStep::id).toList(),
                "step IDs"
        );
        requireUnique(
                workflow.actors().stream().map(ActorDefinition::id).toList(),
                "actor IDs"
        );
        requireUnique(
                workflow.actors().stream()
                        .map(actor -> actor.name().toLowerCase(Locale.ROOT))
                        .toList(),
                "actor names"
        );
        requireUnique(
                workflow.variables().stream().map(VariableDefinition::name).toList(),
                "variable names"
        );
        requireUnique(
                workflow.invariants().stream().map(ProbeInvariant::id).toList(),
                "invariant IDs"
        );

        long totalRequestCharacters = 0;
        for (WorkflowStep step : workflow.steps()) {
            requireLength(
                    step.name(),
                    MAXIMUM_DISPLAY_NAME_CHARACTERS,
                    "step name"
            );
            requireLength(step.url(), MAXIMUM_URL_CHARACTERS, "step URL");
            requireLength(
                    step.rawRequest(),
                    MAXIMUM_RAW_REQUEST_CHARACTERS,
                    "raw request"
            );
            HttpRequestText.validateTemplate(step.method(), step.url(), step.rawRequest());
            totalRequestCharacters += step.rawRequest().length();
            if (totalRequestCharacters > MAXIMUM_TOTAL_REQUEST_CHARACTERS) {
                throw new IllegalArgumentException(
                        "Workflow request templates exceed the 8 MiB aggregate limit"
                );
            }
        }

        Set<UUID> stepIds = workflow.steps().stream()
                .map(WorkflowStep::id)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        Set<UUID> actorIds = workflow.actors().stream()
                .map(ActorDefinition::id)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        workflow.steps().forEach(step -> {
            if (!actorIds.contains(step.actorId())) {
                throw new IllegalArgumentException(
                        "Workflow step references an unknown actor: " + step.name()
                );
            }
        });
        workflow.variables().forEach(variable -> {
            if (!stepIds.contains(variable.sourceStepId())) {
                throw new IllegalArgumentException(
                        "Variable references an unknown source step: " + variable.name()
                );
            }
        });
        workflow.invariants().forEach(invariant -> validateInvariant(
                workflow,
                stepIds,
                invariant
        ));
        workflow.volatileJsonPointers().forEach(pointer ->
                requireLength(pointer, MAXIMUM_POINTER_CHARACTERS, "volatile JSON pointer"));
        return workflow;
    }

    private void validateInvariant(
            Workflow workflow,
            Set<UUID> stepIds,
            ProbeInvariant invariant
    ) {
        requireLength(
                invariant.invariant().name(),
                MAXIMUM_DISPLAY_NAME_CHARACTERS,
                "invariant name"
        );
        requireLength(
                invariant.invariant().jsonPointer(),
                MAXIMUM_POINTER_CHARACTERS,
                "invariant JSON pointer"
        );
        if (invariant.invariant().expectedJson() != null) {
            requireLength(
                    invariant.invariant().expectedJson(),
                    MAXIMUM_INVARIANT_EXPRESSION_CHARACTERS,
                    "invariant expected value or expression"
            );
        }
        if (!stepIds.contains(invariant.probeStepId())) {
            throw new IllegalArgumentException(
                    "Invariant references an unknown probe step: "
                            + invariant.invariant().name()
            );
        }
        WorkflowStep probe = workflow.steps().stream()
                .filter(step -> step.id().equals(invariant.probeStepId()))
                .findFirst()
                .orElseThrow();
        if (probe.role() != StepRole.PROBE) {
            throw new IllegalArgumentException(
                    "Invariant source is not a PROBE step: "
                            + invariant.invariant().name()
            );
        }
    }

    private void requireMaximum(int actual, int maximum, String label) {
        if (actual > maximum) {
            throw new IllegalArgumentException(
                    "Workflow contains too many " + label + " (maximum " + maximum + ")"
            );
        }
    }

    private void requireLength(String value, int maximum, String label) {
        Objects.requireNonNull(value, label);
        if (value.length() > maximum) {
            throw new IllegalArgumentException(
                    label + " exceeds the " + maximum + " character limit"
            );
        }
    }

    private void requireUnique(List<?> values, String label) {
        if (new HashSet<>(values).size() != values.size()) {
            throw new IllegalArgumentException("Workflow contains duplicate " + label);
        }
    }
}

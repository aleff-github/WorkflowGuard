package dev.workflowguard.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.workflowguard.core.ExecutionRunSummarizer;
import dev.workflowguard.core.InvariantViolation;
import dev.workflowguard.domain.ExecutionRun;
import dev.workflowguard.domain.ExecutionRunSummary;
import dev.workflowguard.domain.InvariantCheckResult;
import dev.workflowguard.domain.StepExecutionResult;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class JsonEvidenceExporter {
    private final ObjectMapper objectMapper;
    private final SensitiveDataRedactor redactor;
    private final ExecutionRunSummarizer runSummarizer = new ExecutionRunSummarizer();

    public JsonEvidenceExporter() {
        this(new ObjectMapper());
    }

    JsonEvidenceExporter(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        redactor = new SensitiveDataRedactor(objectMapper);
    }

    public void write(Path destination, ExecutionRun run) throws IOException {
        Objects.requireNonNull(destination, "destination");
        Path absolute = destination.toAbsolutePath().normalize();
        Path parent = absolute.getParent();
        if (parent == null || !Files.isDirectory(parent)) {
            throw new IOException("Destination directory does not exist: " + parent);
        }
        Path temporary = Files.createTempFile(parent, ".workflowguard-evidence-", ".tmp");
        try {
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(
                    temporary.toFile(),
                    exportModel(run)
            );
            try {
                Files.move(
                        temporary,
                        absolute,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING
                );
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(
                        temporary,
                        absolute,
                        StandardCopyOption.REPLACE_EXISTING
                );
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    public Map<String, Object> exportModel(ExecutionRun run) {
        Objects.requireNonNull(run, "run");
        ExecutionRunSummary summary = runSummarizer.summarize(run);
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("schemaVersion", 1);
        document.put("redactionApplied", true);
        document.put("redactionPolicy", "conservative-v2");
        document.put("httpBodiesOmitted", true);
        document.put("runId", run.id().toString());
        document.put("mutationCaseId", run.mutationCaseId().toString());
        document.put("mutationCaseName", redactor.redactFreeText(run.mutationCaseName()));
        document.put("status", run.status().name());
        document.put("startedAt", run.startedAt().toString());
        document.put("finishedAt", run.finishedAt().toString());
        document.put("durationMilliseconds", run.duration().toMillis());
        document.put("assessment", summary.assessment().name());
        document.put("actors", redactor.redactFreeText(summary.actors()));
        document.put("mutationHttpStatuses", summary.mutationHttpStatuses());
        document.put("passedInvariantChecks", summary.passedInvariantChecks());
        document.put("mutationViolations", summary.mutationViolations());
        document.put("cleanupViolations", summary.cleanupViolations());
        document.put(
                "messages",
                run.messages().stream().map(redactor::redactFreeText).toList()
        );
        document.put("variables", redactNamedValues(run.variables()));
        document.put("actorCookies", redactActorCookies(run.actorCookies()));
        document.put(
                "steps",
                run.stepResults().stream().map(this::stepModel).toList()
        );
        document.put(
                "invariantChecks",
                run.invariantResults().stream().map(this::invariantModel).toList()
        );
        return document;
    }

    private Map<String, Object> stepModel(StepExecutionResult result) {
        Map<String, Object> step = new LinkedHashMap<>();
        step.put("stepId", result.stepId().toString());
        step.put("stepName", redactor.redactFreeText(result.stepName()));
        step.put("actorId", result.actorId().toString());
        step.put("actorName", redactor.redactFreeText(result.actorName()));
        step.put("phase", result.phase().name());
        step.put("method", result.method());
        step.put("url", redactor.redactEvidenceUrl(result.url()));
        step.put("startedAt", result.startedAt().toString());
        step.put("durationMilliseconds", result.duration().toMillis());
        step.put("outcome", result.status().name());
        step.put("httpStatus", result.statusCode().orElse(null));
        step.put("request", redactor.redactEvidenceHttpMessage(result.rawRequest()));
        step.put("response", redactor.redactEvidenceHttpMessage(result.rawResponse()));
        step.put("extractedVariables", redactNamedValues(result.extractedVariables()));
        step.put(
                "error",
                result.errorMessage().map(redactor::redactFreeText).orElse(null)
        );
        return step;
    }

    private Map<String, Object> invariantModel(InvariantCheckResult result) {
        Map<String, Object> invariant = new LinkedHashMap<>();
        invariant.put("invariantId", result.invariantId().toString());
        invariant.put("invariantName", redactor.redactFreeText(result.invariantName()));
        invariant.put("probeStepId", result.probeStepId().toString());
        invariant.put("comparisonPhase", result.comparisonPhase().name());
        invariant.put("passed", result.passed());
        invariant.put(
                "error",
                result.errorMessage().map(redactor::redactFreeText).orElse(null)
        );
        invariant.put(
                "violations",
                result.violations().stream().map(this::violationModel).toList()
        );
        return invariant;
    }

    private Map<String, Object> violationModel(InvariantViolation violation) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("jsonPointer", violation.jsonPointer());
        item.put("message", violation.message());
        item.put(
                "beforeValue",
                violation.beforeValue() == null
                        ? null
                        : SensitiveDataRedactor.REDACTED
        );
        item.put(
                "afterValue",
                violation.afterValue() == null
                        ? null
                        : SensitiveDataRedactor.REDACTED
        );
        return item;
    }

    private Map<String, String> redactNamedValues(Map<String, String> values) {
        Map<String, String> redacted = new LinkedHashMap<>();
        values.forEach((name, value) ->
                redacted.put(name, value == null || value.isEmpty()
                        ? value
                        : SensitiveDataRedactor.REDACTED));
        return Map.copyOf(redacted);
    }

    private Map<String, Map<String, String>> redactActorCookies(
            Map<String, Map<String, String>> actorCookies
    ) {
        Map<String, Map<String, String>> redacted = new LinkedHashMap<>();
        actorCookies.forEach((actor, cookies) -> {
            Map<String, String> cookieNames = new LinkedHashMap<>();
            int index = 1;
            for (int ignored = 0; ignored < cookies.size(); ignored++) {
                cookieNames.put(
                        "cookie-" + index++,
                        SensitiveDataRedactor.REDACTED
                );
            }
            String safeActor = redactor.redactFreeText(actor);
            String uniqueActor = safeActor;
            int suffix = 2;
            while (redacted.containsKey(uniqueActor)) {
                uniqueActor = safeActor + " #" + suffix++;
            }
            redacted.put(uniqueActor, Map.copyOf(cookieNames));
        });
        return Map.copyOf(redacted);
    }
}

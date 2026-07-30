package dev.workflowguard.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import dev.workflowguard.core.WorkflowValidator;
import dev.workflowguard.domain.ActorDefinition;
import dev.workflowguard.domain.VariableDefinition;
import dev.workflowguard.domain.Workflow;
import dev.workflowguard.domain.WorkflowStep;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

public final class WorkflowArchiveService {
    private static final int CURRENT_SCHEMA_VERSION = 1;
    public static final long MAXIMUM_ARCHIVE_BYTES = 10L * 1024L * 1024L;

    private final ObjectMapper objectMapper;
    private final SensitiveDataRedactor redactor;
    private final WorkflowValidator workflowValidator = new WorkflowValidator();

    public WorkflowArchiveService() {
        objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        redactor = new SensitiveDataRedactor(objectMapper);
    }

    public void write(Path destination, Workflow workflow, boolean includeSecrets)
            throws IOException {
        Objects.requireNonNull(destination, "destination");
        Path absolute = destination.toAbsolutePath().normalize();
        Path parent = absolute.getParent();
        if (parent == null || !Files.isDirectory(parent)) {
            throw new IOException("Destination directory does not exist: " + parent);
        }
        Path temporary = Files.createTempFile(parent, ".workflowguard-", ".tmp");
        try {
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(
                    temporary.toFile(),
                    archive(workflow, includeSecrets)
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

    public String exportJson(Workflow workflow, boolean includeSecrets) {
        try {
            return objectMapper.writerWithDefaultPrettyPrinter()
                    .writeValueAsString(archive(workflow, includeSecrets));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to serialize workflow archive", exception);
        }
    }

    public Workflow read(Path source) throws IOException {
        Objects.requireNonNull(source, "source");
        if (!Files.isRegularFile(source)) {
            throw new IOException("Workflow archive is not a regular file");
        }
        if (Files.size(source) > MAXIMUM_ARCHIVE_BYTES) {
            throw new IOException("Workflow archive exceeds the 10 MiB size limit");
        }
        return validate(objectMapper.readValue(source.toFile(), WorkflowArchive.class));
    }

    public Workflow importJson(String document) {
        Objects.requireNonNull(document, "document");
        if (document.length() > MAXIMUM_ARCHIVE_BYTES) {
            throw new IllegalArgumentException(
                    "Workflow archive exceeds the 10 MiB size limit"
            );
        }
        try {
            return validate(objectMapper.readValue(document, WorkflowArchive.class));
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Invalid workflow archive", exception);
        }
    }

    private WorkflowArchive archive(Workflow workflow, boolean includeSecrets) {
        Objects.requireNonNull(workflow, "workflow");
        Workflow validated = workflowValidator.validate(workflow);
        return new WorkflowArchive(
                CURRENT_SCHEMA_VERSION,
                Instant.now(),
                includeSecrets,
                includeSecrets ? validated : redact(validated)
        );
    }

    private Workflow validate(WorkflowArchive archive) {
        if (archive.schemaVersion() != CURRENT_SCHEMA_VERSION) {
            throw new IllegalArgumentException(
                    "Unsupported workflow archive schema " + archive.schemaVersion()
            );
        }
        return workflowValidator.validate(archive.workflow());
    }

    private Workflow redact(Workflow workflow) {
        List<ActorDefinition> actors = workflow.actors().stream()
                .map(actor -> new ActorDefinition(
                        actor.id(),
                        actor.name(),
                        actor.initialCookieHeader().isBlank()
                                ? ""
                                : SensitiveDataRedactor.REDACTED,
                        actor.initialAuthorizationHeader().isBlank()
                                ? ""
                                : SensitiveDataRedactor.REDACTED,
                        actor.seedFromCapturedRequest()
                ))
                .toList();
        List<VariableDefinition> variables = workflow.variables().stream()
                .map(variable -> new VariableDefinition(
                        variable.name(),
                        variable.sourceStepId(),
                        variable.extractionType(),
                        variable.expression(),
                        variable.captureGroup(),
                        redactor.redactNamedValue(variable.name(), variable.staleValue())
                ))
                .toList();
        return new Workflow(
                workflow.id(),
                workflow.name(),
                workflow.steps().stream()
                        .map(step -> new WorkflowStep(
                                step.id(),
                                step.name(),
                                step.method(),
                                redactor.redactQuery(step.url()),
                                redactor.redactHttpMessage(step.rawRequest()),
                                step.category(),
                                step.role(),
                                step.actorId(),
                                step.enabled(),
                                step.inScope()
                        ))
                        .toList(),
                actors,
                variables,
                workflow.invariants(),
                workflow.volatileJsonPointers(),
                workflow.createdAt()
        );
    }

    public boolean containsRedactions(Workflow workflow) {
        Objects.requireNonNull(workflow, "workflow");
        return workflow.actors().stream().anyMatch(
                actor -> actor.initialCookieHeader().contains(SensitiveDataRedactor.REDACTED)
                        || actor.initialAuthorizationHeader().contains(
                                SensitiveDataRedactor.REDACTED
                        )
        ) || workflow.variables().stream().anyMatch(
                variable -> variable.staleValue().contains(SensitiveDataRedactor.REDACTED)
        ) || workflow.steps().stream().anyMatch(
                step -> step.url().contains(SensitiveDataRedactor.REDACTED)
                        || step.url().contains(SensitiveDataRedactor.URL_REDACTED)
                        || step.rawRequest().contains(SensitiveDataRedactor.REDACTED)
                        || step.rawRequest().contains(SensitiveDataRedactor.URL_REDACTED)
        );
    }

    private record WorkflowArchive(
            int schemaVersion,
            Instant exportedAt,
            boolean secretsIncluded,
            Workflow workflow
    ) {
        private WorkflowArchive {
            Objects.requireNonNull(exportedAt, "exportedAt");
            Objects.requireNonNull(workflow, "workflow");
        }
    }
}

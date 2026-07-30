package dev.workflowguard.adapters.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import dev.workflowguard.core.WorkflowValidator;
import dev.workflowguard.domain.Workflow;
import dev.workflowguard.ports.WorkflowDocumentStore;
import dev.workflowguard.ports.WorkflowRepository;

import java.util.List;
import java.util.HashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

public final class JsonWorkflowRepository implements WorkflowRepository {
    private static final int CURRENT_SCHEMA_VERSION = 2;
    private static final int MAXIMUM_DOCUMENT_CHARACTERS = 20 * 1024 * 1024;

    private final InMemoryWorkflowRepository delegate = new InMemoryWorkflowRepository();
    private final WorkflowDocumentStore documentStore;
    private final ObjectMapper objectMapper;
    private final Consumer<String> warningSink;
    private final WorkflowValidator workflowValidator = new WorkflowValidator();

    public JsonWorkflowRepository(
            WorkflowDocumentStore documentStore,
            Consumer<String> warningSink
    ) {
        this.documentStore = Objects.requireNonNull(documentStore, "documentStore");
        this.warningSink = Objects.requireNonNull(warningSink, "warningSink");
        objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        load();
    }

    @Override
    public synchronized void save(Workflow workflow) {
        Workflow validated = workflowValidator.validate(workflow);
        boolean newWorkflow = delegate.findById(validated.id()).isEmpty();
        if (newWorkflow
                && delegate.findAll().size() >= WorkflowValidator.MAXIMUM_WORKFLOWS) {
            throw new IllegalStateException(
                    "Workflow project has reached the "
                            + WorkflowValidator.MAXIMUM_WORKFLOWS
                            + " workflow limit"
            );
        }
        Optional<Workflow> previous = delegate.findById(validated.id());
        delegate.save(validated);
        try {
            persist();
        } catch (RuntimeException exception) {
            previous.ifPresentOrElse(delegate::save, () -> delegate.deleteById(validated.id()));
            throw exception;
        }
    }

    @Override
    public synchronized Optional<Workflow> findById(UUID id) {
        return delegate.findById(id);
    }

    @Override
    public synchronized List<Workflow> findAll() {
        return delegate.findAll();
    }

    @Override
    public synchronized void deleteById(UUID id) {
        Optional<Workflow> previous = delegate.findById(id);
        delegate.deleteById(id);
        try {
            persist();
        } catch (RuntimeException exception) {
            previous.ifPresent(delegate::save);
            throw exception;
        }
    }

    private void load() {
        documentStore.read().ifPresent(document -> {
            try {
                if (document.length() > MAXIMUM_DOCUMENT_CHARACTERS) {
                    throw new IllegalArgumentException(
                            "Persisted workflow data exceeds the 20 MiB limit"
                    );
                }
                WorkflowDocument decoded = objectMapper.readValue(
                        document,
                        WorkflowDocument.class
                );
                if (decoded.schemaVersion() < 1
                        || decoded.schemaVersion() > CURRENT_SCHEMA_VERSION) {
                    warningSink.accept(
                            "Ignoring unsupported workflow document schema "
                                    + decoded.schemaVersion() + "."
                    );
                    return;
                }
                if (decoded.workflows().size() > WorkflowValidator.MAXIMUM_WORKFLOWS) {
                    throw new IllegalArgumentException(
                            "Persisted data contains too many workflows"
                    );
                }
                if (new HashSet<>(decoded.workflows().stream()
                        .map(Workflow::id)
                        .toList()).size() != decoded.workflows().size()) {
                    throw new IllegalArgumentException(
                            "Persisted data contains duplicate workflow IDs"
                    );
                }
                List<Workflow> validated = decoded.workflows().stream()
                        .map(workflowValidator::validate)
                        .toList();
                validated.forEach(delegate::save);
            } catch (JsonProcessingException | IllegalArgumentException exception) {
                warningSink.accept(
                        "Ignoring invalid persisted WorkflowGuard data: " + exception.getMessage()
                );
            }
        });
    }

    private void persist() {
        try {
            String document = objectMapper.writeValueAsString(
                    new WorkflowDocument(CURRENT_SCHEMA_VERSION, delegate.findAll())
            );
            if (document.length() > MAXIMUM_DOCUMENT_CHARACTERS) {
                throw new IllegalStateException(
                        "Workflow project data exceeds the 20 MiB persistence limit"
                );
            }
            documentStore.write(document);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to persist workflows", exception);
        }
    }

    private record WorkflowDocument(int schemaVersion, List<Workflow> workflows) {
        private WorkflowDocument {
            workflows = List.copyOf(Objects.requireNonNull(workflows, "workflows"));
        }
    }
}

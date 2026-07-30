package dev.workflowguard.adapters.persistence;

import burp.api.montoya.persistence.PersistedObject;
import dev.workflowguard.ports.WorkflowDocumentStore;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

public final class MontoyaWorkflowDocumentStore implements WorkflowDocumentStore {
    private static final String DOCUMENT_KEY = "workflowguard.workflows.v1";

    private final PersistedObject persistedObject;
    private final Consumer<String> warningSink;

    public MontoyaWorkflowDocumentStore(
            PersistedObject persistedObject,
            Consumer<String> warningSink
    ) {
        this.persistedObject = Objects.requireNonNull(persistedObject, "persistedObject");
        this.warningSink = Objects.requireNonNull(warningSink, "warningSink");
    }

    @Override
    public Optional<String> read() {
        try {
            return Optional.ofNullable(persistedObject.getString(DOCUMENT_KEY));
        } catch (RuntimeException exception) {
            warningSink.accept("Project persistence is unavailable: " + exception.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public void write(String document) {
        try {
            persistedObject.setString(DOCUMENT_KEY, document);
        } catch (RuntimeException exception) {
            warningSink.accept("Unable to persist workflows in this Burp project: " + exception.getMessage());
        }
    }
}

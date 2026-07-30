package dev.workflowguard.adapters.persistence;

import dev.workflowguard.ports.WorkflowDocumentStore;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

public final class InMemoryWorkflowDocumentStore implements WorkflowDocumentStore {
    private final AtomicReference<String> document = new AtomicReference<>();

    @Override
    public Optional<String> read() {
        return Optional.ofNullable(document.get());
    }

    @Override
    public void write(String newDocument) {
        document.set(newDocument);
    }
}

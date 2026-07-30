package dev.workflowguard.ports;

import java.util.Optional;

public interface WorkflowDocumentStore {
    Optional<String> read();

    void write(String document);
}

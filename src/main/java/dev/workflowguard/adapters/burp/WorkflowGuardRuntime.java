package dev.workflowguard.adapters.burp;

import dev.workflowguard.adapters.ui.WorkflowGuardTab;

import java.util.Objects;

final class WorkflowGuardRuntime implements AutoCloseable {
    private final WorkflowGuardTab tab;

    WorkflowGuardRuntime(WorkflowGuardTab tab) {
        this.tab = Objects.requireNonNull(tab, "tab");
    }

    @Override
    public void close() {
        tab.close();
    }
}

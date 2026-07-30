package dev.workflowguard.adapters.ui;

import dev.workflowguard.adapters.persistence.InMemoryWorkflowRepository;
import dev.workflowguard.application.ExecutionCoordinator;
import dev.workflowguard.application.WorkflowService;
import dev.workflowguard.core.MutationEngine;
import dev.workflowguard.core.StepClassifier;
import dev.workflowguard.ports.AuditIssuePublisher;
import dev.workflowguard.ports.HttpExchangeEvidence;
import org.junit.jupiter.api.Test;

import javax.swing.JCheckBox;
import javax.swing.JPanel;
import java.awt.Component;
import java.awt.Container;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class WorkflowGuardTabTest {
    @Test
    void communityEditionLabelsTheProfessionalOnlyIssueViewer() {
        try (var tab = tab(false, ignored -> {
        })) {
            assertEquals(
                    "Publish invariant failures to Burp issues (Pro viewer only)",
                    issuePublicationCheckBox(tab).getText()
            );
        }
    }

    @Test
    void reportsUnexpectedErrorsWithoutReplacingTheOriginalFailure() {
        AtomicReference<Throwable> reported = new AtomicReference<>();
        try (var tab = tab(true, reported::set)) {
            IllegalStateException failure = new IllegalStateException("worker failed");

            tab.reportUnexpectedError(failure);

            assertSame(failure, reported.get());
        }

        try (var tab = tab(true, ignored -> {
            throw new IllegalStateException("reporter failed");
        })) {
            assertDoesNotThrow(
                    () -> tab.reportUnexpectedError(new IllegalStateException("original"))
            );
        }
    }

    @Test
    void usesTheProvidedBurpSuiteFrameAsDialogParent() {
        JPanel suiteFrame = new JPanel();
        try (var tab = tab(true, ignored -> {
        }, suiteFrame)) {
            assertSame(suiteFrame, tab.dialogParent());
        }
    }

    private static WorkflowGuardTab tab(
            boolean burpIssueViewerAvailable,
            java.util.function.Consumer<Throwable> errorReporter
    ) {
        return tab(burpIssueViewerAvailable, errorReporter, null);
    }

    private static WorkflowGuardTab tab(
            boolean burpIssueViewerAvailable,
            java.util.function.Consumer<Throwable> errorReporter,
            Component dialogParent
    ) {
        var service = new WorkflowService(
                new InMemoryWorkflowRepository(),
                new StepClassifier(),
                new MutationEngine()
        );
        service.createWorkflow("Test workflow");
        var coordinator = new ExecutionCoordinator(
                ignored -> HttpExchangeEvidence.noResponse(),
                ignored -> true
        );
        return new WorkflowGuardTab(
                service,
                coordinator,
                AuditIssuePublisher.disabled(),
                burpIssueViewerAvailable,
                errorReporter,
                dialogParent
        );
    }

    private static JCheckBox issuePublicationCheckBox(Container container) {
        for (Component component : container.getComponents()) {
            if (component instanceof JCheckBox checkBox
                    && checkBox.getText().startsWith("Publish invariant failures")) {
                return checkBox;
            }
            if (component instanceof Container child) {
                try {
                    return issuePublicationCheckBox(child);
                } catch (IllegalStateException ignored) {
                    // Continue searching sibling containers.
                }
            }
        }
        throw new IllegalStateException("Issue publication checkbox not found");
    }
}

package dev.workflowguard.adapters.burp;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.core.BurpSuiteEdition;
import dev.workflowguard.adapters.persistence.InMemoryWorkflowDocumentStore;
import dev.workflowguard.adapters.persistence.JsonWorkflowRepository;
import dev.workflowguard.adapters.persistence.MontoyaWorkflowDocumentStore;
import dev.workflowguard.adapters.ui.WorkflowGuardTab;
import dev.workflowguard.application.ExecutionCoordinator;
import dev.workflowguard.application.WorkflowService;
import dev.workflowguard.core.MutationEngine;
import dev.workflowguard.core.StepClassifier;
import dev.workflowguard.ports.WorkflowDocumentStore;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Objects;

public final class WorkflowGuardBootstrap {
    private final MontoyaApi api;
    private WorkflowGuardRuntime runtime;

    public WorkflowGuardBootstrap(MontoyaApi api) {
        this.api = Objects.requireNonNull(api, "api");
    }

    public void initialize() {
        api.extension().setName("WorkflowGuard");

        WorkflowDocumentStore documentStore = createDocumentStore();
        var repository = new JsonWorkflowRepository(
                documentStore,
                message -> api.logging().logToError(message)
        );
        var workflowService = new WorkflowService(
                repository,
                new StepClassifier(),
                new MutationEngine()
        );
        if (workflowService.activeWorkflow().isEmpty()) {
            workflowService.createWorkflow("Captured workflow");
        }

        var executionCoordinator = new ExecutionCoordinator(
                new MontoyaRequestSender(api.http()),
                url -> api.scope().isInScope(url)
        );
        var tab = new WorkflowGuardTab(
                workflowService,
                executionCoordinator,
                new BurpAuditIssuePublisher(api),
                api.burpSuite().version().edition() == BurpSuiteEdition.PROFESSIONAL,
                this::logUnexpectedError,
                api.userInterface().swingUtils().suiteFrame()
        );
        runtime = new WorkflowGuardRuntime(tab);

        api.userInterface().registerSuiteTab("WorkflowGuard", tab);
        api.userInterface().registerContextMenuItemsProvider(
                new WorkflowContextMenuProvider(
                        workflowService,
                        new BurpRequestMapper(),
                        api.logging()
                )
        );
        api.extension().registerUnloadingHandler(runtime::close);
        api.logging().logToOutput(
                "WorkflowGuard initialized. Capture requests, generate a case, and review safety controls before execution."
        );
    }

    private void logUnexpectedError(Throwable throwable) {
        StringWriter stackTrace = new StringWriter();
        throwable.printStackTrace(new PrintWriter(stackTrace));
        api.logging().logToError(stackTrace.toString());
    }

    private WorkflowDocumentStore createDocumentStore() {
        try {
            return new MontoyaWorkflowDocumentStore(
                    api.persistence().extensionData(),
                    message -> api.logging().logToError(message)
            );
        } catch (RuntimeException exception) {
            api.logging().logToError(
                    "Project persistence is unavailable; using runtime memory only: "
                            + exception.getMessage()
            );
            return new InMemoryWorkflowDocumentStore();
        }
    }
}

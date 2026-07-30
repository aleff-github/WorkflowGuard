package dev.workflowguard.adapters.burp;

import burp.api.montoya.logging.Logging;
import burp.api.montoya.ui.contextmenu.ContextMenuEvent;
import burp.api.montoya.ui.contextmenu.ContextMenuItemsProvider;
import dev.workflowguard.application.CapturedRequest;
import dev.workflowguard.application.WorkflowService;

import javax.swing.JMenu;
import javax.swing.JMenuItem;
import java.awt.Component;
import java.util.List;
import java.util.Objects;

final class WorkflowContextMenuProvider implements ContextMenuItemsProvider {
    private final WorkflowService workflowService;
    private final BurpRequestMapper requestMapper;
    private final Logging logging;

    WorkflowContextMenuProvider(
            WorkflowService workflowService,
            BurpRequestMapper requestMapper,
            Logging logging
    ) {
        this.workflowService = Objects.requireNonNull(workflowService, "workflowService");
        this.requestMapper = Objects.requireNonNull(requestMapper, "requestMapper");
        this.logging = Objects.requireNonNull(logging, "logging");
    }

    @Override
    public List<Component> provideMenuItems(ContextMenuEvent event) {
        var exchanges = event.selectedRequestResponses();
        if (exchanges.isEmpty()) {
            exchanges = event.messageEditorRequestResponse()
                    .map(editorExchange -> List.of(editorExchange.requestResponse()))
                    .orElseGet(List::of);
        }

        List<CapturedRequest> capturedRequests = exchanges.stream()
                .map(requestMapper::map)
                .toList();

        JMenu workflowGuardMenu = new JMenu("WorkflowGuard");
        JMenuItem addItem = new JMenuItem("Add to active workflow");
        addItem.setEnabled(!capturedRequests.isEmpty());
        addItem.addActionListener(ignored -> capture(capturedRequests));
        workflowGuardMenu.add(addItem);
        return List.of(workflowGuardMenu);
    }

    private void capture(List<CapturedRequest> requests) {
        try {
            var workflow = workflowService.capture(requests);
            logging.logToOutput(
                    "Captured " + requests.size() + " request(s) in workflow '" + workflow.name() + "'."
            );
        } catch (RuntimeException exception) {
            logging.logToError("Unable to capture selected requests: " + exception.getMessage());
        }
    }
}

package dev.workflowguard.ports;

import dev.workflowguard.domain.WorkflowStep;

@FunctionalInterface
public interface RequestSender {
    HttpExchangeEvidence send(WorkflowStep step);
}

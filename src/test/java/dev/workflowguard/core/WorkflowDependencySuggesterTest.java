package dev.workflowguard.core;

import dev.workflowguard.TestFixtures;
import dev.workflowguard.domain.DependencyConfidence;
import dev.workflowguard.domain.DependencyType;
import dev.workflowguard.domain.ExtractionType;
import dev.workflowguard.domain.StepCategory;
import dev.workflowguard.domain.StepRole;
import dev.workflowguard.domain.VariableDefinition;
import dev.workflowguard.domain.Workflow;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkflowDependencySuggesterTest {
    private final WorkflowDependencySuggester suggester = new WorkflowDependencySuggester();

    @Test
    void infersVariableLifecycleCleanupAndObservedOrderDependencies() {
        var create = TestFixtures.step("Create", "POST", StepCategory.INVITE);
        var consume = TestFixtures.step("Consume", "POST", StepCategory.ONE_TIME_USE)
                .withRawRequest(
                        "POST /consume/${invitationId} HTTP/1.1\r\n"
                                + "Host: example.test\r\n\r\n"
                );
        var revoke = TestFixtures.step("Revoke", "POST", StepCategory.REVOKE);
        var unknown = TestFixtures.step("Notify", "POST", StepCategory.UNKNOWN);
        var probe = TestFixtures.step("Probe", "GET", StepCategory.READ)
                .withRole(StepRole.PROBE);
        var cleanup = TestFixtures.step("Cleanup", "DELETE", StepCategory.DELETE)
                .withRole(StepRole.CLEANUP);
        Workflow base = TestFixtures.workflow(
                create,
                consume,
                revoke,
                unknown,
                probe,
                cleanup
        );
        Workflow workflow = new Workflow(
                base.id(),
                base.name(),
                base.steps(),
                base.actors(),
                List.of(new VariableDefinition(
                        "invitationId",
                        create.id(),
                        ExtractionType.JSON_POINTER,
                        "/id",
                        0
                )),
                base.invariants(),
                base.createdAt()
        );

        var suggestions = suggester.suggest(workflow);

        assertTrue(suggestions.stream().anyMatch(dependency ->
                dependency.sourceStepId().equals(create.id())
                        && dependency.targetStepId().equals(consume.id())
                        && dependency.type() == DependencyType.VARIABLE_DATA
                        && dependency.confidence() == DependencyConfidence.HIGH));
        assertTrue(suggestions.stream().anyMatch(dependency ->
                dependency.sourceStepId().equals(consume.id())
                        && dependency.targetStepId().equals(revoke.id())
                        && dependency.type() == DependencyType.RESOURCE_LIFECYCLE));
        assertTrue(suggestions.stream().anyMatch(dependency ->
                dependency.sourceStepId().equals(revoke.id())
                        && dependency.targetStepId().equals(unknown.id())
                        && dependency.type() == DependencyType.OBSERVED_ORDER));
        assertTrue(suggestions.stream().anyMatch(dependency ->
                dependency.targetStepId().equals(cleanup.id())
                        && dependency.type() == DependencyType.RESOURCE_LIFECYCLE));
        assertFalse(suggestions.stream().anyMatch(dependency ->
                dependency.sourceStepId().equals(probe.id())
                        || dependency.targetStepId().equals(probe.id())));
        assertEquals(suggestions, suggester.suggest(workflow));
    }

    @Test
    void ignoresDisabledStepsAndVariablesThatAreNotConsumedLater() {
        var source = TestFixtures.step("Source", "POST", StepCategory.CREATE);
        var disabled = TestFixtures.step("Disabled", "POST", StepCategory.UPDATE)
                .withRawRequest(
                        "POST /disabled/${id} HTTP/1.1\r\nHost: example.test\r\n\r\n"
                )
                .withEnabled(false);
        Workflow base = TestFixtures.workflow(source, disabled);
        Workflow workflow = new Workflow(
                base.id(),
                base.name(),
                base.steps(),
                base.actors(),
                List.of(new VariableDefinition(
                        "id",
                        source.id(),
                        ExtractionType.JSON_POINTER,
                        "/id",
                        0
                )),
                base.invariants(),
                base.createdAt()
        );

        assertTrue(suggester.suggest(workflow).isEmpty());
    }
}

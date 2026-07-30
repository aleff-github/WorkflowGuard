package dev.workflowguard.application;

import dev.workflowguard.adapters.persistence.InMemoryWorkflowRepository;
import dev.workflowguard.core.MutationEngine;
import dev.workflowguard.core.StepClassifier;
import dev.workflowguard.domain.ExtractionType;
import dev.workflowguard.domain.ActorDefinition;
import dev.workflowguard.domain.InvariantRule;
import dev.workflowguard.domain.JsonInvariant;
import dev.workflowguard.domain.MutationType;
import dev.workflowguard.domain.StepCategory;
import dev.workflowguard.domain.StepRole;
import dev.workflowguard.domain.VariableDefinition;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkflowServiceTest {
    @Test
    void capturesRequestsIntoTheActiveWorkflowAndGeneratesCases() {
        var service = new WorkflowService(
                new InMemoryWorkflowRepository(),
                new StepClassifier(),
                new MutationEngine()
        );
        service.createWorkflow("Invite lifecycle");

        var workflow = service.capture(List.of(
                new CapturedRequest(
                        "POST",
                        "https://example.test/api/invitations",
                        "POST /api/invitations HTTP/1.1\r\nHost: example.test\r\n\r\n",
                        true
                ),
                new CapturedRequest(
                        "DELETE",
                        "https://example.test/api/invitations/183/revoke",
                        "DELETE /api/invitations/183/revoke HTTP/1.1\r\nHost: example.test\r\n\r\n",
                        true
                )
        ));

        assertEquals(2, workflow.steps().size());
        assertEquals(StepCategory.INVITE, workflow.steps().getFirst().category());
        assertEquals(StepCategory.REVOKE, workflow.steps().getLast().category());
        assertTrue(workflow.steps().getFirst().name().startsWith("S1"));
        assertEquals(
                2,
                service.generateMutations(EnumSet.of(MutationType.REPEAT_STEP), 20).size()
        );
    }

    @Test
    void editsWorkflowAndStepMetadata() {
        var service = new WorkflowService(
                new InMemoryWorkflowRepository(),
                new StepClassifier(),
                new MutationEngine()
        );
        service.createWorkflow("Original");
        var captured = service.capture(List.of(
                request("POST", "/one"),
                request("POST", "/two"),
                request("GET", "/probe")
        ));
        var first = captured.steps().getFirst();
        var probe = captured.steps().getLast();

        service.moveStep(probe.id(), -1);
        service.updateStep(probe.id(), StepCategory.READ, StepRole.PROBE, true);
        service.updateStep(first.id(), StepCategory.CREATE, StepRole.ACTION, false);
        service.addVariable(new VariableDefinition(
                "probeValue",
                probe.id(),
                ExtractionType.JSON_POINTER,
                "/value",
                0
        ));
        service.addInvariant(
                probe.id(),
                new JsonInvariant(
                        "Probe unchanged",
                        "/value",
                        InvariantRule.UNCHANGED,
                        null
                )
        );
        service.updateStepTemplate(
                captured.steps().get(1).id(),
                "POST /two HTTP/1.1\r\nHost: example.test\r\nX-Probe: ${probeValue}\r\n\r\n"
        );
        service.renameActiveWorkflow("Edited");

        var edited = service.activeWorkflow().orElseThrow();
        assertEquals("Edited", edited.name());
        assertEquals(probe.id(), edited.steps().get(1).id());
        assertEquals(StepRole.PROBE, edited.steps().get(1).role());
        assertEquals(false, edited.steps().getFirst().enabled());
        assertEquals(1, edited.variables().size());
        assertEquals(1, edited.invariants().size());
        assertTrue(edited.steps().getLast().rawRequest().contains("${probeValue}"));
        assertEquals(
                1,
                service.generateMutations(EnumSet.of(MutationType.REPEAT_STEP), 20).size()
        );

        service.removeStep(probe.id());
        var withoutProbe = service.activeWorkflow().orElseThrow();
        assertEquals(2, withoutProbe.steps().size());
        assertTrue(withoutProbe.variables().isEmpty());
        assertTrue(withoutProbe.invariants().isEmpty());
        assertEquals("Captured workflow", service.deleteActiveWorkflow().name());
    }

    @Test
    void managesActorsAssignsStepsAndFallsBackToDefaultOnRemoval() {
        var service = new WorkflowService(
                new InMemoryWorkflowRepository(),
                new StepClassifier(),
                new MutationEngine()
        );
        service.createWorkflow("Actors");
        var captured = service.capture(List.of(request("GET", "/profile")));
        var step = captured.steps().getFirst();

        service.addActor(
                "Alice",
                "session=alice",
                "Bearer alice-token",
                false
        );
        ActorDefinition alice = service.activeWorkflow().orElseThrow().actors().stream()
                .filter(actor -> actor.name().equals("Alice"))
                .findFirst()
                .orElseThrow();
        assertEquals("Bearer alice-token", alice.initialAuthorizationHeader());
        service.updateStep(
                step.id(),
                StepCategory.READ,
                StepRole.ACTION,
                alice.id(),
                true
        );

        assertEquals(
                alice.id(),
                service.activeWorkflow().orElseThrow().steps().getFirst().actorId()
        );

        service.removeActor(alice.id());
        var updated = service.activeWorkflow().orElseThrow();
        assertEquals(1, updated.actors().size());
        assertEquals(
                ActorDefinition.DEFAULT_ID,
                updated.steps().getFirst().actorId()
        );
    }

    @Test
    void updatesStaleValuesVolatilePathsAndResolvesImportIdCollisions() {
        var service = new WorkflowService(
                new InMemoryWorkflowRepository(),
                new StepClassifier(),
                new MutationEngine()
        );
        var original = service.createWorkflow("Portable");
        var captured = service.capture(List.of(request("GET", "/source")));
        var source = captured.steps().getFirst();
        service.addVariable(new VariableDefinition(
                "objectId",
                source.id(),
                ExtractionType.JSON_POINTER,
                "/id",
                0
        ));
        service.updateVariable(
                "objectId",
                new VariableDefinition(
                        "objectId",
                        source.id(),
                        ExtractionType.JSON_POINTER,
                        "/id",
                        0,
                        "retired-7"
                )
        );
        service.updateVolatileJsonPointers(Set.of("/requestId"));

        var configured = service.activeWorkflow().orElseThrow();
        assertEquals("retired-7", configured.variables().getFirst().staleValue());
        assertEquals(Set.of("/requestId"), configured.volatileJsonPointers());

        var imported = service.importWorkflow(configured);
        assertNotEquals(original.id(), imported.id());
        assertTrue(imported.name().endsWith("(imported)"));
        assertEquals(configured.steps(), imported.steps());
    }

    private CapturedRequest request(String method, String path) {
        return new CapturedRequest(
                method,
                "https://example.test" + path,
                method + " " + path + " HTTP/1.1\r\nHost: example.test\r\n\r\n",
                true
        );
    }
}

package dev.workflowguard.adapters.persistence;

import dev.workflowguard.TestFixtures;
import dev.workflowguard.domain.ActorDefinition;
import dev.workflowguard.domain.ExtractionType;
import dev.workflowguard.domain.InvariantRule;
import dev.workflowguard.domain.JsonInvariant;
import dev.workflowguard.domain.ProbeInvariant;
import dev.workflowguard.domain.StepCategory;
import dev.workflowguard.domain.StepRole;
import dev.workflowguard.domain.VariableDefinition;
import dev.workflowguard.domain.Workflow;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonWorkflowRepositoryTest {
    @Test
    void roundTripsCompleteWorkflowDocuments() {
        var store = new InMemoryWorkflowDocumentStore();
        var warnings = new ArrayList<String>();
        var repository = new JsonWorkflowRepository(store, warnings::add);
        var alice = ActorDefinition.create("Alice", "session=alice", false);
        var step = TestFixtures.step("P1", "GET", StepCategory.READ)
                .withRole(StepRole.PROBE)
                .withActorId(alice.id())
                .withEnabled(false);
        var workflow = new Workflow(
                TestFixtures.workflow(step).id(),
                "Persisted workflow",
                List.of(step),
                List.of(ActorDefinition.defaultActor(), alice),
                List.of(new VariableDefinition(
                        "memberId",
                        step.id(),
                        ExtractionType.JSON_POINTER,
                        "/members/0",
                        0,
                        "retired-member"
                )),
                List.of(ProbeInvariant.create(
                        step.id(),
                        new JsonInvariant(
                                "Members unchanged",
                                "/members",
                                InvariantRule.UNCHANGED,
                                null
                        )
                )),
                Set.of("/requestId", "/generatedAt"),
                TestFixtures.workflow(step).createdAt()
        );

        repository.save(workflow);

        var reloaded = new JsonWorkflowRepository(store, warnings::add);
        assertEquals(workflow, reloaded.findById(workflow.id()).orElseThrow());
        assertEquals(1, reloaded.findById(workflow.id()).orElseThrow().variables().size());
        assertEquals(1, reloaded.findById(workflow.id()).orElseThrow().invariants().size());
        assertEquals(2, reloaded.findById(workflow.id()).orElseThrow().actors().size());
        assertEquals(
                "retired-member",
                reloaded.findById(workflow.id()).orElseThrow()
                        .variables().getFirst().staleValue()
        );
        assertEquals(
                Set.of("/requestId", "/generatedAt"),
                reloaded.findById(workflow.id()).orElseThrow().volatileJsonPointers()
        );
        assertEquals(
                alice.id(),
                reloaded.findById(workflow.id()).orElseThrow().steps().getFirst().actorId()
        );
        assertTrue(warnings.isEmpty());

        reloaded.deleteById(workflow.id());
        assertTrue(new JsonWorkflowRepository(store, warnings::add).findAll().isEmpty());
    }

    @Test
    void ignoresCorruptedDocumentsWithoutCrashingTheExtension() {
        var store = new InMemoryWorkflowDocumentStore();
        store.write("{not-json");
        var warnings = new ArrayList<String>();

        var repository = new JsonWorkflowRepository(store, warnings::add);

        assertTrue(repository.findAll().isEmpty());
        assertEquals(1, warnings.size());
    }

    @Test
    void migratesSchemaOneDocumentsWithNewFieldsDefaulted() {
        var store = new InMemoryWorkflowDocumentStore();
        var warnings = new ArrayList<String>();
        var repository = new JsonWorkflowRepository(store, warnings::add);
        var workflow = TestFixtures.workflow(
                TestFixtures.step("Legacy", "GET", StepCategory.READ)
        );
        repository.save(workflow);
        store.write(store.read().orElseThrow().replace(
                "\"schemaVersion\":2",
                "\"schemaVersion\":1"
        ));

        var migrated = new JsonWorkflowRepository(store, warnings::add)
                .findById(workflow.id())
                .orElseThrow();

        assertEquals(workflow, migrated);
        assertTrue(migrated.volatileJsonPointers().isEmpty());
        assertTrue(warnings.isEmpty());
    }

    @Test
    void rejectsAnInvalidPersistedDocumentWithoutPartiallyLoadingIt() {
        var store = new InMemoryWorkflowDocumentStore();
        var warnings = new ArrayList<String>();
        var repository = new JsonWorkflowRepository(store, warnings::add);
        repository.save(TestFixtures.workflow(
                TestFixtures.step("First", "GET", StepCategory.READ)
        ));
        Workflow second = new Workflow(
                java.util.UUID.randomUUID(),
                "Second workflow",
                List.of(TestFixtures.step("Second", "GET", StepCategory.READ)),
                java.time.Instant.parse("2026-07-29T12:00:00Z")
        );
        repository.save(second);
        store.write(store.read().orElseThrow().replace(
                "GET /second HTTP/1.1",
                "DELETE /second HTTP/1.1"
        ));
        warnings.clear();

        var reloaded = new JsonWorkflowRepository(store, warnings::add);

        assertTrue(reloaded.findAll().isEmpty());
        assertEquals(1, warnings.size());
    }
}

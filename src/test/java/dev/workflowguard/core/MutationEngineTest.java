package dev.workflowguard.core;

import dev.workflowguard.TestFixtures;
import dev.workflowguard.domain.ActorDefinition;
import dev.workflowguard.domain.MutationType;
import dev.workflowguard.domain.StepCategory;
import dev.workflowguard.domain.StepRole;
import dev.workflowguard.domain.ExtractionType;
import dev.workflowguard.domain.VariableDefinition;
import dev.workflowguard.domain.Workflow;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MutationEngineTest {
    private final MutationEngine engine = new MutationEngine();

    @Test
    void generatesDeterministicExplainableCases() {
        var create = TestFixtures.step("S1", "POST", StepCategory.CREATE);
        var use = TestFixtures.step("S2", "POST", StepCategory.UPDATE);
        var revoke = TestFixtures.step("S3", "DELETE", StepCategory.REVOKE);
        var workflow = TestFixtures.workflow(create, use, revoke);

        var first = engine.generate(workflow, EnumSet.allOf(MutationType.class), 20);
        var second = engine.generate(workflow, EnumSet.allOf(MutationType.class), 20);

        assertEquals(9, first.size());
        assertEquals(first, second);
        assertEquals(1, first.stream().filter(
                item -> item.type() == MutationType.REPLAY_AFTER_REVOKE
        ).count());
        assertEquals(3, first.stream().filter(item -> item.type() == MutationType.SKIP_STEP).count());
        assertEquals(3, first.stream().filter(item -> item.type() == MutationType.REPEAT_STEP).count());
        assertEquals(2, first.stream().filter(item -> item.type() == MutationType.REPLAY_EARLIER_STEP).count());
    }

    @Test
    void repeatsTheSelectedStepImmediatelyAndHonorsTheCaseLimit() {
        var firstStep = TestFixtures.step("S1", "POST", StepCategory.CREATE);
        var secondStep = TestFixtures.step("S2", "DELETE", StepCategory.DELETE);
        var workflow = TestFixtures.workflow(firstStep, secondStep);

        var cases = engine.generate(workflow, EnumSet.of(MutationType.REPEAT_STEP), 1);

        assertEquals(1, cases.size());
        assertEquals(
                java.util.List.of(firstStep.id(), firstStep.id(), secondStep.id()),
                cases.getFirst().steps().stream().map(step -> step.id()).toList()
        );
    }

    @Test
    void excludesDisabledProbeAndCleanupStepsFromMutationCases() {
        var action = TestFixtures.step("S1", "POST", StepCategory.CREATE);
        var disabled = TestFixtures.step("S2", "POST", StepCategory.UPDATE)
                .withEnabled(false);
        var probe = TestFixtures.step("P1", "GET", StepCategory.READ)
                .withRole(StepRole.PROBE);
        var cleanup = TestFixtures.step("C1", "DELETE", StepCategory.DELETE)
                .withRole(StepRole.CLEANUP);

        var cases = engine.generate(
                TestFixtures.workflow(action, disabled, probe, cleanup),
                EnumSet.of(MutationType.REPEAT_STEP),
                20
        );

        assertEquals(1, cases.size());
        assertEquals(
                java.util.List.of(action.id(), action.id()),
                cases.getFirst().steps().stream().map(step -> step.id()).toList()
        );
    }

    @Test
    void generatesActorSwapsWithoutSharingOrChangingOtherStepAssignments() {
        var alice = ActorDefinition.create("Alice", "session=alice");
        var bob = ActorDefinition.create("Bob", "session=bob");
        var first = TestFixtures.step("S1", "POST", StepCategory.CREATE)
                .withActorId(alice.id());
        var second = TestFixtures.step("S2", "POST", StepCategory.UPDATE)
                .withActorId(alice.id());
        var base = TestFixtures.workflow(first, second);
        var workflow = new dev.workflowguard.domain.Workflow(
                base.id(),
                base.name(),
                base.steps(),
                List.of(ActorDefinition.defaultActor(), alice, bob),
                base.variables(),
                base.invariants(),
                base.createdAt()
        );

        var cases = engine.generate(
                workflow,
                EnumSet.of(MutationType.SWAP_ACTOR),
                20
        );

        assertEquals(4, cases.size());
        assertEquals(
                2,
                cases.stream()
                        .filter(item -> item.steps().stream()
                                .anyMatch(step -> step.actorId().equals(bob.id())))
                        .count()
        );
        assertEquals(
                2,
                cases.stream()
                        .filter(item -> item.steps().stream()
                                .anyMatch(step -> step.actorId().equals(
                                        ActorDefinition.DEFAULT_ID
                                )))
                        .count()
        );
    }

    @Test
    void insertsTheClosestOneTimeActionImmediatelyAfterRevokeOrDelete() {
        var create = TestFixtures.step("Create", "POST", StepCategory.CREATE);
        var consume = TestFixtures.step("Consume", "POST", StepCategory.ONE_TIME_USE);
        var revoke = TestFixtures.step("Revoke", "POST", StepCategory.REVOKE);
        var delete = TestFixtures.step("Delete", "DELETE", StepCategory.DELETE);

        var cases = engine.generate(
                TestFixtures.workflow(create, consume, revoke, delete),
                EnumSet.of(
                        MutationType.REPLAY_AFTER_REVOKE,
                        MutationType.REPLAY_AFTER_DELETE
                ),
                20
        );

        assertEquals(2, cases.size());
        assertEquals(
                List.of(
                        create.id(),
                        consume.id(),
                        revoke.id(),
                        consume.id(),
                        delete.id()
                ),
                cases.getFirst().steps().stream().map(step -> step.id()).toList()
        );
        assertEquals(
                List.of(
                        create.id(),
                        consume.id(),
                        revoke.id(),
                        delete.id(),
                        consume.id()
                ),
                cases.getLast().steps().stream().map(step -> step.id()).toList()
        );
    }

    @Test
    void createsSingleUseReplayOnlyForExplicitOneTimeSteps() {
        var create = TestFixtures.step("Create", "POST", StepCategory.CREATE);
        var consume = TestFixtures.step("Consume", "POST", StepCategory.ONE_TIME_USE);

        var cases = engine.generate(
                TestFixtures.workflow(create, consume),
                EnumSet.of(MutationType.REPLAY_ONE_TIME_USE),
                20
        );

        assertEquals(1, cases.size());
        assertEquals(
                List.of(create.id(), consume.id(), consume.id()),
                cases.getFirst().steps().stream().map(step -> step.id()).toList()
        );
    }

    @Test
    void createsAStaleMutationOnlyForConfiguredAndConsumedVariables() {
        var source = TestFixtures.step("Source", "GET", StepCategory.READ);
        var consumer = TestFixtures.step("Consumer", "POST", StepCategory.UPDATE)
                .withRawRequest(
                        "POST /objects/${objectId} HTTP/1.1\r\n"
                                + "Host: example.test\r\n\r\n"
                );
        Workflow base = TestFixtures.workflow(source, consumer);
        Workflow workflow = base.withVariables(List.of(
                new VariableDefinition(
                        "objectId",
                        source.id(),
                        ExtractionType.JSON_POINTER,
                        "/id",
                        0,
                        "retired-object-17"
                ),
                new VariableDefinition(
                        "unused",
                        source.id(),
                        ExtractionType.JSON_POINTER,
                        "/unused",
                        0,
                        "old"
                )
        ));

        var cases = engine.generate(
                workflow,
                EnumSet.of(MutationType.STALE_VARIABLE),
                20
        );

        assertEquals(1, cases.size());
        assertEquals(
                Map.of("objectId", "retired-object-17"),
                cases.getFirst().variableOverrides()
        );
        assertTrue(cases.getFirst().description().contains("${objectId}"));
    }
}

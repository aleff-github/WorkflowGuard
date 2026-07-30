package dev.workflowguard.application;

import dev.workflowguard.TestFixtures;
import dev.workflowguard.domain.ActorDefinition;
import dev.workflowguard.domain.ExtractionType;
import dev.workflowguard.domain.StepCategory;
import dev.workflowguard.domain.VariableDefinition;
import dev.workflowguard.domain.Workflow;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkflowArchiveServiceTest {
    private final WorkflowArchiveService archives = new WorkflowArchiveService();

    @Test
    void redactsPortableExportsAndRoundTripsTrustedExports() {
        var actor = ActorDefinition.create(
                "Alice",
                "session=alice-secret",
                "Bearer actor-authorization-secret",
                false
        );
        var step = TestFixtures.step("Use", "POST", StepCategory.UPDATE)
                .withActorId(actor.id())
                .withRawRequest(
                        "POST /use?token=query-secret HTTP/1.1\r\n"
                                + "Host: example.test\r\n"
                                + "Authorization: Bearer header-secret\r\n"
                                + "Cookie: session=cookie-secret\r\n\r\n"
                                + "{\"password\":\"body-secret\"}"
                );
        Workflow base = TestFixtures.workflow(step);
        Workflow workflow = new Workflow(
                base.id(),
                base.name(),
                List.of(step),
                List.of(ActorDefinition.defaultActor(), actor),
                List.of(new VariableDefinition(
                        "accessToken",
                        step.id(),
                        ExtractionType.JSON_POINTER,
                        "/token",
                        0,
                        "stale-secret"
                )),
                List.of(),
                base.volatileJsonPointers(),
                base.createdAt()
        );

        String portable = archives.exportJson(workflow, false);
        assertTrue(portable.contains("<redacted>"));
        assertFalse(portable.contains("header-secret"));
        assertFalse(portable.contains("actor-authorization-secret"));
        assertFalse(portable.contains("cookie-secret"));
        assertFalse(portable.contains("body-secret"));
        assertFalse(portable.contains("stale-secret"));

        String trusted = archives.exportJson(workflow, true);
        assertEquals(workflow, archives.importJson(trusted));
    }

    @Test
    void importsVersionOneArchivesCreatedBeforeAuthorizationSeedsWereAdded() {
        Workflow workflow = TestFixtures.workflow(
                TestFixtures.step("Read", "GET", StepCategory.READ)
        );
        String trusted = archives.exportJson(workflow, true);
        String legacy = trusted.replace(
                "\"initialAuthorizationHeader\" : \"\",\r\n",
                ""
        ).replace(
                "\"initialAuthorizationHeader\" : \"\",\n",
                ""
        );

        Workflow imported = archives.importJson(legacy);

        assertTrue(imported.actors().stream().allMatch(
                actor -> actor.initialAuthorizationHeader().isBlank()
        ));
    }

    @Test
    void rejectsUnsupportedArchiveSchemas() {
        String archive = archives.exportJson(
                TestFixtures.workflow(TestFixtures.step("Read", "GET", StepCategory.READ)),
                false
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> archives.importJson(archive.replace(
                        "\"schemaVersion\" : 1",
                        "\"schemaVersion\" : 99"
                ))
        );
    }

    @Test
    void rejectsBrokenInternalReferences() {
        var step = TestFixtures.step("Broken", "GET", StepCategory.READ)
                .withActorId(UUID.randomUUID());
        Workflow broken = TestFixtures.workflow(step);

        assertThrows(IllegalArgumentException.class, () -> archives.exportJson(broken, true));
    }

    @Test
    void rejectsTrailingDocumentsAndRequestMetadataDivergence() {
        Workflow workflow = TestFixtures.workflow(
                TestFixtures.step("Read", "GET", StepCategory.READ)
        );
        String trusted = archives.exportJson(workflow, true);

        assertThrows(
                IllegalArgumentException.class,
                () -> archives.importJson(trusted + "{}")
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> archives.importJson(trusted.replace(
                        "GET /read HTTP/1.1",
                        "DELETE /read HTTP/1.1"
                ))
        );
    }

    @Test
    void rejectsArchivesAboveTheHardSizeLimit() {
        String oversized = " ".repeat(10 * 1024 * 1024 + 1);

        assertThrows(IllegalArgumentException.class, () -> archives.importJson(oversized));
    }

}

package dev.workflowguard.core;

import dev.workflowguard.domain.StepCategory;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StepClassifierTest {
    private final StepClassifier classifier = new StepClassifier();

    @Test
    void recognizesOneTimeOperationsBeforeBroaderInvitationClassification() {
        assertEquals(
                StepCategory.ONE_TIME_USE,
                classifier.classify(
                        "POST",
                        "https://example.test/api/invitations/123/accept"
                )
        );
        assertEquals(
                StepCategory.ONE_TIME_USE,
                classifier.classify("POST", "https://example.test/coupons/123/redeem")
        );
        assertEquals(
                StepCategory.INVITE,
                classifier.classify("POST", "https://example.test/api/invitations")
        );
    }
}

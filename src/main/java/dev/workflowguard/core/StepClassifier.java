package dev.workflowguard.core;

import dev.workflowguard.domain.StepCategory;

import java.util.Locale;

public final class StepClassifier {
    public StepCategory classify(String method, String url) {
        String normalizedMethod = method == null ? "" : method.toUpperCase(Locale.ROOT);
        String normalizedUrl = url == null ? "" : url.toLowerCase(Locale.ROOT);

        if (containsAny(normalizedUrl, "refund", "payment", "checkout", "charge")) {
            return StepCategory.PAYMENT;
        }
        if (containsAny(normalizedUrl, "revoke", "disable", "deactivate")) {
            return StepCategory.REVOKE;
        }
        if (containsAny(
                normalizedUrl,
                "/accept",
                "/redeem",
                "/consume",
                "/claim",
                "one-time",
                "onetime",
                "/otp"
        )) {
            return StepCategory.ONE_TIME_USE;
        }
        if (containsAny(normalizedUrl, "invite", "invitation")) {
            return StepCategory.INVITE;
        }

        return switch (normalizedMethod) {
            case "GET", "HEAD", "OPTIONS" -> StepCategory.READ;
            case "POST" -> StepCategory.CREATE;
            case "PUT", "PATCH" -> StepCategory.UPDATE;
            case "DELETE" -> StepCategory.DELETE;
            default -> StepCategory.UNKNOWN;
        };
    }

    private boolean containsAny(String value, String... candidates) {
        for (String candidate : candidates) {
            if (value.contains(candidate)) {
                return true;
            }
        }
        return false;
    }
}

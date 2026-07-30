package dev.workflowguard.adapters.burp;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.http.HttpService;
import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.http.message.responses.HttpResponse;
import burp.api.montoya.scanner.audit.issues.AuditIssue;
import burp.api.montoya.scanner.audit.issues.AuditIssueConfidence;
import burp.api.montoya.scanner.audit.issues.AuditIssueSeverity;
import dev.workflowguard.domain.ExecutionPhase;
import dev.workflowguard.domain.ExecutionRun;
import dev.workflowguard.domain.InvariantCheckResult;
import dev.workflowguard.domain.StepExecutionResult;
import dev.workflowguard.domain.StepExecutionStatus;
import dev.workflowguard.ports.AuditIssuePublisher;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class BurpAuditIssuePublisher implements AuditIssuePublisher {
    private static final String BACKGROUND =
            "WorkflowGuard replayed a deterministic application workflow and compared "
                    + "explicit user-defined state invariants.";
    private static final String REMEDIATION =
            "Review server-side authorization, lifecycle, idempotency, and token invalidation "
                    + "for the affected workflow. Reproduce the sequence before assigning impact.";

    private final MontoyaApi api;

    public BurpAuditIssuePublisher(MontoyaApi api) {
        this.api = Objects.requireNonNull(api, "api");
    }

    @Override
    public int publish(ExecutionRun run) {
        Objects.requireNonNull(run, "run");
        List<HttpRequestResponse> evidence = evidence(run);
        if (evidence.isEmpty()) {
            return 0;
        }
        String baseUrl = run.stepResults().stream()
                .filter(result -> result.phase() == ExecutionPhase.MUTATION)
                .map(StepExecutionResult::url)
                .findFirst()
                .orElse(run.stepResults().getFirst().url());
        int published = 0;
        for (InvariantCheckResult result : run.invariantResults()) {
            if (result.comparisonPhase() != ExecutionPhase.AFTER_PROBE || result.passed()) {
                continue;
            }
            api.siteMap().add(AuditIssue.auditIssue(
                    "WorkflowGuard: workflow invariant violation",
                    detail(run, result),
                    REMEDIATION,
                    baseUrl,
                    AuditIssueSeverity.INFORMATION,
                    AuditIssueConfidence.FIRM,
                    BACKGROUND,
                    REMEDIATION,
                    AuditIssueSeverity.INFORMATION,
                    evidence
            ));
            published++;
        }
        return published;
    }

    private List<HttpRequestResponse> evidence(ExecutionRun run) {
        List<HttpRequestResponse> exchanges = new ArrayList<>();
        for (StepExecutionResult result : run.stepResults()) {
            if (result.status() != StepExecutionStatus.RESPONSE_RECEIVED
                    || result.rawRequest().isBlank()
                    || result.rawResponse().isBlank()) {
                continue;
            }
            try {
                URI uri = URI.create(result.url());
                boolean secure = "https".equalsIgnoreCase(uri.getScheme());
                int port = uri.getPort() >= 0 ? uri.getPort() : secure ? 443 : 80;
                HttpService service = HttpService.httpService(uri.getHost(), port, secure);
                exchanges.add(HttpRequestResponse.httpRequestResponse(
                        HttpRequest.httpRequest(service, result.rawRequest()),
                        HttpResponse.httpResponse(result.rawResponse())
                ));
            } catch (RuntimeException exception) {
                api.logging().logToError(
                        "WorkflowGuard could not attach one HTTP exchange to an audit issue: "
                                + exception.getMessage()
                );
            }
        }
        return List.copyOf(exchanges);
    }

    private String detail(ExecutionRun run, InvariantCheckResult result) {
        StringBuilder detail = new StringBuilder();
        detail.append("<p>The deterministic mutation case <b>")
                .append(escape(run.mutationCaseName()))
                .append("</b> did not satisfy the explicit invariant <b>")
                .append(escape(result.invariantName()))
                .append("</b>.</p>");
        if (result.errorMessage().isPresent()) {
            detail.append("<p>Evaluation error: ")
                    .append(escape(result.errorMessage().orElseThrow()))
                    .append("</p>");
        }
        if (!result.violations().isEmpty()) {
            detail.append("<ul>");
            result.violations().forEach(violation -> detail
                    .append("<li>")
                    .append(escape(violation.jsonPointer()))
                    .append(": ")
                    .append(escape(violation.message()))
                    .append("</li>"));
            detail.append("</ul>");
        }
        detail.append(
                "<p>This informational issue deliberately does not assign vulnerability "
                        + "impact from response differences alone.</p>"
        );
        return detail.toString();
    }

    private String escape(String value) {
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}

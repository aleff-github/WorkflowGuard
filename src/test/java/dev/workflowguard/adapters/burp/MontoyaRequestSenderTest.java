package dev.workflowguard.adapters.burp;

import burp.api.montoya.http.Http;
import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.http.message.responses.HttpResponse;
import dev.workflowguard.domain.StepCategory;
import dev.workflowguard.domain.WorkflowStep;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicReference;

import static dev.workflowguard.TestFixtures.step;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MontoyaRequestSenderTest {
    @Test
    void sendsTheCreatedRequestAndCapturesRawResponseEvidence() {
        WorkflowStep step = step("Create", "POST", StepCategory.CREATE);
        HttpRequest request = proxy(HttpRequest.class, (method, arguments) -> defaultValue(
                method.getReturnType()
        ));
        HttpResponse response = proxy(HttpResponse.class, (method, arguments) -> switch (
                method.getName()
        ) {
            case "statusCode" -> (short) 201;
            case "toString" -> "HTTP/1.1 201 Created\r\n\r\n{\"id\":\"123\"}";
            case "bodyToString" -> "{\"id\":\"123\"}";
            default -> defaultValue(method.getReturnType());
        });
        HttpRequestResponse exchange = exchange(true, response);
        AtomicReference<HttpRequest> sent = new AtomicReference<>();
        Http http = proxy(Http.class, (method, arguments) -> {
            if (method.getName().equals("sendRequest") && arguments.length == 1) {
                sent.set((HttpRequest) arguments[0]);
                return exchange;
            }
            return defaultValue(method.getReturnType());
        });
        var sender = new MontoyaRequestSender(http, (url, rawRequest) -> {
            assertEquals(step.url(), url);
            assertEquals(step.rawRequest(), rawRequest);
            return request;
        });

        var evidence = sender.send(step);

        assertSame(request, sent.get());
        assertTrue(evidence.hasResponse());
        assertEquals(201, evidence.statusCode().orElseThrow());
        assertTrue(evidence.rawResponse().contains("{\"id\":\"123\"}"));
    }

    @Test
    void representsARequestWithoutAResponseExplicitly() {
        WorkflowStep step = step("Probe", "GET", StepCategory.READ);
        Http http = proxy(Http.class, (method, arguments) -> {
            if (method.getName().equals("sendRequest") && arguments.length == 1) {
                return exchange(false, null);
            }
            return defaultValue(method.getReturnType());
        });
        HttpRequest request = proxy(HttpRequest.class, (method, arguments) -> defaultValue(
                method.getReturnType()
        ));

        var evidence = new MontoyaRequestSender(http, (url, rawRequest) -> request).send(step);

        assertFalse(evidence.hasResponse());
        assertTrue(evidence.statusCode().isEmpty());
        assertEquals("", evidence.rawResponse());
    }

    @Test
    void rejectsResponsesTooLargeForLongTermEvidenceStorage() {
        WorkflowStep step = step("Probe", "GET", StepCategory.READ);
        String oversized = "x".repeat(
                MontoyaRequestSender.MAXIMUM_RESPONSE_CHARACTERS + 1
        );
        HttpResponse response = proxy(HttpResponse.class, (method, arguments) -> switch (
                method.getName()
        ) {
            case "statusCode" -> (short) 200;
            case "bodyToString", "toString" -> oversized;
            default -> defaultValue(method.getReturnType());
        });
        Http http = proxy(Http.class, (method, arguments) -> {
            if (method.getName().equals("sendRequest") && arguments.length == 1) {
                return exchange(true, response);
            }
            return defaultValue(method.getReturnType());
        });
        HttpRequest request = proxy(HttpRequest.class, (method, arguments) -> defaultValue(
                method.getReturnType()
        ));

        assertThrows(
                IllegalStateException.class,
                () -> new MontoyaRequestSender(
                        http,
                        (url, rawRequest) -> request
                ).send(step)
        );
    }

    private HttpRequestResponse exchange(boolean hasResponse, HttpResponse response) {
        return proxy(HttpRequestResponse.class, (method, arguments) -> switch (method.getName()) {
            case "hasResponse" -> hasResponse;
            case "response" -> response;
            default -> defaultValue(method.getReturnType());
        });
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) {
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == byte.class) {
            return (byte) 0;
        }
        if (type == short.class) {
            return (short) 0;
        }
        if (type == int.class) {
            return 0;
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == float.class) {
            return 0f;
        }
        if (type == double.class) {
            return 0d;
        }
        if (type == char.class) {
            return '\0';
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, Invocation invocation) {
        return (T) Proxy.newProxyInstance(
                type.getClassLoader(),
                new Class<?>[]{type},
                (proxy, method, arguments) -> invocation.invoke(
                        method,
                        arguments == null ? new Object[0] : arguments
                )
        );
    }

    @FunctionalInterface
    private interface Invocation {
        Object invoke(java.lang.reflect.Method method, Object[] arguments);
    }
}

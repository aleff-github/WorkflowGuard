package dev.workflowguard.adapters.burp;

import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.logging.Logging;
import burp.api.montoya.ui.contextmenu.ContextMenuEvent;
import dev.workflowguard.adapters.persistence.InMemoryWorkflowRepository;
import dev.workflowguard.application.WorkflowService;
import dev.workflowguard.core.MutationEngine;
import dev.workflowguard.core.StepClassifier;
import dev.workflowguard.domain.StepCategory;
import org.junit.jupiter.api.Test;

import javax.swing.JMenu;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkflowContextMenuProviderTest {
    @Test
    void capturesASelectedMontoyaRequestIntoTheActiveWorkflow() {
        var service = new WorkflowService(
                new InMemoryWorkflowRepository(),
                new StepClassifier(),
                new MutationEngine()
        );
        service.createWorkflow("Context capture");
        AtomicReference<String> outputLog = new AtomicReference<>();

        HttpRequest request = proxy(HttpRequest.class, (method, arguments) -> switch (method) {
            case "method" -> "POST";
            case "url" -> "http://127.0.0.1:18080/api/invitations";
            case "isInScope" -> true;
            case "toString" -> """
                    POST /api/invitations HTTP/1.1\r
                    Host: 127.0.0.1:18080\r
                    Content-Type: application/json\r
                    \r
                    {"recipient":"member-b"}""";
            default -> defaultValue(arguments.returnType());
        });
        HttpRequestResponse exchange = proxy(
                HttpRequestResponse.class,
                (method, arguments) -> method.equals("request")
                        ? request
                        : defaultValue(arguments.returnType())
        );
        ContextMenuEvent event = proxy(
                ContextMenuEvent.class,
                (method, arguments) -> switch (method) {
                    case "selectedRequestResponses" -> List.of(exchange);
                    case "messageEditorRequestResponse" -> Optional.empty();
                    default -> defaultValue(arguments.returnType());
                }
        );
        Logging logging = proxy(
                Logging.class,
                (method, arguments) -> {
                    if (method.equals("logToOutput")) {
                        outputLog.set((String) arguments.values()[0]);
                    }
                    return defaultValue(arguments.returnType());
                }
        );
        var provider = new WorkflowContextMenuProvider(
                service,
                new BurpRequestMapper(),
                logging
        );

        var components = provider.provideMenuItems(event);
        JMenu menu = (JMenu) components.getFirst();
        menu.getItem(0).doClick();

        var workflow = service.activeWorkflow().orElseThrow();
        assertEquals(1, workflow.steps().size());
        assertEquals(StepCategory.INVITE, workflow.steps().getFirst().category());
        assertEquals("POST", workflow.steps().getFirst().method());
        assertTrue(outputLog.get().contains("Captured 1 request"));
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, Invocation invocation) {
        return (T) Proxy.newProxyInstance(
                type.getClassLoader(),
                new Class<?>[]{type},
                (instance, method, arguments) -> invocation.invoke(
                        method.getName(),
                        new InvocationArguments(
                                method.getReturnType(),
                                arguments == null ? new Object[0] : arguments
                        )
                )
        );
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) {
            if (type == Optional.class) {
                return Optional.empty();
            }
            if (type == List.class) {
                return List.of();
            }
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == char.class) {
            return '\0';
        }
        return 0;
    }

    @FunctionalInterface
    private interface Invocation {
        Object invoke(String method, InvocationArguments arguments);
    }

    private record InvocationArguments(Class<?> returnType, Object[] values) {
    }
}

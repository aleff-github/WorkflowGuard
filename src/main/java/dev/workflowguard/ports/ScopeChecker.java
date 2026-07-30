package dev.workflowguard.ports;

@FunctionalInterface
public interface ScopeChecker {
    boolean isInScope(String url);
}

package dev.workflowguard.ports;

import java.time.Duration;

@FunctionalInterface
public interface DelayStrategy {
    void pause(Duration duration) throws InterruptedException;

    static DelayStrategy threadSleep() {
        return duration -> Thread.sleep(duration.toMillis());
    }
}

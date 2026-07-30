package dev.workflowguard.fixture;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;

public final class FixtureMain {
    private FixtureMain() {
    }

    public static void main(String[] args) throws Exception {
        Map<String, String> options = parseOptions(args);
        String host = options.getOrDefault("host", "127.0.0.1");
        int port = Integer.parseInt(options.getOrDefault("port", "18080"));
        FixtureMode mode = FixtureMode.parse(options.getOrDefault("mode", "vulnerable"));

        InetAddress address = InetAddress.getByName(host);
        WorkflowGuardFixture fixture = WorkflowGuardFixture.start(
                new InetSocketAddress(address, port),
                mode
        );
        Runtime.getRuntime().addShutdownHook(new Thread(fixture::close, "fixture-shutdown"));

        System.out.printf(
                "WorkflowGuard fixture listening at http://%s:%d in %s mode.%n",
                host,
                fixture.port(),
                fixture.mode().name().toLowerCase()
        );
        System.out.println("Reset endpoint: POST /test/reset");
        new CountDownLatch(1).await();
    }

    private static Map<String, String> parseOptions(String[] args) {
        Map<String, String> options = new HashMap<>();
        for (String argument : args) {
            if (!argument.startsWith("--") || !argument.contains("=")) {
                throw new IllegalArgumentException(
                        "Expected options in --name=value form, received: " + argument
                );
            }
            int separator = argument.indexOf('=');
            options.put(argument.substring(2, separator), argument.substring(separator + 1));
        }
        return options;
    }
}

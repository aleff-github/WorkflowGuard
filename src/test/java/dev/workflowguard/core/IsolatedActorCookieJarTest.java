package dev.workflowguard.core;

import dev.workflowguard.domain.ActorDefinition;
import dev.workflowguard.ports.HttpExchangeEvidence;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IsolatedActorCookieJarTest {
    private static final String URL = "https://example.test/account";
    private static final String REQUEST = "GET /account HTTP/1.1\r\n"
            + "Host: example.test\r\n"
            + "Authorization: Bearer captured-shared\r\n"
            + "Cookie: captured=shared\r\n\r\n";

    @Test
    void keepsActorCookiesIsolatedAndAppliesSetCookieUpdates() {
        var alice = new IsolatedActorCookieJar(new ActorDefinition(
                UUID.randomUUID(),
                "Alice",
                "session=alice",
                false
        ));
        var bob = new IsolatedActorCookieJar(new ActorDefinition(
                UUID.randomUUID(),
                "Bob",
                "session=bob",
                false
        ));

        assertTrue(alice.apply(REQUEST, URL).contains("Cookie: session=alice"));
        assertTrue(bob.apply(REQUEST, URL).contains("Cookie: session=bob"));

        alice.capture(URL, new HttpExchangeEvidence(
                true,
                Optional.of(200),
                "",
                "",
                Map.of("set-cookie", List.of("session=alice-rotated; Path=/"))
        ));

        assertTrue(alice.apply(REQUEST, URL).contains("session=alice-rotated"));
        assertTrue(bob.apply(REQUEST, URL).contains("session=bob"));
        assertFalse(bob.apply(REQUEST, URL).contains("alice"));
    }

    @Test
    void seedsOnlyActorsExplicitlyConfiguredToUseCapturedCookies() {
        var seeded = new IsolatedActorCookieJar(new ActorDefinition(
                UUID.randomUUID(),
                "Seeded",
                "",
                true
        ));
        var empty = new IsolatedActorCookieJar(new ActorDefinition(
                UUID.randomUUID(),
                "Empty",
                "",
                false
        ));

        assertTrue(seeded.apply(REQUEST, URL).contains("captured=shared"));
        assertFalse(empty.apply(REQUEST, URL).contains("Cookie:"));
    }

    @Test
    void neverSharesCookiesAcrossOrigins() {
        var jar = new IsolatedActorCookieJar(new ActorDefinition(
                UUID.randomUUID(),
                "Actor",
                "",
                true
        ));

        jar.apply(REQUEST, URL);

        String otherOrigin = jar.apply(
                "GET / HTTP/1.1\r\nHost: other.test\r\n\r\n",
                "https://other.test/"
        );
        assertFalse(otherOrigin.contains("captured=shared"));
    }

    @Test
    void acceptsAnInitialCookieValueWithOrWithoutTheHeaderPrefix() {
        var jar = new IsolatedActorCookieJar(new ActorDefinition(
                UUID.randomUUID(),
                "Actor",
                "Cookie: session=actor; tenant=one",
                false
        ));

        String request = jar.apply(REQUEST, URL);

        assertTrue(request.contains("Cookie: session=actor; tenant=one"));
        assertFalse(request.contains("Cookie: Cookie:"));
    }

    @Test
    void replacesCapturedAuthorizationPerActorWithoutSharingTokens() {
        var alice = new IsolatedActorCookieJar(new ActorDefinition(
                UUID.randomUUID(),
                "Alice",
                "",
                "Bearer alice-token",
                false
        ));
        var bob = new IsolatedActorCookieJar(new ActorDefinition(
                UUID.randomUUID(),
                "Bob",
                "",
                "Authorization: Bearer bob-token",
                false
        ));

        String aliceRequest = alice.apply(REQUEST, URL);
        String bobRequest = bob.apply(REQUEST, URL);

        assertTrue(aliceRequest.contains("Authorization: Bearer alice-token"));
        assertFalse(aliceRequest.contains("captured-shared"));
        assertFalse(aliceRequest.contains("bob-token"));
        assertTrue(bobRequest.contains("Authorization: Bearer bob-token"));
        assertFalse(bobRequest.contains("captured-shared"));
        assertFalse(bobRequest.contains("alice-token"));
        assertFalse(bobRequest.contains("Authorization: Authorization:"));
    }

    @Test
    void removesCapturedAuthorizationForCustomActorsWithoutAnExplicitSeed() {
        var custom = new IsolatedActorCookieJar(new ActorDefinition(
                UUID.randomUUID(),
                "Custom",
                "",
                "",
                true
        ));

        String request = custom.apply(REQUEST, URL);

        assertFalse(request.contains("Authorization:"));
        assertTrue(request.contains("Cookie: captured=shared"));
    }

    @Test
    void preservesCapturedAuthorizationOnlyForTheDefaultActor() {
        var defaultActor = new IsolatedActorCookieJar(ActorDefinition.defaultActor());

        String request = defaultActor.apply(REQUEST, URL);

        assertTrue(request.contains("Authorization: Bearer captured-shared"));
    }

    @Test
    void bindsExplicitCredentialsToOneOrigin() {
        var actor = ActorDefinition.create(
                "Bound",
                "session=origin-secret",
                "Bearer origin-token",
                false
        );
        var jar = new IsolatedActorCookieJar(actor, "https://example.test/account");

        String allowed = jar.apply(REQUEST, URL);
        String other = jar.apply(
                "GET / HTTP/1.1\r\n"
                        + "Host: other.test\r\n"
                        + "Authorization: Bearer captured-shared\r\n"
                        + "Cookie: captured=shared\r\n\r\n",
                "https://other.test/"
        );

        assertTrue(allowed.contains("session=origin-secret"));
        assertTrue(allowed.contains("Bearer origin-token"));
        assertFalse(other.contains("origin-secret"));
        assertFalse(other.contains("origin-token"));
        assertFalse(other.contains("captured=shared"));
    }
}

# Contributing

## Development flow

1. Create a focused branch.
2. Add or update tests with each behavior change.
3. Run `./gradlew clean test jar`.
4. Load the resulting JAR in Burp for changes involving Montoya or Swing.
5. Keep state-changing execution behind explicit policy checks and confirmation.

## Architecture rules

- Domain objects must not depend on Burp classes or Swing.
- Application services depend on ports, not adapters.
- Montoya objects are converted at the adapter boundary.
- Mutation generation must be deterministic for the same workflow and settings.
- Safety checks must fail closed when scope or request classification is unknown.
- Background work must be cancellable and released when Burp unloads the extension.
- Do not introduce network clients when the Montoya API can issue the request.

## Code style

- Java 21 language features are allowed.
- Prefer immutable records and defensive copies.
- Validate public constructor inputs.
- Avoid static mutable state.
- Keep UI event handlers thin; business logic belongs in application or core services.

## Tests

Core tests must run without Burp Suite. Montoya integration should be covered
by adapter tests using fakes where practical, followed by a manual smoke test in
Burp Community when a release candidate is prepared. Repeat the smoke test in
Professional when a change depends on a Professional-only capability or a
Professional test environment is available.

Use the loopback-only `fixture` project for stateful integration tests. Every intentionally vulnerable behavior must have a corresponding secure-mode test proving that the same request sequence does not change state.

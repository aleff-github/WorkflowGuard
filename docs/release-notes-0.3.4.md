# WorkflowGuard 0.3.4

WorkflowGuard `0.3.4` is a release-integrity and security-maintenance release
prepared for BApp Store submission. It intentionally avoids changing the core
workflow-execution semantics validated in the `0.3.3` campaign.

## Security and dependency maintenance

- Update Jackson Databind and aligned Jackson runtime components to `2.22.3`.
- Keep Gradle dependency locking enabled.
- Use Gradle Wrapper `9.7.1` with a pinned distribution checksum.
- Update `actions/setup-java` to the pinned `6.0.1` commit.

## Release integrity

- Verify the distributable JAR in CI.
- Reject a build if Montoya API classes are accidentally bundled.
- Reject duplicate JAR entries.
- Generate and verify a SHA-256 file from the exact built artifact.
- Add a tag-driven release workflow that requires the Git tag and Gradle
  project version to match.
- Publish the same verified JAR/checksum pair produced by the release workflow.
- Document the historical `0.3.3` validation-build versus release-asset
  checksum discrepancy.

## BApp Store preparation

- Refresh the BApp Store readiness assessment against PortSwigger's
  2026-09-22 acceptance criteria.
- Update the submission text and explicitly distinguish WorkflowGuard from
  Sequence Comparer, AuthMatrix, Autorize, Auth Analyzer, and API Workflow
  Manager.
- Keep Community compatibility declared; no DAST or Burp AI integration is
  claimed.

## Validation

- The release candidate is built and tested by GitHub Actions on JDK 21.
- The CI pipeline verifies the final JAR layout and checksum generation.
- Direct runtime/UI evidence remains the Burp Suite Community Edition
  `2026.7.1` campaign performed for `0.3.3`; `0.3.4` does not intentionally
  alter workflow execution behavior.

The canonical binary checksum for `0.3.4` is generated automatically by the
release workflow and published alongside the JAR.

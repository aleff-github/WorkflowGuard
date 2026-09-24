# Release integrity and process

This document defines the release process for WorkflowGuard. The goal is to
ensure that the binary published on GitHub, its checksum, the source tag, and
the release notes all refer to the same build.

## Required process

1. Update the Gradle project version and create matching
   `docs/release-notes-X.Y.Z.md`.
2. Merge all intended changes to `main` and require a green Build workflow.
3. A version change in `build.gradle.kts` on `main` triggers
   `.github/workflows/tag-release.yml`, which creates the annotated
   `vX.Y.Z` tag at that exact release commit.
4. Do not create the GitHub release manually.
5. The tag workflow explicitly dispatches `.github/workflows/release.yml`
   after creating the tag. Manually pushed release tags can also trigger the
   release workflow directly. The release workflow builds, tests, verifies,
   checksums, and publishes the release artifacts.
6. Download the published JAR and `.sha256` file and verify them independently
   before submitting or updating the BApp Store entry.

The tag workflow only creates a release tag when the project version actually
changes. The release workflow fails if the tag version does not match the Gradle
project version, if more than one WorkflowGuard JAR is produced, if Montoya API
classes are bundled, if duplicate JAR entries are present, or if release notes
are missing.

## Historical correction for 0.3.3

The validation record created on 2026-07-31 refers to a build with:

- size: 2,852,241 bytes;
- SHA-256:
  `8FB9CD29EC79CE5B9489A55F7BCAC321393E7B4426CE63BD84A0B9E5D29955DD`.

The JAR currently attached to the GitHub `v0.3.3` release was uploaded on
2026-08-25 and GitHub reports:

- size: 2,854,635 bytes;
- SHA-256:
  `84422c6751fd05574b5e3cdfd20bc64c2a5fd7a6d74be28b0d2d8ccd0df8d043`.

These are different binary builds. The July validation checksum must therefore
not be used to verify the currently attached release asset. The release page
body should be corrected manually to remove the stale size and checksum.

Starting with `0.3.4`, the automated tag/release workflows are the canonical
release path and the release workflow is the canonical producer of both the JAR
and its checksum.

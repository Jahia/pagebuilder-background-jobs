# Changelog

All notable changes to the Page Builder Background Jobs module are documented in this file.

## [Unreleased]

### Changed
- Resolved SonarQube findings in the GraphQL layer: added a private constructor to the static `GqlJahiaAdminQueryBackgroundJobsExtension`, replaced a generic `RuntimeException` with `IllegalStateException`, removed an unused parameter, and refactored the 10-argument `GqlPageBuilderBackgroundJob` constructor into a no-arg constructor populated by the existing `from(JobDetail)` factory (no change to the GraphQL fields).

### Accessibility
- The background-jobs dialog status-group toggle now exposes `aria-expanded` (state was previously conveyed only by the ▶/▼ glyph).

### Added
- Wired ESLint (`@jahia/eslint-config`) + a `lint` script (frontend lints clean) and a Jest test suite (`@testing-library/react`, 8 tests) for the background-jobs dialog, plus JS coverage (lcov) for SonarQube.
- First Java unit tests (JUnit 4 + Mockito, 23 tests) for the background-jobs service (publication-job filtering, configuration parsing) and the `GqlPageBuilderBackgroundJob` mapping. JaCoCo coverage wiring.

### Notes
- The GraphQL extension still registers its fields directly on the root `Query` type rather than under a single module namespace container; consolidating them is recommended in a future change (it would alter the GraphQL field paths consumed by the frontend).

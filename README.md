# Page Builder Background Jobs

Jahia module that adds a **Background Jobs** dialog to the JContent Page Builder header, without redirecting to Page Composer.

## Features

- Adds a Page Builder header action (`Publication jobs` by default, `Background jobs` when `showAllJobs=true`).
- Loads background jobs through GraphQL.
- Supports an OSGi configuration to switch between `Publication jobs` mode and full `Background jobs` mode.
- Displays:
  - status grouping (fold/unfold),
  - pagination,
  - auto-refresh.
  - group filters (toggle switches) only when full mode is enabled.

## Project Structure

- Frontend (React):
  - `src/javascript/BackgroundJobsAction.jsx`
  - `src/javascript/BackgroundJobsDialog.jsx`
  - `src/javascript/backgroundJobs.gql-queries.js`
- Backend (GraphQL extensions):
  - `src/main/java/org/jahia/support/modules/pagebuilder/backgroundjobs/graphql/GqlJahiaAdminQueryBackgroundJobsExtension.java`
  - `src/main/java/org/jahia/support/modules/pagebuilder/backgroundjobs/graphql/GqlJahiaAdminQueryBackgroundJobsCompatibilityExtension.java`
  - `src/main/java/org/jahia/support/modules/pagebuilder/backgroundjobs/graphql/GqlPageBuilderBackgroundJob.java`
  - `src/main/java/org/jahia/support/modules/pagebuilder/backgroundjobs/graphql/DXGraphQLPageBuilderBackgroundJobsProvider.java`

## GraphQL API

Root query:

```graphql
query($siteKey: String, $path: String) {
  canAccessPageBuilderBackgroundJobs(siteKey: $siteKey, path: $path)
  pageBuilderBackgroundJobsShowAll
  pageBuilderBackgroundJobs(siteKey: $siteKey, path: $path) {
    name
    group
    jobDescription
    duration
    jobState
    jobStatus
    siteKey
    userKey
    createdRaw
    createdTimestamp
  }
}
```

Compatibility query is also exposed on `admin.jahia` with the same field names.

## OSGi Configuration

PID:

- `org.jahia.support.modules.pagebuilder.backgroundjobs`

Property:

- `showAllJobs=false`

Behavior:

- `false` (default): only jobs whose group contains `PublicationJob` are returned. The button label and dialog title become `Publication jobs`, and the group filter UI is hidden.
- `true`: all jobs are returned. The UI stays in `Background jobs` mode, with group filters enabled.

Default config is shipped in:

- `src/main/resources/META-INF/configurations/org.jahia.support.modules.pagebuilder.backgroundjobs.cfg`

## Security and Permission

### Custom permission

This module imports a custom Jahia permission:

- `canAccessJobsInformation`

Defined in:

- `src/main/import/permissions.xml`

### Access rules

- `guest` is denied.
- `root` is allowed.
- users with `admin` are allowed.
- users with `canAccessJobsInformation` are allowed.

The UI button is hidden when `canAccessPageBuilderBackgroundJobs(...)` returns `false`.

### Required post-deployment step

After deploying the module, assign `canAccessJobsInformation` to the relevant Jahia role(s), then assign that role to the target users/groups.

## UI Behavior

- Default page size: **50 rows**.
- Toggle states are persisted during browser session (`sessionStorage`).
- `userKey` is displayed in short form (last segment after `/`).
- Long columns (User/Description) wrap to avoid overflow.

## Build

From this module directory:

```bash
mvn clean install
```

Deployable artifact:

- `target/pagebuilder-background-jobs-xxx.jar`

## Deployment

1. Upload the JAR in Jahia Module Manager.
2. Start/enable the module.
3. Refresh JContent Page Builder and verify the button visibility/behavior.

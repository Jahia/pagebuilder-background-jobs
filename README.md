# Page Builder Background Jobs

Jahia module that adds a **Background Jobs** dialog to the JContent Page Builder header, without redirecting to Page Composer.

## Features

- Adds a Page Builder header action (`Publication jobs` by default, `Background jobs` when `showAllJobs=true`).
- Loads background jobs through GraphQL.
- Supports an OSGi configuration to switch between `Publication jobs` mode and full `Background jobs` mode.
- Displays:
  - status grouping (fold/unfold),
  - pagination,
  - auto-refresh (off by default, polls every 5 seconds when enabled).
  - group filters (toggle switches) only when full mode is enabled.
- Ships two pre-configured roles for quick deployment (no manual role authoring required).

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
- Service layer:
  - `src/main/java/org/jahia/support/modules/pagebuilder/backgroundjobs/service/PageBuilderBackgroundJobsService.java`
- JCR import content:
  - `src/main/import/permissions.xml`
  - `src/main/import/roles.xml`
- Build configuration:
  - `webpack.config.js`
  - `pom.xml`
- Tests:
  - `src/test/java/org/jahia/support/modules/pagebuilder/backgroundjobs/graphql/GqlJahiaAdminQueryBackgroundJobsExtensionTest.java` (JUnit)
  - `tests/cypress/e2e/` (Cypress E2E)

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

### Query behavior

- **`pageBuilderBackgroundJobs`** — **THROWS** `SecurityException("Permission denied")` when the caller lacks permission. The GraphQL response includes a `null` data field and a GraphQL error. The dialog handles this gracefully by showing an error message.
- **`canAccessPageBuilderBackgroundJobs`** — Returns a **boolean**. Returns `false` when the caller lacks permission; does **NOT** throw an error. Used by the UI to show/hide the button.
- **`pageBuilderBackgroundJobsShowAll`** — Returns a **boolean**. Gated behind the same jobs permission (as of SEC-140 C3); returns `false` if the caller has no access. Returns the current `showAllJobs` configuration.

A legacy compatibility alias `pageBuilderBackgroundJobs`, `canAccessPageBuilderBackgroundJobs`, and `pageBuilderBackgroundJobsShowAll` are also exposed on the `admin.jahia` GraphQL endpoint for backward compatibility.

### Field semantics

- **`jobState`** — **Derived**, not directly from Quartz. Read-only computed field: returns `"STARTED"` when `jobStatus` is `"EXECUTING"`, otherwise `"FINISHED"`. Never a Quartz trigger state.
- **`duration`** — **Number or -1**. When a duration value is not found in the job data map, this field is set to `-1` (never `null`).
- **`createdRaw`** — **String or null**. Best-effort scavenge over many candidate JobDataMap keys (see `GqlPageBuilderBackgroundJob.DATE_KEYS`); may be `null` if no date can be found.
- **`createdTimestamp`** — **Long (milliseconds) or null**. Normalized timestamp parsed from the same date keys as `createdRaw`; may be `null` if no parseable date exists.
- **`userKey`** — **String or null**. When `null` or empty, the UI renders it as `"system"`.

## Upgrading

The module imports JCR content (roles, permissions) in `src/main/import/` exactly **once per module version**. If your instance is already running the current version, redeploying it will **not** re-import this content.

To force a reimport of the roles and permissions:

### Option 1: Bump the module version (recommended)

1. Edit `pom.xml` and increment the `<version>` (e.g., `1.0.1-SNAPSHOT` → `1.0.2-SNAPSHOT`).
2. Rebuild: `mvn clean install`.
3. Redeploy the new JAR.

### Option 2: Undeploy and redeploy

1. In Module Manager, stop and uninstall the current module.
2. Delete the module's data directory: `$JAHIA_HOME/karaf/data/bundleXX/` (where XX matches the bundle ID).
3. Redeploy the same JAR.

### Why redeployment alone is insufficient

Jahia's bundle framework caches the imported JCR content by version. To signal that the import should run again, the version number must change, or the bundle must be completely removed.

## OSGi Configuration

### Runtime configuration

PID: `org.jahia.support.modules.pagebuilder.backgroundjobs`

Property: `showAllJobs=false` (boolean)

**Behavior:**

- `false` (default): only jobs whose group contains `PublicationJob` are returned. The button label and dialog title become `Publication jobs`, and the group filter UI is hidden.
- `true`: all job **groups** are returned. The UI stays in `Background jobs` mode, with group filters enabled.

> `showAllJobs` selects which job *groups* are eligible. It never widens which *sites* a caller may see — site scoping is always derived from the caller's own permissions (see Security section above).

**Configuration file:**

Default config is shipped in:

- `src/main/resources/META-INF/configurations/org.jahia.support.modules.pagebuilder.backgroundjobs.cfg`

This file starts with the required comment `# default configuration - won't be overridden` to prevent Jahia's module extender from overwriting user changes on every start.

### Changing configuration on a running instance

1. SSH into the Jahia server.
2. Edit (or create) the file: `$JAHIA_HOME/karaf/etc/org.jahia.support.modules.pagebuilder.backgroundjobs.cfg`
3. Add or modify: `showAllJobs=true`
4. Save the file.
5. Changes apply **immediately** — no Jahia restart required. The service uses `@Modified` to pick up configuration changes live.

## Security and Permission

### Custom permission

This module defines a custom Jahia permission:

- `canAccessJobsInformation` — Grants access to query and view background jobs.

Defined in:

- `src/main/import/permissions.xml`

### Pre-configured roles

Two roles are shipped with the module in `src/main/import/roles.xml`. Operators can grant one of these to users/groups instead of manually authoring a role.

#### `background-jobs-viewer` (site-scoped)

- **Scope**: Single site (`j:nodeTypes="jnt:virtualsite"`)
- **Group**: `site-role`
- **Permission**: `canAccessJobsInformation`
- **Access level**: User sees jobs only from the site(s) this role is granted on.
- **Required for Page Builder**: `j:privilegedAccess="true"` — without this flag, Jahia's AclListener does not add the grantee to the site's privileged group, and the JContent Page Builder UI is unreachable.

#### `background-jobs-administrator` (server-wide)

- **Scope**: Server-wide (`j:nodeTypes="rep:root"`)
- **Group**: `server-role`
- **Permission**: `canAccessJobsInformation`
- **Access level**: User sees jobs from every site, plus instance-level jobs (those with no `siteKey`).
- **Required for Page Builder**: `j:privilegedAccess="true"` — same requirement as the site role.

### Access control rules

Authorization decides both **whether** the caller may query jobs and **which sites' jobs** they receive:

| Caller | Scenario | Result |
|---|---|---|
| `guest` | any | denied |
| `root` | any | unrestricted — all sites + instance-level jobs |
| User with `canAccessJobsInformation` on `/` | any | unrestricted — all sites + instance-level jobs |
| User with `canAccessJobsInformation` on site(s) | no `siteKey`/`path` argument | scoped to their authorized sites only |
| User with `canAccessJobsInformation` on site(s) | with `siteKey`/`path` pointing to an authorized site | scoped to that one site only |
| User with `canAccessJobsInformation` on site(s) | with `siteKey`/`path` pointing to a **different** site | denied (SEC-140 / GHSA-4vfj-8pfg-4xrp) |
| User with no permission anywhere | any | denied |

**Important**: Scoping is **always** derived from the caller's actual permissions on nodes in the JCR, **never** from the caller-supplied `siteKey` or `path` arguments. When a specific site is requested, the caller must hold the permission on that site; there is no fallback to a global-API-scope or any-site override. This prevents scope-confusion attacks (SEC-140).

Jobs carrying no `siteKey` are instance-level and are visible only to unrestricted callers (root or grantees on `/`).

The UI button is hidden when `canAccessPageBuilderBackgroundJobs(...)` returns `false`.

### Deployment: granting roles to users

1. Deploy the module via the Module Manager.
2. Refresh Jahia administration or log out and back in to load the new roles.
3. Navigate to **Administration > Roles & Permissions** (or per-site roles).
4. Assign either `background-jobs-viewer` (site-scoped) or `background-jobs-administrator` (server-wide) to the target users or groups.
5. Users can now see the **Background Jobs** button in Page Builder.

## Requirements / Compatibility

### Jahia version

- **Minimum Jahia**: 8.2.2.0 (via parent `jahia-modules` in `pom.xml`)
- **Minimum graphql-dxm-provider**: 3.2.0

### Module deployment

- **Module type**: `system`
- **Deploy on site**: `system`
- **Jahia depends**: `default`

These settings mean the module **auto-deploys to every site** on the instance; no per-site activation is needed.

### Prerequisites

- **Node.js and Yarn**: Auto-provisioned by `frontend-maven-plugin` during the Maven build. Do not manually install node or yarn; the build will fetch and use the exact versions specified in `pom.xml` (Node v20.9.0, Yarn v1.22.21).

## UI Behavior

- **Button visibility**: The button only appears in **Page Builder view mode**. If it's not visible, check that you're in edit mode, not view mode.
- **Auto-refresh**: Off by default. When enabled, polls the GraphQL endpoint every **5 seconds**.
- **Default page size**: **50 rows**.
- **Rows per page options**: [50, 25, 10].
- **Toggle states**: Status groups and filters persist during the browser session via `sessionStorage`.
- **User display**: `userKey` is shown in short form (last segment after `/`). When empty or null, rendered as `"system"`.
- **Long columns**: User and Description columns wrap to avoid overflow.

## Build

From this module directory:

```bash
mvn clean install
```

Deployable artifact:

- `target/pagebuilder-background-jobs-1.0.1-SNAPSHOT.jar` (or `${version}.jar` depending on the pom.xml version)

## Deployment

1. Upload the JAR in Jahia Module Manager.
2. Start/enable the module.
3. (For new roles to appear) Refresh the Jahia administration interface or log out and back in.
4. Grant the `background-jobs-viewer` or `background-jobs-administrator` role to target users/groups (see Security section above).
5. Users can now see the **Background Jobs** (or **Publication jobs**) button in Page Builder.

## Testing

The module includes both **JUnit** unit tests and **Cypress** E2E tests:

- **JUnit**: `src/test/java/org/jahia/support/modules/pagebuilder/backgroundjobs/graphql/GqlJahiaAdminQueryBackgroundJobsExtensionTest.java`  
  Run with: `mvn test`

- **Cypress E2E**: `tests/cypress/e2e/`  
  Full documentation: see [`tests/README.md`](tests/README.md)

## Troubleshooting

### The "Background Jobs" or "Publication jobs" button does not appear

The module's failure mode is silent — the button simply doesn't show. Check the following:

1. **Are you in Page Builder?**  
   The action registers into the Page Builder header only. `BackgroundJobsActionComponent` renders
   nothing unless jContent's current view mode is `pageBuilder`, so the button is absent from the
   Content, Media and other jContent views by design.

2. **Does your user have the required role?**  
   Check that your user (or a group you belong to) has been assigned either `background-jobs-viewer` or `background-jobs-administrator`. These roles must be granted per site (viewer) or globally (administrator).  
   Visit: **Administration > Roles & Permissions** (or per-site admin).

3. **Were the roles imported?**  
   The roles are imported once per module version. If you deployed the module but the roles don't appear in Jahia administration, the import may have already run.  
   **Solution**: Bump the module version in `pom.xml`, rebuild, and redeploy (see Upgrading section above).

4. **Is graphql-dxm-provider installed?**  
   This module requires `graphql-dxm-provider >= 3.2.0`.  
   Check: **Module Manager** → look for `graphql-dxm-provider` and verify version >= 3.2.0.  
   If missing or outdated: deploy the correct version or update your Jahia instance.

5. **Check browser console for GraphQL errors**  
   Open your browser's developer tools (F12) → **Console** tab. Look for GraphQL errors like:
   - `"Unknown field pageBuilderBackgroundJobs"` — graphql-dxm-provider is too old or missing.
   - `"Permission denied"` — the user lacks the permission, or the role was not correctly imported.

### Jobs list is empty or incomplete

- **showAllJobs=false (default)**: Only **Publication** jobs are shown (those with `PublicationJob` in the group name). Other background jobs are filtered out.
- **showAllJobs=true**: All background jobs should appear. Check the module logs: `$JAHIA_HOME/tomcat/logs/` for warnings about scheduler access.

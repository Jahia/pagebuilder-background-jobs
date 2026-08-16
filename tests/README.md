# Testing Guide

This directory contains Cypress E2E tests and supporting infrastructure for the Page Builder Background Jobs module.

## Quick Start

### Local development

```bash
cd tests

# Set up environment
source set-env.sh

# Build the test image and start containers
./ci.startup.sh

# In another terminal, run tests in debug mode
./env.run.sh
source set-env.sh
yarn e2e:debug
```

### CI pipeline

```bash
cd tests
./ci.build.sh      # Build module and test image
./ci.startup.sh    # Start Jahia + Cypress containers
# Tests run automatically, exit code 0 = all pass
```

## Prerequisites

### Local development

1. **Jahia EE License**  
   Create `tests/.env` (note: `.gitignored`) with:
   ```
   JAHIA_LICENSE=<your-ee-license-key>
   ```

2. **GitHub Container Registry (GHCR) authentication**  
   The `jahia-ee-dev` image lives on GHCR. Authenticate:
   ```bash
   echo $GITHUB_TOKEN | docker login ghcr.io -u <username> --password-stdin
   ```

3. **Nexus credentials** (for pulling dependencies)  
   Add to `tests/.env`:
   ```
   NEXUS_USERNAME=<your-nexus-username>
   NEXUS_PASSWORD=<your-nexus-password>
   ```
   These are referenced by `docker-compose.yml` and passed to the test container.

### Docker

- Docker and Docker Compose must be installed and running.

## Environment Setup

Copy the example to create your local `.env`:

```bash
cp tests/.env.example tests/.env
```

Then edit `tests/.env` to add your license and credentials:

```
JAHIA_LICENSE=your-ee-license-here
NEXUS_USERNAME=your-nexus-username
NEXUS_PASSWORD=your-nexus-password
JAHIA_IMAGE=ghcr.io/jahia/jahia-ee-dev:8-SNAPSHOT
```

**Note**: `tests/.env` is git-ignored for security. Never commit credentials to the repository.

## CI Pipeline Workflow

### 1. Build (`ci.build.sh`)

```bash
cd tests
./ci.build.sh
```

**What it does:**
- Runs `mvn clean install` from the project root (if `../target/` exists, copies the JAR).
- Fetches and runs `@jahia/cypress ci.build` to build the Cypress test Docker image.
- Creates `docker-compose` image with tests and dependencies baked in.

**Must re-run after:**
- Any change under `tests/` (test code, fixtures, config)
- Any change to the Java backend that affects the module JAR

### 2. Startup (`ci.startup.sh`)

```bash
./ci.startup.sh
```

**What it does:**
- Sources `set-env.sh` to load environment variables from `.env` or `.env.example`.
- Runs `@jahia/cypress ci.startup` to:
  - Start a Jahia EE container with the built module.
  - Wait for Jahia to be healthy (web console available).
  - Deploy the module and apply provisioning manifests.
  - Start a Cypress container to run all tests.
- Tests run automatically; exit code 0 = all pass, non-zero = failures.

### 3. Post-run cleanup (`ci.postrun.sh`)

```bash
./ci.postrun.sh
```

Shuts down and removes containers. Typically called by CI/CD on job completion.

## Local Development Workflow

### Without auto-run (`notests` flag)

To keep Jahia running but skip tests:

```bash
cd tests
./ci.startup.sh notests
```

This leaves the Jahia container running. You can then:

```bash
source set-env.sh
yarn e2e:debug
```

### Test runner modes

**Interactive / Debug mode** (opens Cypress UI):

```bash
source set-env.sh
yarn e2e:debug
```

Opens `http://localhost:3000` (Cypress UI). Select specs to run, see live browser preview, and inspect failures interactively.

**Headless / CI mode** (runs all specs once):

```bash
source set-env.sh
yarn e2e:ci
```

Runs all specs, generates JSON reports in `results/reports/`, exit code reflects pass/fail.

## Test Specifications

### `01-backgroundJobs-api.cy.ts`

**GraphQL API contract test**

Validates the three root GraphQL fields and their behavior under different authorization levels:

- ✓ `pageBuilderBackgroundJobs` returns an array (root can access).
- ✓ `canAccessPageBuilderBackgroundJobs` returns boolean (access check without errors).
- ✓ `pageBuilderBackgroundJobsShowAll` returns configuration flag.
- ✓ Guest user is denied (error thrown).
- ✓ Authenticated user with no permission is denied (error in `pageBuilderBackgroundJobs`, boolean `false` in `canAccessPageBuilderBackgroundJobs`).
- ✓ Config probe (`pageBuilderBackgroundJobsShowAll`) is gated behind permission (SEC-140 C3 regression test).

**Covers**: API contract, authorization asymmetry, access denial modes.

### `02-backgroundJobs-siteScoping.cy.ts`

**Scope-confusion regression suite (SEC-140 / GHSA-4vfj-8pfg-4xrp)**

Ensures a principal with permission on **one site** cannot view jobs from another site:

- ✓ Create two test sites (pbjsitea, pbjsiteb).
- ✓ Grant `background-jobs-viewer` role to a test user on site A only.
- ✓ **Bypass 1**: Query with `siteKey=pbjsiteb` (other site's key) → must be denied.
- ✓ **Bypass 2**: Query with no `siteKey` → must see only site A's jobs, not site B's.
- ✓ Root and unrestricted users see all sites.

**Covers**: Authorization boundary enforcement, scope derivation from caller permissions (not arguments), cross-site isolation.

## Reports

### Generating HTML reports

After tests run, reports are collected in `results/reports/`:

```bash
source set-env.sh
yarn report:merge    # Merge multiple cypress*.json into report.json
yarn report:html     # Generate HTML from report.json
```

Open `results/reports/index.html` in a browser to view the full report with screenshots, timings, and error details.

## Running Java Unit Tests

The module includes a JUnit suite for backend logic:

```bash
# From project root
mvn test
```

Tests run against:

- `src/test/java/org/jahia/support/modules/pagebuilder/backgroundjobs/graphql/GqlJahiaAdminQueryBackgroundJobsExtensionTest.java`

Covers:
- Authorization checks (`JobsAccess` logic).
- Site-scoped vs. unrestricted access modes.
- SEC-140 scope-derivation safeguards.

## Configuration Files

### `docker-compose.yml`

Defines two services:
- **jahia** — Jahia EE container (port 8080).
- **cypress** — Cypress test runner container.

Environment variables (loaded from `.env`):
- `JAHIA_LICENSE` — EE license key.
- `NEXUS_USERNAME`, `NEXUS_PASSWORD` — Maven repo credentials.
- `JAHIA_IMAGE` — Jahia Docker image tag.
- `TESTS_IMAGE` — Cypress test image tag.
- `MANIFEST` — Provisioning manifest file (defaults to `provisioning-manifest-snapshot.yml`).

### `provisioning-manifest-*.yml`

Jahia provisioning files that define module deployment and initial site setup for tests.

- `provisioning-manifest-snapshot.yml` — Used by default, targets snapshot builds.
- `provisioning-manifest-build.yml` — Alternative for release builds.

### `package.json`

Defines test scripts and dependencies:

- `@jahia/cypress` — Test orchestration (ci.build, ci.startup, env.run).
- `cypress` — E2E test runner.
- `@apollo/client` — GraphQL client for API tests.
- `mochawesome`, `mochawesome-merge` — Test report generators.

Scripts:
- `e2e:ci` — Headless test run.
- `e2e:debug` — Interactive Cypress UI.
- `report:merge` — Combine test reports.
- `report:html` — Generate HTML report.

## Debugging Test Failures

### Check Jahia logs

While containers are running:

```bash
docker logs jahia 2>&1 | tail -100
```

Look for module deployment errors, GraphQL schema issues, or permission denials.

### Check Cypress output

```bash
docker logs cypress 2>&1 | tail -100
```

Or open the Cypress UI (`yarn e2e:debug`) and inspect the failed step in detail (screenshot, network tab, console).

### Verify module is deployed

```bash
curl -s http://localhost:8080/modules/graphql \
  -H "Authorization: Basic $(echo -n 'root:root1234' | base64)" | jq .
```

Look for `pageBuilderBackgroundJobs` in the schema. If missing, the module isn't deployed or graphql-dxm-provider is too old.

### Check roles were imported

```bash
# Log in to Jahia admin console
# Go to Administration > Roles & Permissions
# Search for "background-jobs-"
```

If roles aren't there, see the Upgrading section in the main README (bump module version and redeploy).

## CI Integration

### GitHub Actions example

```yaml
- name: Run tests
  run: |
    cd tests
    ./ci.build.sh
    ./ci.startup.sh
    # Exit code reflects test result
```

The `ci.startup.sh` script returns:
- `0` — All tests passed.
- Non-zero — At least one test failed.

## Troubleshooting

### "Cannot connect to Jahia" or timeouts

- Jahia container didn't start or isn't healthy.
- Check: `docker logs jahia` and wait for "Jahia is now running" message.
- Verify port 8080 isn't already in use: `lsof -i :8080`.

### "Unknown field pageBuilderBackgroundJobs" in GraphQL schema

- graphql-dxm-provider is too old or missing.
- Check: `docker exec jahia curl -s http://localhost:8080/modules/graphql` and grep for the field.

### Tests run but report failures

- Check `results/reports/index.html` for screenshots and error messages.
- Scroll through `docker logs cypress` for assertion failures or GraphQL errors.
- Use `yarn e2e:debug` to re-run failed tests interactively.

### Module doesn't auto-deploy

- Check provisioning manifest (`MANIFEST` env variable).
- Verify the module JAR was built (check `../target/`).
- Check `docker logs jahia` for deployment errors.

## More Information

- **Main README**: [`../README.md`](../README.md) — Module overview, API docs, deployment guide.
- **Cypress docs**: https://docs.cypress.io/
- **Jahia Cypress helpers**: https://github.com/Jahia/jahia-cypress-reporters

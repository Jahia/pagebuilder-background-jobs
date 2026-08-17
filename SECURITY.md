# Security Policy

## Supported Versions

| Version | Status | Notes |
|---------|--------|-------|
| 1.0.1-SNAPSHOT | Fixed, unreleased | Carries the complete SEC-140 remediation; build from source |
| 1.0.0 | Affected | Contains the scope-confusion vulnerability (SEC-140) — the only published release |

## Known Security Issues

### SEC-140 / GHSA-4vfj-8pfg-4xrp — cross-site background-job metadata disclosure

**Severity**: Medium — CVSS 3.1 4.3 (`AV:N/AC:L/PR:L/UI:N/S:U/C:L/I:N/A:N`), CWE-863 / CWE-200.

**Status**: **fixed on `main`** — all four bypasses listed below are closed, and building from `main`
produces a fixed module. **No fixed release exists yet**: `1.0.1` is still an unreleased SNAPSHOT, so
`1.0.0`, the only published artifact, remains affected. Upgrade means building from source until `1.0.1`
ships.

Each fix carries regression tests: unit tests for the scoping and path-guard logic, plus an end-to-end
suite that grants a role on one site and asserts the other site's jobs are never returned, under every
way of framing the request.

**Issue**: `pageBuilderBackgroundJobs` verified the caller's permission against a caller-supplied
`siteKey`/`path`, but returned jobs for the whole instance. A principal granted
`canAccessJobsInformation` on one site could therefore read every site's publication-job metadata,
including which user (`userKey`) triggered each publication. Confirmed live on 8.2.4.0-SNAPSHOT.

Three distinct routes were found and closed:

1. Requesting another site's key — the answer was argument-independent.
2. Omitting `siteKey` entirely — the first remediation filtered on that argument, so leaving it out
   skipped the filter and returned the instance-wide list again.
3. Pairing a `siteKey` for one site with a `path` inside another — the permission was checked against
   `path` while the result was scoped to `siteKey`.
4. A `..` traversal path such as `/sites/siteB/../siteA`. JCR collapses `..` before resolving, so a
   string-parsed site key (`siteB`) and the node the permission was actually evaluated on
   (`/sites/siteA`) disagreed — the same defect one layer down.

**Fix**: access is now resolved to one of *denied*, *unrestricted* (root, or a grant on the repository
root), or *scoped to the set of sites the caller actually holds the permission on*, and the returned
jobs are filtered against that set. Caller-supplied arguments never widen the result. When a specific
site is requested the caller must hold the permission on that site; the any-site and global-API-scope
fallbacks were removed.

**Mitigation for 1.0.0**: grant `canAccessJobsInformation` only to principals you are willing to let
see every site's job metadata, or remove the module until a fixed release is available.

**Note on real-world reachability in 1.0.0.** Separately from the authorization defect, 1.0.0 shipped no
API authorization scope for its own GraphQL fields, so Jahia's security filter refused them to every
non-`root` caller before the resolver ran. On a default Jahia that means the delegated principal the
advisory describes could not reach the vulnerable field at all, and `root` — who could — is already
entitled to see every site's jobs. The leak was therefore reachable in practice only where an operator
had granted a scope covering these fields, or via the legacy `admin.jahia` path. This lowers the
practical exposure of 1.0.0; it does not change the defect, which is fixed on `main` independently of the
scope now being shipped.

## Reporting a Vulnerability

Security information can be found in our [security.txt file](https://academy.jahia.com/.well-known/security.txt).

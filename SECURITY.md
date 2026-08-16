# Security Policy

## Supported Versions

| Version | Status | Notes |
|---------|--------|-------|
| 1.0.1-SNAPSHOT | In development | Carries the SEC-140 remediation; **not yet released** |
| 1.0.0 | Affected | Contains the scope-confusion vulnerability (SEC-140) — the only published release |

## Known Security Issues

### SEC-140 / GHSA-4vfj-8pfg-4xrp — cross-site background-job metadata disclosure

**Severity**: Medium — CVSS 3.1 4.3 (`AV:N/AC:L/PR:L/UI:N/S:U/C:L/I:N/A:N`), CWE-863 / CWE-200.

**Status**: **not yet fixed on `main`.** `main` currently carries only the first, partial remediation —
it still contains bypasses 2, 3 and 4 below. The complete fix is on the
`fix/scope-jobs-to-authorized-sites` branch, pending review and merge. No fixed release exists, so every
published artifact (1.0.0) is affected, and building from `main` today does not produce a fixed module.

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

## Reporting a Vulnerability

Security information can be found in our [security.txt file](https://academy.jahia.com/.well-known/security.txt).

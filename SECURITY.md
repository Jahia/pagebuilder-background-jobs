# Security Policy

## Supported Versions

| Version | Status |
|---------|--------|
| 1.0.1 | Supported |
| 1.0.0 | End of life — upgrade to 1.0.1 |

Only the latest release receives fixes. Release artifacts are published on the
[releases page](https://github.com/Jahia/pagebuilder-background-jobs/releases); availability in the Jahia
store and in Nexus is handled separately and may follow later, so if you consume the module from either of
those, check the version you actually have deployed.

Note when upgrading: this module ships JCR import content (`src/main/import/roles.xml`), and Jahia imports
that content only once per module version. The roles it provides therefore arrive only on a version
change, not on a redeploy of the same version.

## Reporting a Vulnerability

Security information can be found in our [security.txt file](https://academy.jahia.com/.well-known/security.txt).

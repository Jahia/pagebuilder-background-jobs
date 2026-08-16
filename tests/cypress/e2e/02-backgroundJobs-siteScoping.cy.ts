import { createSite, createUser, deleteSite, deleteUser, grantRoles, publishAndWaitJobEnding } from '@jahia/cypress'
import { CAN_ACCESS_BACKGROUND_JOBS, errorsOf, GET_BACKGROUND_JOBS } from '../support/backgroundJobsQueries'

/**
 * Regression suite for SEC-140 / GHSA-4vfj-8pfg-4xrp.
 *
 * A principal granted `canAccessJobsInformation` on ONE site must never receive publication-job
 * metadata (notably `userKey` — which user triggered a publication) belonging to another site.
 *
 * Two distinct bypasses are covered, because the first remediation only closed one of them:
 *
 *   1. Querying with the other site's key.  The original defect: the permission was probed against
 *      the requested site while `getVisibleJobs()` returned the whole instance, so the answer was
 *      argument-independent.
 *
 *   2. Querying with NO siteKey at all.  The first fix filtered on the caller-supplied `siteKey`,
 *      so simply omitting it skipped the filter and handed back the global list again. Scoping is
 *      now derived from the caller's own authorization, which is what test 2 pins down.
 */
describe('Page Builder Background Jobs — cross-site scoping (SEC-140)', () => {
    const SITE_ALLOWED = 'pbjsitea'
    const SITE_OTHER = 'pbjsiteb'
    const SCOPED_USER = 'pbjScopedUser'
    const PASSWORD = 'PbjScope5PwdTest'
    const VIEWER_ROLE = 'background-jobs-viewer'

    interface Job {
        name: string
        group: string
        siteKey: string | null
        userKey: string | null
    }

    const jobsOf = (result: never): Job[] =>
        (result as { data: { pageBuilderBackgroundJobs: Job[] } }).data.pageBuilderBackgroundJobs

    const siteKeysIn = (jobs: Job[]) => [...new Set(jobs.map((j) => j.siteKey))]

    const createAndPublishSite = (siteKey: string) => {
        createSite(siteKey, {
            languages: 'en',
            locale: 'en',
            serverName: 'localhost',
            templateSet: 'samples-bootstrap-templates',
        })
        // Publishing is what actually enqueues a PublicationJob carrying this site's siteKey.
        publishAndWaitJobEnding(`/sites/${siteKey}/home`, ['en'])
    }

    before(() => {
        cy.login()
        createAndPublishSite(SITE_ALLOWED)
        createAndPublishSite(SITE_OTHER)
        createUser(SCOPED_USER, PASSWORD)
        // Granted on ONE site only — this is the whole point of the suite.
        grantRoles(`/sites/${SITE_ALLOWED}`, [VIEWER_ROLE], SCOPED_USER, 'USER')
    })

    after(() => {
        cy.apolloClient()
        cy.login()
        deleteUser(SCOPED_USER)
        deleteSite(SITE_ALLOWED)
        deleteSite(SITE_OTHER)
    })

    it('root sees jobs from both sites', () => {
        cy.apolloClient()
        cy.apollo({ query: GET_BACKGROUND_JOBS }).then((result: never) => {
            expect(errorsOf(result), 'should have no errors').to.have.length(0)
            const keys = siteKeysIn(jobsOf(result))
            expect(keys, 'root should see the allowed site').to.include(SITE_ALLOWED)
            expect(keys, 'root should see the other site').to.include(SITE_OTHER)
        })
    })

    it('grants the scoped user access to its own site', () => {
        cy.apolloClient({ username: SCOPED_USER, password: PASSWORD })
        cy.apollo({ query: CAN_ACCESS_BACKGROUND_JOBS, variables: { siteKey: SITE_ALLOWED } }).then((result: never) => {
            expect(
                (result as { data: { canAccessPageBuilderBackgroundJobs: boolean } }).data
                    .canAccessPageBuilderBackgroundJobs,
            ).to.equal(true)
        })
    })

    it('returns only the authorized site when the scoped user asks for it', () => {
        cy.apolloClient({ username: SCOPED_USER, password: PASSWORD })
        cy.apollo({ query: GET_BACKGROUND_JOBS, variables: { siteKey: SITE_ALLOWED } }).then((result: never) => {
            expect(errorsOf(result), 'should have no errors').to.have.length(0)
            const jobs = jobsOf(result)
            expect(siteKeysIn(jobs), 'must not leak any other site').to.deep.equal([SITE_ALLOWED])
        })
    })

    // Bypass 1 — the originally reported defect.
    it('denies the scoped user when it asks for a site it is not authorized on', () => {
        cy.apolloClient({ username: SCOPED_USER, password: PASSWORD })
        cy.apollo({ query: CAN_ACCESS_BACKGROUND_JOBS, variables: { siteKey: SITE_OTHER } }).then((result: never) => {
            expect(
                (result as { data: { canAccessPageBuilderBackgroundJobs: boolean } }).data
                    .canAccessPageBuilderBackgroundJobs,
                'the any-site fallback must be gone',
            ).to.equal(false)
        })
    })

    it('never returns the other site s jobs, whatever siteKey is supplied', () => {
        cy.apolloClient({ username: SCOPED_USER, password: PASSWORD })
        cy.apollo({ query: GET_BACKGROUND_JOBS, variables: { siteKey: SITE_OTHER }, errorPolicy: 'all' }).then(
            (result: never) => {
                const jobs = (result as { data?: { pageBuilderBackgroundJobs?: Job[] } }).data
                    ?.pageBuilderBackgroundJobs
                // Either denied outright, or scoped — but never containing the unauthorized site.
                if (jobs) {
                    expect(siteKeysIn(jobs)).to.not.include(SITE_OTHER)
                } else {
                    expect(errorsOf(result), 'denial errors').to.have.length.greaterThan(0)
                }
            },
        )
    })

    // Bypass 2 — the gap left by the first remediation. This is the test that would have caught it.
    it('scopes the result even when siteKey is omitted entirely', () => {
        cy.apolloClient({ username: SCOPED_USER, password: PASSWORD })
        cy.apollo({ query: GET_BACKGROUND_JOBS, errorPolicy: 'all' }).then((result: never) => {
            const jobs = (result as { data?: { pageBuilderBackgroundJobs?: Job[] } }).data?.pageBuilderBackgroundJobs
            if (jobs) {
                expect(
                    siteKeysIn(jobs),
                    'omitting siteKey must not fall back to the instance-wide list',
                ).to.not.include(SITE_OTHER)
            } else {
                expect(errorsOf(result), 'denial errors').to.have.length.greaterThan(0)
            }
        })
    })

    // Bypass 3 — siteKey and path disagree. `siteKey` decides the scope, `path` decided the permission,
    // so a genuine grant on the allowed site could be spent to read the other one.
    it('denies a siteKey/path mismatch instead of scoping to the unauthorized site', () => {
        cy.apolloClient({ username: SCOPED_USER, password: PASSWORD })
        cy.apollo({
            query: GET_BACKGROUND_JOBS,
            variables: { siteKey: SITE_OTHER, path: `/sites/${SITE_ALLOWED}/home` },
            errorPolicy: 'all',
        }).then((result: never) => {
            const jobs = (result as { data?: { pageBuilderBackgroundJobs?: Job[] } }).data?.pageBuilderBackgroundJobs
            if (jobs) {
                expect(
                    siteKeysIn(jobs),
                    'a grant on the allowed site must not authorize the other site',
                ).to.not.include(SITE_OTHER)
            } else {
                expect(errorsOf(result), 'denial errors').to.have.length.greaterThan(0)
            }
        })
    })

    it('reports access as denied for a siteKey/path mismatch', () => {
        cy.apolloClient({ username: SCOPED_USER, password: PASSWORD })
        cy.apollo({
            query: CAN_ACCESS_BACKGROUND_JOBS,
            variables: { siteKey: SITE_OTHER, path: `/sites/${SITE_ALLOWED}/home` },
        }).then((result: never) => {
            expect(
                (result as { data: { canAccessPageBuilderBackgroundJobs: boolean } }).data
                    .canAccessPageBuilderBackgroundJobs,
            ).to.equal(false)
        })
    })

    it('scopes the result when only a path is supplied', () => {
        cy.apolloClient({ username: SCOPED_USER, password: PASSWORD })
        cy.apollo({
            query: GET_BACKGROUND_JOBS,
            variables: { path: `/sites/${SITE_ALLOWED}/home` },
            errorPolicy: 'all',
        }).then((result: never) => {
            const jobs = (result as { data?: { pageBuilderBackgroundJobs?: Job[] } }).data?.pageBuilderBackgroundJobs
            if (jobs) {
                expect(siteKeysIn(jobs)).to.not.include(SITE_OTHER)
            }
        })
    })

    it('does not expose another user s userKey through the scoped list', () => {
        cy.apolloClient({ username: SCOPED_USER, password: PASSWORD })
        cy.apollo({ query: GET_BACKGROUND_JOBS, variables: { siteKey: SITE_ALLOWED } }).then((result: never) => {
            const foreign = jobsOf(result).filter((job) => job.siteKey !== SITE_ALLOWED)
            expect(foreign, 'no job outside the authorized site may be returned').to.have.length(0)
        })
    })
})

import {createSite, createUser, deleteSite, deleteUser, grantRoles, publishAndWaitJobEnding} from '@jahia/cypress';
import {CAN_ACCESS_BACKGROUND_JOBS, errorsOf, GET_BACKGROUND_JOBS} from '../support/backgroundJobsQueries';

/**
 * Regression suite for cross-site job-metadata scoping.
 *
 * A principal granted `canAccessJobsInformation` on ONE site must never learn anything about another
 * site's publication jobs — notably `userKey`, i.e. which user triggered a publication.
 *
 * FOUR bypasses were found, three of them introduced by the fix for the previous one:
 *
 *   1. Ask for the other site's key. The permission was probed against the requested site while
 *      getVisibleJobs() returned the whole instance, so the answer was argument-independent.
 *   2. Omit siteKey entirely. The first fix filtered on that argument, so leaving it out skipped the
 *      filter and returned the global list again.
 *   3. Disagreeing siteKey and path. The permission was checked against `path` while the result was
 *      scoped to `siteKey`.
 *   4. A '..' path. JCR collapses '..' before resolving, so a string-parsed site key and the node the
 *      permission was actually evaluated on diverged.
 *
 * WHY THIS FILE IS SPLIT IN TWO
 * -----------------------------
 * `SchedulerService.getAllJobs()` returns ACTIVE jobs only — Jahia's own schema documents
 * GqlScheduler.jobs as 'List of active jobs', and the JCR publish mutation takes no date argument, so
 * a publication job cannot be parked in the scheduler. Once a publication finishes, its job is gone.
 *
 * An earlier version of this suite awaited publication and then asserted on job contents, so every
 * assertion ran against an empty list. Some failed loudly; the ones written as `if (jobs) {...}` would
 * have passed while guarding nothing at all.
 *
 * So the two kinds of assertion are now separated:
 *
 *   - 'authorization decisions' uses canAccessPageBuilderBackgroundJobs, a boolean that does not
 *     depend on any job existing. Deterministic, and the real regression guard for all four bypasses.
 *   - 'returned job scoping' needs a live job. It establishes that precondition explicitly and fails
 *     with a clear message if it cannot, rather than passing vacuously.
 */
describe('Page Builder Background Jobs — cross-site scoping', () => {
    const SITE_ALLOWED = 'pbjsitea';
    const SITE_OTHER = 'pbjsiteb';
    const SCOPED_USER = 'pbjScopedUser';
    const PASSWORD = 'PbjScope5PwdTest';
    const VIEWER_ROLE = 'background-jobs-viewer';

    interface Job {
        name: string;
        group: string;
        siteKey: string | null;
        userKey: string | null;
    }

    const jobsOf = (result: never): Job[] =>
        (result as {data?: {pageBuilderBackgroundJobs?: Job[]}}).data?.pageBuilderBackgroundJobs ?? [];

    const siteKeysIn = (jobs: Job[]) => [...new Set(jobs.map(j => j.siteKey))];

    const asScopedUser = () => cy.apolloClient({username: SCOPED_USER, password: PASSWORD});

    /** The access probe returns a boolean and never throws, so this is deterministic with zero jobs. */
    const canAccessAs = (variables: Record<string, string>) => {
        asScopedUser();
        return cy.apollo({query: CAN_ACCESS_BACKGROUND_JOBS, variables}).then((result: never) => {
            expect(errorsOf(result), 'the access probe must answer, not error').to.have.length(0);
            return (result as {data: {canAccessPageBuilderBackgroundJobs: boolean}}).data
                .canAccessPageBuilderBackgroundJobs;
        });
    };

    before(() => {
        cy.login();
        [SITE_ALLOWED, SITE_OTHER].forEach(siteKey => {
            createSite(siteKey, {
                languages: 'en',
                locale: 'en',
                serverName: 'localhost',
                templateSet: 'dx-base-demo-templates'
            });
            // Awaited here only to leave both sites in a clean, fully-published state.
            publishAndWaitJobEnding(`/sites/${siteKey}/home`, ['en']);
        });
        createUser(SCOPED_USER, PASSWORD);
        // Granted on ONE site only — the entire point of this suite.
        grantRoles(`/sites/${SITE_ALLOWED}`, [VIEWER_ROLE], SCOPED_USER, 'USER');

        // HARD PRECONDITION. Every 'denies ...' test below asserts canAccess === false, which is also
        // what a completely broken setup returns -- no site, no role grant, nothing to leak. Without
        // this check those tests would report green against a wide-open build. Asserting it in before()
        // makes the whole file fail loudly instead.
        cy.apolloClient({username: SCOPED_USER, password: PASSWORD});
        cy.apollo({query: CAN_ACCESS_BACKGROUND_JOBS, variables: {siteKey: SITE_ALLOWED}}).then((result: never) => {
            expect(errorsOf(result), 'setup: the access probe must answer for the scoped user').to.have.length(0);
            expect(
                (result as {data: {canAccessPageBuilderBackgroundJobs: boolean}}).data
                    .canAccessPageBuilderBackgroundJobs,
                'SETUP FAILED: the scoped user is not authorized on its own site, so every ' +
                    'deny-assertion in this file would pass vacuously. Check that the site was created ' +
                    '(a missing template set makes createSite fail silently) and that roles.xml was ' +
                    'imported so background-jobs-viewer exists.'
            ).to.equal(true);
        });
        cy.apolloClient();
    });

    after(() => {
        cy.apolloClient();
        cy.login();
        deleteUser(SCOPED_USER);
        deleteSite(SITE_ALLOWED);
        deleteSite(SITE_OTHER);
    });

    describe('authorization decisions', () => {
        it('grants the scoped user on the site it holds the role on', () => {
            canAccessAs({siteKey: SITE_ALLOWED}).should('equal', true);
        });

        it('grants the scoped user for a path inside that site', () => {
            canAccessAs({path: `/sites/${SITE_ALLOWED}/home`}).should('equal', true);
        });

        it('grants the scoped user when no site is specified at all', () => {
            // Counterpart to bypass 2: omitting siteKey must resolve to the caller's OWN authorized
            // set — which is non-empty here — rather than to a denial or to everything.
            canAccessAs({}).should('equal', true);
        });

        // Bypass 1 — the originally reported defect.
        it('denies the scoped user on a site it is not authorized on', () => {
            canAccessAs({siteKey: SITE_OTHER}).should('equal', false);
        });

        // Bypass 3 — siteKey and path disagree.
        it('denies a siteKey/path mismatch', () => {
            canAccessAs({siteKey: SITE_OTHER, path: `/sites/${SITE_ALLOWED}/home`}).should('equal', false);
        });

        // Bypass 4 — '..' traversal. JCR normalizes the path; string parsing does not.
        it('denies a traversal path that resolves outside the named site', () => {
            canAccessAs({path: `/sites/${SITE_OTHER}/../${SITE_ALLOWED}`}).should('equal', false);
        });

        it('denies a traversal path even when siteKey names the unauthorized site', () => {
            canAccessAs({
                siteKey: SITE_OTHER,
                path: `/sites/${SITE_OTHER}/../${SITE_ALLOWED}`
            }).should('equal', false);
        });

        it('denies a path outside /sites entirely', () => {
            canAccessAs({path: '/modules'}).should('equal', false);
        });
    });

    /**
     * These tests need a live publication job attributable to a site.
     *
     * Jahia does not attribute one: BackgroundJob.createJahiaJob() writes only
     * created/status/userkey/currentLocale, PublicationJob never sets siteKey, and the only class in
     * jahia-impl writing "sitekey" is the legacy GWT PublicationHelper. A publication triggered through
     * the modern GraphQL path therefore arrives with siteKey = null, and root really did observe
     * siteKeysIn(...) === [null] -- which left a site-scoped caller seeing an empty dialog.
     *
     * The module now extrapolates the site set from "publicationPaths", which
     * ComplexPublicationServiceImpl (the service behind the modern path) does record. These tests are
     * the end-to-end proof of that: they assert the precondition explicitly, so if extrapolation ever
     * stops working they fail loudly instead of passing on an empty list.
     */
    describe('returned job scoping', () => {
        /**
         * Publishes a whole site subtree WITHOUT awaiting it, then polls our own field as root until a
         * job is visible. Publishing the subtree rather than one page makes the job long enough to
         * observe; polling our own field means the poll exercises the code under test.
         */
        const startPublicationAndWaitForVisibleJob = (siteKey: string) => {
            cy.apolloClient();
            cy.apollo({
                mutationFile: 'graphql/jcr/mutation/publishNode.graphql',
                variables: {
                    pathOrId: `/sites/${siteKey}`,
                    languages: ['en'],
                    publishSubNodes: true,
                    includeSubTree: true
                }
            });

            return cy
                .waitUntil(
                    () =>
                        cy
                            .apollo({query: GET_BACKGROUND_JOBS, fetchPolicy: 'no-cache'})
                            .then((result: never) => jobsOf(result).length > 0),
                    {
                        errorMsg:
                            'Precondition not met: no publication job was ever visible to root. ' +
                            'getAllJobs() lists ACTIVE jobs only, so the publication finished before it ' +
                            'could be observed. This test cannot verify scoping without a live job — ' +
                            'treat it as inconclusive, not as a pass.',
                        timeout: 30000,
                        interval: 250
                    }
                )
                .then(() => cy.apollo({query: GET_BACKGROUND_JOBS, fetchPolicy: 'no-cache'}));
        };

        it('never returns another site s jobs to a site-scoped caller', () => {
            startPublicationAndWaitForVisibleJob(SITE_OTHER).then((rootResult: never) => {
                // Precondition asserted rather than assumed: if root cannot see the unauthorized
                // site's job, the scoping assertion below would prove nothing.
                expect(
                    siteKeysIn(jobsOf(rootResult)),
                    'root must see the unauthorized site s job for this test to mean anything'
                ).to.include(SITE_OTHER);

                asScopedUser();
                cy.apollo({query: GET_BACKGROUND_JOBS, fetchPolicy: 'no-cache', errorPolicy: 'all'}).then(
                    (scopedResult: never) => {
                        const scopedJobs = jobsOf(scopedResult);
                        expect(
                            siteKeysIn(scopedJobs),
                            'a pbjsitea-only grantee must not see pbjsiteb jobs'
                        ).to.not.include(SITE_OTHER);
                        expect(
                            scopedJobs.filter(job => job.siteKey !== SITE_ALLOWED),
                            'no job outside the authorized site may be returned, so no foreign userKey either'
                        ).to.have.length(0);
                    }
                );
            });
        });

        it('returns the same scoped view however the caller frames the request', () => {
            startPublicationAndWaitForVisibleJob(SITE_OTHER).then(() => {
                const framings: Array<Record<string, string>> = [
                    {siteKey: SITE_ALLOWED},
                    {path: `/sites/${SITE_ALLOWED}/home`},
                    {},
                    {siteKey: SITE_OTHER},
                    {siteKey: SITE_OTHER, path: `/sites/${SITE_ALLOWED}/home`},
                    {path: `/sites/${SITE_OTHER}/../${SITE_ALLOWED}`}
                ];

                framings.forEach(variables => {
                    asScopedUser();
                    cy.apollo({
                        query: GET_BACKGROUND_JOBS,
                        variables,
                        fetchPolicy: 'no-cache',
                        errorPolicy: 'all'
                    }).then((result: never) => {
                        expect(
                            siteKeysIn(jobsOf(result)),
                            `framing ${JSON.stringify(variables)} must not leak ${SITE_OTHER}`
                        ).to.not.include(SITE_OTHER);
                    });
                });
            });
        });
    });
});

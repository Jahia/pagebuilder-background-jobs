import {createUser, deleteUser} from '@jahia/cypress';
import {
    CAN_ACCESS_BACKGROUND_JOBS,
    errorsOf,
    GET_BACKGROUND_JOBS,
    GET_SHOW_ALL_JOBS_FLAG,
    messagesOf
} from '../support/backgroundJobsQueries';

/**
 * Baseline contract of the module's GraphQL surface:
 *  - the three root fields exist and are shaped as the frontend expects,
 *  - guest is rejected,
 *  - an authenticated user holding no jobs permission is rejected,
 *  - the `showAllJobs` config probe is gated (it was ungated before SEC-140 C3).
 */
describe('Page Builder Background Jobs — GraphQL API contract', () => {
    const PLAIN_USER = 'pbjPlainUser';
    const PASSWORD = 'PbjApi7PwdTest';

    before(() => {
        cy.login();
        createUser(PLAIN_USER, PASSWORD);
    });

    after(() => {
        cy.apolloClient();
        cy.login();
        deleteUser(PLAIN_USER);
    });

    describe('as root', () => {
        beforeEach(() => {
            cy.apolloClient();
        });

        it('exposes pageBuilderBackgroundJobs returning the fields the dialog consumes', () => {
            cy.apollo({query: GET_BACKGROUND_JOBS}).then((result: never) => {
                expect(errorsOf(result), 'should have no errors').to.have.length(0);
                const jobs = (result as {data: {pageBuilderBackgroundJobs: unknown[]}}).data.pageBuilderBackgroundJobs;
                expect(jobs).to.be.an('array');
            });
        });

        it('reports access as granted', () => {
            cy.apollo({query: CAN_ACCESS_BACKGROUND_JOBS}).then((result: never) => {
                expect(errorsOf(result), 'should have no errors').to.have.length(0);
                expect(
                    (result as {data: {canAccessPageBuilderBackgroundJobs: boolean}}).data
                        .canAccessPageBuilderBackgroundJobs
                ).to.equal(true);
            });
        });

        it('exposes the showAllJobs flag as a boolean', () => {
            cy.apollo({query: GET_SHOW_ALL_JOBS_FLAG}).then((result: never) => {
                expect(errorsOf(result), 'should have no errors').to.have.length(0);
                expect(
                    (result as {data: {pageBuilderBackgroundJobsShowAll: boolean}}).data
                        .pageBuilderBackgroundJobsShowAll
                ).to.be.a('boolean');
            });
        });
    });

    describe('as an authenticated user with no jobs permission', () => {
        beforeEach(() => {
            cy.apolloClient({username: PLAIN_USER, password: PASSWORD});
        });

        it('denies the jobs query', () => {
            cy.apollo({query: GET_BACKGROUND_JOBS, errorPolicy: 'all'}).then((result: never) => {
                expect(errorsOf(result), 'denial errors').to.have.length.greaterThan(0);
                expect(messagesOf(result)).to.contain('Permission denied');
            });
        });

        /**
         * The security property is that a caller holding no jobs permission learns nothing. Two
         * outcomes satisfy it, depending on whether the API scope in
         * org.jahia.bundles.api.authorization-pagebuilder-background-jobs.yml gates the field with a
         * constraint:
         *
         *   - the field is reachable and the resolver answers `false`, or
         *   - the scope does not apply and the field is refused at the API layer.
         *
         * Both are asserted explicitly -- one of the two MUST hold. What must never happen is `true`,
         * or a value leaking through alongside an error.
         */
        const expectLearnsNothing = (result: never, field: string) => {
            const errors = errorsOf(result);
            const value = (result as {data?: Record<string, boolean>}).data?.[field];
            if (errors.length > 0) {
                expect(value, `${field} must not be answered when the API layer refuses`).to.not.equal(true);
            } else {
                expect(value, `${field} must be denied for a caller with no jobs permission`).to.equal(false);
            }
        };

        it('never reports access as granted', () => {
            cy.apollo({query: CAN_ACCESS_BACKGROUND_JOBS, errorPolicy: 'all'}).then((result: never) => {
                expectLearnsNothing(result, 'canAccessPageBuilderBackgroundJobs');
            });
        });

        // SEC-140 (C3): this probe used to answer for any authenticated user.
        it('does not leak the showAllJobs configuration flag', () => {
            cy.apollo({query: GET_SHOW_ALL_JOBS_FLAG, errorPolicy: 'all'}).then((result: never) => {
                expectLearnsNothing(result, 'pageBuilderBackgroundJobsShowAll');
            });
        });
    });

    describe('as guest', () => {
        it('denies the jobs query', () => {
            cy.apolloClient({username: 'guest', password: 'guest'});
            cy.apollo({query: GET_BACKGROUND_JOBS, errorPolicy: 'all'}).then((result: never) => {
                expect(errorsOf(result), 'denial errors').to.have.length.greaterThan(0);
            });
        });
    });
});

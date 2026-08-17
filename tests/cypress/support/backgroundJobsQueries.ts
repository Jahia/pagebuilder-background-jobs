import gql from 'graphql-tag';

/**
 * The module's GraphQL surface, as the Page Builder dialog consumes it.
 *
 * Declared with `gql` template literals rather than loaded from .graphql fixtures via
 * `require('graphql-tag/loader!...')`: the inline-loader form is a `require()` call, which
 * `@typescript-eslint/no-require-imports` rejects.
 */
export const GET_BACKGROUND_JOBS = gql`
    query getBackgroundJobs($siteKey: String, $path: String) {
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
`;

export const CAN_ACCESS_BACKGROUND_JOBS = gql`
    query canAccessBackgroundJobs($siteKey: String, $path: String) {
        canAccessPageBuilderBackgroundJobs(siteKey: $siteKey, path: $path)
    }
`;

export const GET_SHOW_ALL_JOBS_FLAG = gql`
    query getShowAllJobsFlag {
        pageBuilderBackgroundJobsShowAll
    }
`;

/** GraphQL errors surface differently depending on the client path; normalise them. */
export const errorsOf = (result: {
    graphQLErrors?: Array<{ message: string }>
    errors?: Array<{ message: string }>
}): Array<{ message: string }> => result.graphQLErrors ?? result.errors ?? [];

export const messagesOf = (result: never): string =>
    errorsOf(result)
        .map((e: { message: string }) => e.message)
        .join(' ');

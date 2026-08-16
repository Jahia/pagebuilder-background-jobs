import gql from 'graphql-tag';

export const BackgroundJobsQuery = gql`
    query BackgroundJobsQuery($siteKey: String, $path: String) {
        jobs: pageBuilderBackgroundJobs(siteKey: $siteKey, path: $path) {
            name
            group
            jobDescription
            duration
            jobStatus
            siteKey
            userKey
            createdRaw
            createdTimestamp
        }
        showAllJobs: pageBuilderBackgroundJobsShowAll
    }
`;

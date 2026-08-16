import React from 'react';
import {render, screen} from '@testing-library/react';
import {BackgroundJobsDialog} from './BackgroundJobsDialog';

// Mock Apollo's useQuery so we control the data the dialog renders.
const mockUseQuery = jest.fn();
jest.mock('@apollo/client', () => ({
    useQuery: (...args) => mockUseQuery(...args)
}));

// Moonstone Button -> a plain button exposing its label so we can query by text.
jest.mock('@jahia/moonstone', () => ({
    // eslint-disable-next-line react/prop-types
    Button: ({label, onClick, disabled}) => (
        <button type="button" disabled={disabled} onClick={onClick}>{label}</button>
    )
}));

const baseProps = {
    isOpen: true,
    onClose: jest.fn(),
    onExited: jest.fn(),
    siteKey: 'mySite',
    path: '/sites/mySite'
};

const sampleJobs = [
    {
        name: 'publicationJob-1',
        group: 'PublicationJob',
        jobDescription: 'Publish home page',
        duration: 1500,
        jobState: 'NORMAL',
        jobStatus: 'SUCCESSFUL',
        siteKey: 'mySite',
        userKey: '/users/root',
        createdRaw: '01.01.2026 10:00:00',
        createdTimestamp: 1735725600000
    },
    {
        name: 'publicationJob-2',
        group: 'PublicationJob',
        jobDescription: 'Publish news section',
        duration: 250,
        jobState: 'NORMAL',
        jobStatus: 'EXECUTING',
        siteKey: 'mySite',
        userKey: '',
        createdRaw: '02.01.2026 11:00:00',
        createdTimestamp: 1735812000000
    }
];

const buildQueryResult = overrides => ({
    data: undefined,
    loading: false,
    error: undefined,
    refetch: jest.fn(),
    networkStatus: 7,
    ...overrides
});

beforeEach(() => {
    mockUseQuery.mockReset();
});

describe('BackgroundJobsDialog', () => {
    it('renders the publication-jobs title and the returned jobs', () => {
        mockUseQuery.mockReturnValue(buildQueryResult({
            data: {jobs: sampleJobs, showAllJobs: false}
        }));

        render(<BackgroundJobsDialog {...baseProps}/>);

        expect(screen.getByText('PUBLICATION JOBS')).toBeInTheDocument();
        expect(screen.getByText('Publish home page')).toBeInTheDocument();
        expect(screen.getByText('Publish news section')).toBeInTheDocument();
    });

    it('shows the system user fallback when no userKey is provided', () => {
        mockUseQuery.mockReturnValue(buildQueryResult({
            data: {jobs: sampleJobs, showAllJobs: false}
        }));

        render(<BackgroundJobsDialog {...baseProps}/>);

        // Job-2 has an empty userKey -> formatted as "system".
        expect(screen.getByText('system')).toBeInTheDocument();
    });

    it('renders the background-jobs title when showAllJobs is true', () => {
        mockUseQuery.mockReturnValue(buildQueryResult({
            data: {jobs: sampleJobs, showAllJobs: true}
        }));

        render(<BackgroundJobsDialog {...baseProps}/>);

        expect(screen.getByText('BACKGROUND JOBS')).toBeInTheDocument();
    });

    it('shows the empty-state message when there are no jobs', () => {
        mockUseQuery.mockReturnValue(buildQueryResult({
            data: {jobs: [], showAllJobs: false}
        }));

        render(<BackgroundJobsDialog {...baseProps}/>);

        expect(screen.getByText('No publication jobs found.')).toBeInTheDocument();
    });

    it('surfaces the error message when the query fails', () => {
        mockUseQuery.mockReturnValue(buildQueryResult({
            error: {message: 'boom'}
        }));

        render(<BackgroundJobsDialog {...baseProps}/>);

        expect(screen.getByText(/Unable to load jobs: boom/)).toBeInTheDocument();
    });

    it('exposes the status group toggle with an accessible expanded state', () => {
        mockUseQuery.mockReturnValue(buildQueryResult({
            data: {jobs: sampleJobs, showAllJobs: false}
        }));

        render(<BackgroundJobsDialog {...baseProps}/>);

        const executingToggle = screen.getByRole('button', {name: /STATUS:EXECUTING/});
        expect(executingToggle).toHaveAttribute('aria-expanded', 'true');
    });

    it('renders a Close action', () => {
        mockUseQuery.mockReturnValue(buildQueryResult({
            data: {jobs: sampleJobs, showAllJobs: false}
        }));

        render(<BackgroundJobsDialog {...baseProps}/>);

        expect(screen.getByRole('button', {name: 'Close'})).toBeInTheDocument();
    });

    it('does not render when isOpen is false', () => {
        mockUseQuery.mockReturnValue(buildQueryResult({
            data: {jobs: sampleJobs, showAllJobs: false}
        }));

        render(<BackgroundJobsDialog {...baseProps} isOpen={false}/>);

        expect(screen.queryByText('PUBLICATION JOBS')).not.toBeInTheDocument();
    });
});

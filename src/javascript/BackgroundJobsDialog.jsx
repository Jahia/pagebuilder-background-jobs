import React, {useMemo, useState, useEffect} from 'react';
import PropTypes from 'prop-types';
import {useQuery} from '@apollo/client';
import {
    Dialog,
    DialogActions,
    DialogContent,
    DialogTitle,
    FormControlLabel,
    Switch,
    Table,
    TableBody,
    TableCell,
    TableHead,
    TableRow,
    TablePagination,
    Typography,
    CircularProgress
} from '@material-ui/core';
import {Button} from '@jahia/moonstone';
import {BackgroundJobsQuery} from './backgroundJobs.gql-queries';

const AUTO_REFRESH_DELAY_MS = 5000;
const DEFAULT_ROWS_PER_PAGE = 50;
const STATUS_ORDER = ['EXECUTING', 'SCHEDULED', 'ADDED', 'FAILED', 'CANCELED', 'SUCCESSFUL', 'UNKNOWN'];
const SESSION_STORAGE_KEY = 'pagebuilderBackgroundJobsDialogState';
const COMPACT_HEADER_CELL_STYLE = {
    color: '#1f2a33',
    fontWeight: 600,
    fontSize: '11px',
    padding: '0 6px',
    lineHeight: '16px',
    whiteSpace: 'nowrap',
    overflow: 'hidden',
    textOverflow: 'ellipsis'
};
const COMPACT_CELL_STYLE = {
    color: '#25313b',
    fontSize: '11px',
    padding: '0 6px',
    lineHeight: '16px',
    height: '18px',
    whiteSpace: 'nowrap',
    overflow: 'hidden',
    textOverflow: 'ellipsis'
};
const WRAP_CELL_STYLE = {
    ...COMPACT_CELL_STYLE,
    whiteSpace: 'normal',
    overflowWrap: 'anywhere',
    wordBreak: 'break-word',
    lineHeight: '14px',
    paddingTop: '2px',
    paddingBottom: '2px',
    height: 'auto'
};

const readSessionState = () => {
    if (typeof window === 'undefined') {
        return null;
    }

    try {
        const raw = window.sessionStorage.getItem(SESSION_STORAGE_KEY);
        if (!raw) {
            return null;
        }

        const parsed = JSON.parse(raw);
        return parsed && typeof parsed === 'object' ? parsed : null;
    } catch (e) {
        return null;
    }
};

const writeSessionState = state => {
    if (typeof window === 'undefined') {
        return;
    }

    try {
        window.sessionStorage.setItem(SESSION_STORAGE_KEY, JSON.stringify(state));
    } catch (e) {
        // Ignore session storage failures.
    }
};

const normalizeTimestamp = value => {
    if (!value || value <= 0) {
        return null;
    }

    // Some runtimes expose seconds, others milliseconds.
    return value < 100000000000 ? value * 1000 : value;
};

const getStatus = job => (job?.jobStatus || 'UNKNOWN').toUpperCase();

const compareStatus = (a, b) => {
    const ia = STATUS_ORDER.indexOf(a);
    const ib = STATUS_ORDER.indexOf(b);
    const wa = ia === -1 ? STATUS_ORDER.length : ia;
    const wb = ib === -1 ? STATUS_ORDER.length : ib;
    return wa - wb || a.localeCompare(b);
};

const getCreationTimestamp = job => {
    const directTimestamp = normalizeTimestamp(job?.createdTimestamp);
    if (directTimestamp) {
        return directTimestamp;
    }

    if (!job?.createdRaw) {
        return null;
    }

    const asString = String(job.createdRaw).trim();
    if (!asString) {
        return null;
    }

    if (/^\d+$/.test(asString)) {
        const parsedNumber = Number(asString);
        const normalized = normalizeTimestamp(parsedNumber);
        if (normalized) {
            return normalized;
        }
    }

    const dottedMatch = asString.match(/^(\d{1,2})\.(\d{1,2})\.(\d{4})(?:\s+(\d{1,2}):(\d{2})(?::(\d{2}))?)?$/);
    if (dottedMatch) {
        const day = Number(dottedMatch[1]);
        const month = Number(dottedMatch[2]) - 1;
        const year = Number(dottedMatch[3]);
        const hour = Number(dottedMatch[4] || 0);
        const minute = Number(dottedMatch[5] || 0);
        const second = Number(dottedMatch[6] || 0);
        const ts = new Date(year, month, day, hour, minute, second).getTime();
        if (!Number.isNaN(ts) && ts > 0) {
            return ts;
        }
    }

    const nativeParsed = Date.parse(asString);
    if (!Number.isNaN(nativeParsed) && nativeParsed > 0) {
        return nativeParsed;
    }

    return null;
};

const getCreationTextFallback = job => job?.createdRaw || null;

const formatCreationDate = job => {
    const timestamp = getCreationTimestamp(job);
    if (timestamp) {
        try {
            return new Date(timestamp).toLocaleString('fr-FR', {hour12: false});
        } catch (e) {
            return '-';
        }
    }

    return getCreationTextFallback(job) || '-';
};

const formatDuration = duration => {
    if (duration === null || duration === undefined || duration < 0) {
        return '-';
    }

    if (duration < 1000) {
        return `${duration} ms`;
    }

    return `${(duration / 1000).toFixed(2)} sec`;
};

const formatUser = userKey => {
    if (!userKey || String(userKey).trim() === '') {
        return 'system';
    }

    const normalized = String(userKey).trim();
    const shortName = normalized.substring(normalized.lastIndexOf('/') + 1).trim();
    return shortName || normalized;
};

const toJobKey = (job, index) => [
    job?.name || 'job',
    job?.group || 'group',
    job?.jobStatus || 'status',
    getCreationTimestamp(job) || 0,
    index
].join('|');

export const BackgroundJobsDialog = ({isOpen, onClose, onExited, siteKey, path, initialShowAllJobs = false}) => {
    const [autoRefresh, setAutoRefresh] = useState(() => {
        const state = readSessionState();
        return typeof state?.autoRefresh === 'boolean' ? state.autoRefresh : false;
    });
    const [groupFilters, setGroupFilters] = useState(() => {
        const state = readSessionState();
        return state?.groupFilters && typeof state.groupFilters === 'object' ? state.groupFilters : {};
    });
    const [statusExpanded, setStatusExpanded] = useState(() => {
        const state = readSessionState();
        return state?.statusExpanded && typeof state.statusExpanded === 'object' ? state.statusExpanded : {};
    });
    const [page, setPage] = useState(0);
    const [rowsPerPage, setRowsPerPage] = useState(() => {
        const state = readSessionState();
        return Number.isInteger(state?.rowsPerPage) ? state.rowsPerPage : DEFAULT_ROWS_PER_PAGE;
    });

    const {data, loading, error, refetch, networkStatus} = useQuery(BackgroundJobsQuery, {
        fetchPolicy: 'network-only',
        notifyOnNetworkStatusChange: true,
        skip: !isOpen,
        variables: {siteKey, path}
    });

    const showAllJobs = typeof data?.showAllJobs === 'boolean' ? data.showAllJobs : initialShowAllJobs;
    const dialogTitle = showAllJobs ? 'BACKGROUND JOBS' : 'PUBLICATION JOBS';
    const emptyMessage = showAllJobs ? 'No background jobs found.' : 'No publication jobs found.';

    useEffect(() => {
        if (!isOpen || !autoRefresh) {
            return undefined;
        }

        const interval = window.setInterval(() => {
            refetch();
        }, AUTO_REFRESH_DELAY_MS);

        return () => window.clearInterval(interval);
    }, [isOpen, autoRefresh, refetch]);

    const jobs = useMemo(() => {
        const nodes = data?.jobs || [];
        const mapped = nodes.map((job, index) => ({
            ...job,
            __status: getStatus(job),
            __creationTs: getCreationTimestamp(job),
            __key: toJobKey(job, index)
        }));

        return mapped.sort((a, b) => {
            const statusOrder = compareStatus(a.__status, b.__status);
            if (statusOrder !== 0) {
                return statusOrder;
            }

            const tsA = a.__creationTs || 0;
            const tsB = b.__creationTs || 0;
            if (tsA !== tsB) {
                return tsB - tsA;
            }

            const descA = (a?.jobDescription || a?.name || '').toLowerCase();
            const descB = (b?.jobDescription || b?.name || '').toLowerCase();
            return descA.localeCompare(descB);
        });
    }, [data]);

    const groups = useMemo(() => {
        const values = new Set();
        jobs.forEach(job => values.add(job?.group || 'Other'));
        return [...values].sort((a, b) => a.localeCompare(b));
    }, [jobs]);

    useEffect(() => {
        if (groups.length === 0) {
            return;
        }

        setGroupFilters(current => {
            const next = {...current};
            groups.forEach(group => {
                if (next[group] === undefined) {
                    next[group] = false;
                }
            });
            return next;
        });
    }, [groups]);

    const filteredJobs = useMemo(() => {
        if (!showAllJobs) {
            return jobs;
        }

        return jobs.filter(job => {
            const group = job?.group || 'Other';
            return groupFilters[group] !== false;
        });
    }, [jobs, groupFilters, showAllJobs]);

    const statusCounts = useMemo(() => {
        const counts = {};
        filteredJobs.forEach(job => {
            counts[job.__status] = (counts[job.__status] || 0) + 1;
        });
        return counts;
    }, [filteredJobs]);

    const statusKeys = useMemo(() => Object.keys(statusCounts).sort(compareStatus), [statusCounts]);

    useEffect(() => {
        if (statusKeys.length === 0) {
            return;
        }

        setStatusExpanded(current => {
            const next = {...current};
            statusKeys.forEach(status => {
                if (next[status] === undefined) {
                    next[status] = true;
                }
            });
            return next;
        });
    }, [statusKeys]);

    const jobsByStatus = useMemo(() => {
        const grouped = {};
        statusKeys.forEach(status => {
            grouped[status] = [];
        });
        filteredJobs.forEach(job => {
            grouped[job.__status].push(job);
        });
        return grouped;
    }, [filteredJobs, statusKeys]);

    const expandedVisibleJobs = useMemo(() => (
        statusKeys.flatMap(status => (statusExpanded[status] === false ? [] : jobsByStatus[status] || []))
    ), [statusKeys, statusExpanded, jobsByStatus]);

    useEffect(() => {
        const maxPage = Math.max(0, Math.ceil(expandedVisibleJobs.length / rowsPerPage) - 1);
        if (page > maxPage) {
            setPage(maxPage);
        }
    }, [expandedVisibleJobs.length, rowsPerPage, page]);

    const pagedVisibleJobKeys = useMemo(() => {
        const start = page * rowsPerPage;
        const rows = expandedVisibleJobs.slice(start, start + rowsPerPage);
        return new Set(rows.map(job => job.__key));
    }, [expandedVisibleJobs, page, rowsPerPage]);

    const handleToggleGroup = group => {
        setGroupFilters(current => ({...current, [group]: !current[group]}));
        setPage(0);
    };

    const handleToggleStatus = status => {
        setStatusExpanded(current => ({...current, [status]: !current[status]}));
        setPage(0);
    };

    useEffect(() => {
        writeSessionState({
            autoRefresh,
            groupFilters,
            statusExpanded,
            rowsPerPage
        });
    }, [autoRefresh, groupFilters, statusExpanded, rowsPerPage]);

    return (
        <Dialog
            maxWidth="xl"
            fullWidth
            open={isOpen}
            onClose={onClose}
            onExited={onExited}
            aria-labelledby="background-jobs-dialog-title"
        >
            <DialogTitle id="background-jobs-dialog-title">
                {dialogTitle}
            </DialogTitle>
            <DialogContent>
                <div style={{display: 'flex', justifyContent: 'flex-end', alignItems: 'center', gap: '16px'}}>
                    <FormControlLabel
                        control={(
                            <Switch
                                checked={autoRefresh}
                                onChange={event => setAutoRefresh(event.target.checked)}
                                color="primary"
                            />
                        )}
                        label={<span style={{color: '#1f2a33', fontWeight: 500, fontSize: '13px'}}>Auto refresh</span>}
                    />
                    <Button
                        label={networkStatus === 4 ? 'Refreshing...' : 'Refresh'}
                        variant="outlined"
                        size="default"
                        disabled={networkStatus === 4}
                        onClick={() => refetch()}
                    />
                </div>

                {loading && !data && (
                    <div style={{display: 'flex', justifyContent: 'center', padding: '24px'}}>
                        <CircularProgress size={24}/>
                    </div>
                )}

                {error && (
                    <Typography color="error" style={{marginBottom: '12px'}}>
                        Unable to load jobs: {error.message}
                    </Typography>
                )}

                {!loading && !error && jobs.length === 0 && (
                    <Typography style={{padding: '8px 0'}}>{emptyMessage}</Typography>
                )}

                {jobs.length > 0 && (
                    <div style={{display: 'flex', gap: '24px', marginTop: '8px'}}>
                        {showAllJobs && (
                            <div style={{minWidth: '220px', display: 'flex', flexDirection: 'column', alignItems: 'flex-start'}}>
                                {groups.map(group => (
                                    <FormControlLabel
                                        key={group}
                                        style={{marginTop: 0, marginBottom: 0}}
                                        control={(
                                            <Switch
                                                checked={groupFilters[group] !== false}
                                                onChange={() => handleToggleGroup(group)}
                                                color="primary"
                                            />
                                        )}
                                        label={<span style={{color: '#1f2a33', fontWeight: 500, fontSize: '13px'}}>{group}</span>}
                                    />
                                ))}
                            </div>
                        )}

                        <div style={{flex: 1, overflowX: 'auto'}}>
                            <Table size="small" style={{tableLayout: 'fixed', minWidth: '980px'}}>
                                <TableHead>
                                    <TableRow>
                                        <TableCell style={{...COMPACT_HEADER_CELL_STYLE, width: '16%'}}>Creation time</TableCell>
                                        <TableCell style={{...COMPACT_HEADER_CELL_STYLE, width: '11%'}}>Type</TableCell>
                                        <TableCell style={{...COMPACT_HEADER_CELL_STYLE, width: '21%'}}>Job name</TableCell>
                                        <TableCell style={{...COMPACT_HEADER_CELL_STYLE, width: '15%'}}>User</TableCell>
                                        <TableCell style={{...COMPACT_HEADER_CELL_STYLE, width: '29%'}}>Description</TableCell>
                                        <TableCell style={{...COMPACT_HEADER_CELL_STYLE, width: '8%'}}>Duration</TableCell>
                                    </TableRow>
                                </TableHead>
                                <TableBody>
                                    {statusKeys.map(status => (
                                        <React.Fragment key={status}>
                                            <TableRow style={{height: 18}}>
                                                    <TableCell colSpan={6} style={{padding: '0 4px', height: '18px'}}>
                                                    <button
                                                        type="button"
                                                        onClick={() => handleToggleStatus(status)}
                                                            style={{
                                                                background: 'none',
                                                                border: 0,
                                                                color: '#0f7ea8',
                                                                cursor: 'pointer',
                                                                fontSize: '10px',
                                                                fontWeight: 600,
                                                                padding: 0
                                                            }}
                                                    >
                                                        {statusExpanded[status] === false ? '▶ ' : '▼ '}
                                                        {`STATUS:${status} (${statusCounts[status]} ITEMS)`}
                                                    </button>
                                                </TableCell>
                                            </TableRow>

                                            {statusExpanded[status] !== false &&
                                                (jobsByStatus[status] || [])
                                                    .filter(job => pagedVisibleJobKeys.has(job.__key))
                                                    .map(job => (
                                                        <TableRow key={job.__key} style={{height: 'auto'}}>
                                                            <TableCell style={COMPACT_CELL_STYLE}>{formatCreationDate(job)}</TableCell>
                                                            <TableCell style={COMPACT_CELL_STYLE}>{job?.group || '-'}</TableCell>
                                                            <TableCell
                                                                title={job?.name || '-'}
                                                                style={{
                                                                    ...COMPACT_CELL_STYLE,
                                                                    overflow: 'hidden',
                                                                    textOverflow: 'ellipsis'
                                                                }}
                                                            >
                                                                {job?.name || '-'}
                                                            </TableCell>
                                                            <TableCell style={WRAP_CELL_STYLE}>{formatUser(job?.userKey)}</TableCell>
                                                            <TableCell
                                                                title={job?.jobDescription || job?.name || '-'}
                                                                style={WRAP_CELL_STYLE}
                                                            >
                                                                {job?.jobDescription || job?.name || '-'}
                                                            </TableCell>
                                                            <TableCell style={COMPACT_CELL_STYLE}>{formatDuration(job?.duration)}</TableCell>
                                                        </TableRow>
                                                    ))}
                                        </React.Fragment>
                                    ))}
                                </TableBody>
                            </Table>

                            <TablePagination
                                component="div"
                                count={expandedVisibleJobs.length}
                                page={page}
                                rowsPerPage={rowsPerPage}
                                onChangePage={(event, nextPage) => setPage(nextPage)}
                                onChangeRowsPerPage={event => {
                                    setRowsPerPage(parseInt(event.target.value, 10));
                                    setPage(0);
                                }}
                                rowsPerPageOptions={[50, 25, 10]}
                                style={{fontSize: '11px', minHeight: '32px'}}
                                SelectProps={{style: {fontSize: '11px'}}}
                            />
                        </div>
                    </div>
                )}
            </DialogContent>
            <DialogActions>
                <Button label="Close" variant="ghost" size="default" onClick={onClose}/>
            </DialogActions>
        </Dialog>
    );
};

BackgroundJobsDialog.propTypes = {
    isOpen: PropTypes.bool.isRequired,
    onClose: PropTypes.func.isRequired,
    onExited: PropTypes.func.isRequired,
    initialShowAllJobs: PropTypes.bool,
    siteKey: PropTypes.string,
    path: PropTypes.string
};

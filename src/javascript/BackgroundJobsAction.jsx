import React, {useContext} from 'react';
import PropTypes from 'prop-types';
import {shallowEqual, useSelector} from 'react-redux';
import {ComponentRendererContext} from '@jahia/ui-extender';
import {useQuery} from '@apollo/client';
import gql from 'graphql-tag';
import {BackgroundJobsDialog} from './BackgroundJobsDialog';

const PAGE_BUILDER_VIEW_MODE = 'pageBuilder';
const JobsAccessQuery = gql`
    query JobsAccessQuery($siteKey: String, $path: String) {
        canAccessPageBuilderBackgroundJobs(siteKey: $siteKey, path: $path)
        showAllJobs: pageBuilderBackgroundJobsShowAll
    }
`;

export const BackgroundJobsActionComponent = ({render: Render, ...others}) => {
    const componentRenderer = useContext(ComponentRendererContext);
    const {viewMode, siteKey, currentPath} = useSelector(state => ({
        viewMode: state?.jcontent?.tableView?.viewMode,
        siteKey: state?.site || state?.jcontent?.site,
        currentPath: state?.jcontent?.path
    }), shallowEqual);
    const urlSiteKey = typeof window !== 'undefined' ? window.location.pathname.split('/')[2] : undefined;
    const effectiveSiteKey = siteKey || urlSiteKey;
    const isPageBuilderMode = viewMode === PAGE_BUILDER_VIEW_MODE;
    const {data, error} = useQuery(JobsAccessQuery, {
        skip: !isPageBuilderMode,
        variables: {siteKey: effectiveSiteKey, path: currentPath}
    });

    const hasAccess = Boolean(data?.canAccessPageBuilderBackgroundJobs);
    const showAllJobs = Boolean(data?.showAllJobs);
    const isVisible = isPageBuilderMode && hasAccess && !error;

    return (
        <Render
            {...others}
            buttonLabel={showAllJobs ? 'Background jobs' : 'Publication jobs'}
            isVisible={isVisible}
            enabled={isVisible}
            onClick={() => {
                componentRenderer.destroy('pageBuilderBackgroundJobsDialog');
                componentRenderer.render('pageBuilderBackgroundJobsDialog', BackgroundJobsDialog, {
                    isOpen: true,
                    siteKey: effectiveSiteKey,
                    path: currentPath,
                    initialShowAllJobs: showAllJobs,
                    onClose: () => {
                        componentRenderer.setProperties('pageBuilderBackgroundJobsDialog', {isOpen: false});
                    },
                    onExited: () => {
                        componentRenderer.destroy('pageBuilderBackgroundJobsDialog');
                    }
                });
            }}
        />
    );
};

BackgroundJobsActionComponent.propTypes = {
    render: PropTypes.func.isRequired
};

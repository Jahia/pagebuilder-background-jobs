import React from 'react';
import {registry} from '@jahia/ui-extender';
import {OpenInBrowser} from '@jahia/moonstone';
import {BackgroundJobsActionComponent} from './BackgroundJobsAction';

export default function () {
    registry.add('action', 'pageBuilderBackgroundJobs', {
        buttonLabel: 'Publication jobs',
        buttonIcon: <OpenInBrowser/>,
        targets: ['headerPrimaryActions:95'],
        component: BackgroundJobsActionComponent
    });
}

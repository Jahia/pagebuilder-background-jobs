package org.jahia.support.modules.pagebuilder.backgroundjobs.graphql;

import org.jahia.modules.graphql.provider.dxm.DXGraphQLExtensionsProvider;
import org.osgi.service.component.annotations.Component;

import java.util.Collection;
import java.util.List;

@Component(immediate = true, service = DXGraphQLExtensionsProvider.class)
public class DXGraphQLPageBuilderBackgroundJobsProvider implements DXGraphQLExtensionsProvider {
    @Override
    public Collection<Class<?>> getExtensions() {
        return List.of(
                GqlJahiaAdminQueryBackgroundJobsExtension.class,
                GqlJahiaAdminQueryBackgroundJobsCompatibilityExtension.class
        );
    }
}

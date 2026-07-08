package org.jahia.support.modules.pagebuilder.backgroundjobs.graphql;

import graphql.annotations.annotationTypes.GraphQLDescription;
import graphql.annotations.annotationTypes.GraphQLField;
import graphql.annotations.annotationTypes.GraphQLName;
import graphql.annotations.annotationTypes.GraphQLTypeExtension;
import graphql.schema.DataFetchingEnvironment;
import org.jahia.modules.graphql.provider.dxm.admin.GqlJahiaAdminQuery;
import org.quartz.SchedulerException;

import java.util.List;

@GraphQLTypeExtension(GqlJahiaAdminQuery.class)
@GraphQLDescription("Compatibility extension for legacy admin.jahia background jobs queries")
public class GqlJahiaAdminQueryBackgroundJobsCompatibilityExtension {

    @GraphQLField
    @GraphQLName("pageBuilderBackgroundJobs")
    @GraphQLDescription("Background jobs list for legacy admin.jahia path")
    public List<GqlPageBuilderBackgroundJob> getPageBuilderBackgroundJobs(@GraphQLName("siteKey") String siteKey,
                                                                          @GraphQLName("path") String path,
                                                                          DataFetchingEnvironment environment) throws SchedulerException {
        return GqlJahiaAdminQueryBackgroundJobsExtension.getPageBuilderBackgroundJobs(siteKey, path, environment);
    }

    @GraphQLField
    @GraphQLName("canAccessPageBuilderBackgroundJobs")
    @GraphQLDescription("Access check for legacy admin.jahia path")
    public boolean canAccessPageBuilderBackgroundJobs(@GraphQLName("siteKey") String siteKey,
                                                      @GraphQLName("path") String path,
                                                      DataFetchingEnvironment environment) {
        return GqlJahiaAdminQueryBackgroundJobsExtension.canAccessPageBuilderBackgroundJobs(siteKey, path, environment);
    }

    @GraphQLField
    @GraphQLName("pageBuilderBackgroundJobsShowAll")
    @GraphQLDescription("Current dialog mode for legacy admin.jahia path")
    public boolean pageBuilderBackgroundJobsShowAll(DataFetchingEnvironment environment) {
        return GqlJahiaAdminQueryBackgroundJobsExtension.pageBuilderBackgroundJobsShowAll(environment);
    }

}

package org.jahia.support.modules.pagebuilder.backgroundjobs.graphql;

import graphql.annotations.annotationTypes.GraphQLDescription;
import graphql.annotations.annotationTypes.GraphQLField;
import graphql.annotations.annotationTypes.GraphQLName;
import graphql.annotations.annotationTypes.GraphQLTypeExtension;
import graphql.schema.DataFetchingEnvironment;
import org.jahia.modules.graphql.provider.dxm.DXGraphQLProvider;
import org.jahia.modules.graphql.provider.dxm.util.ContextUtil;
import org.jahia.osgi.BundleUtils;
import org.jahia.registries.ServicesRegistry;
import org.jahia.services.content.JCRSessionFactory;
import org.jahia.services.content.JCRSessionWrapper;
import org.jahia.services.content.decorator.JCRUserNode;
import org.jahia.services.securityfilter.PermissionService;
import org.jahia.services.scheduler.SchedulerService;
import org.jahia.services.usermanager.JahiaUser;
import org.jahia.services.usermanager.JahiaUserManagerService;
import org.jahia.support.modules.pagebuilder.backgroundjobs.service.PageBuilderBackgroundJobsService;
import org.quartz.JobDetail;
import org.quartz.SchedulerException;

import javax.jcr.RepositoryException;
import javax.jcr.Node;
import javax.jcr.NodeIterator;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

@GraphQLTypeExtension(DXGraphQLProvider.Query.class)
@GraphQLDescription("Add Page Builder background jobs query at root level")
public class GqlJahiaAdminQueryBackgroundJobsExtension {
    private static final String CAN_ACCESS_JOBS_INFORMATION = "canAccessJobsInformation";
    private static final String ADMIN_PERMISSION = "admin";
    private static final String PERMISSION_DENIED_MESSAGE = "Permission denied";
    private static final String DEFAULT_WORKSPACE = "default";
    @GraphQLField
    @GraphQLName("pageBuilderBackgroundJobs")
    @GraphQLDescription("Background jobs list for Page Builder dialog")
    public static List<GqlPageBuilderBackgroundJob> getPageBuilderBackgroundJobs(@GraphQLName("siteKey") String siteKey,
                                                                                  @GraphQLName("path") String path,
                                                                                  DataFetchingEnvironment environment) throws SchedulerException {
        if (!hasJobsPermission(siteKey, path, environment)) {
            throw new SecurityException(PERMISSION_DENIED_MESSAGE);
        }

        List<JobDetail> jobs = getVisibleJobs();
        return jobs.stream().map(GqlPageBuilderBackgroundJob::from).collect(Collectors.toList());
    }

    @GraphQLField
    @GraphQLName("canAccessPageBuilderBackgroundJobs")
    @GraphQLDescription("True when current user can access Page Builder background jobs")
    public static boolean canAccessPageBuilderBackgroundJobs(@GraphQLName("siteKey") String siteKey,
                                                             @GraphQLName("path") String path,
                                                             DataFetchingEnvironment environment) {
        return hasJobsPermission(siteKey, path, environment);
    }

    @GraphQLField
    @GraphQLName("pageBuilderBackgroundJobsShowAll")
    @GraphQLDescription("True when the dialog should expose all jobs and UI filters")
    public static boolean pageBuilderBackgroundJobsShowAll() {
        PageBuilderBackgroundJobsService service = BundleUtils.getOsgiService(PageBuilderBackgroundJobsService.class, null);
        return service != null && service.isShowAllJobs();
    }

    private static boolean hasJobsPermission(String siteKey, String path, DataFetchingEnvironment environment) {
        try (PermissionContext context = openPermissionContext(environment)) {
            if (isGuestUser(context.effectiveUser)) {
                return false;
            }
            if (isRootUser(context.effectiveUser)) {
                return true;
            }
            if (hasPermissionOnRequestedPath(context.session, path, environment)) {
                return true;
            }
            if (hasPermissionOnSite(context.session, siteKey, environment)) {
                return true;
            }

            return hasFallbackPermission(context.session, environment);
        } catch (RepositoryException e) {
            throw new RuntimeException("Unable to verify background jobs permission", e);
        }
    }

    private static PermissionContext openPermissionContext(DataFetchingEnvironment environment) throws RepositoryException {
        JCRSessionFactory sessionFactory = JCRSessionFactory.getInstance();
        JahiaUser initialUser = sessionFactory.getCurrentUser();
        JahiaUser effectiveUser = initialUser != null ? initialUser : resolveUserFromRequest(environment);
        boolean userInjected = initialUser == null && effectiveUser != null;

        if (userInjected) {
            sessionFactory.setCurrentUser(effectiveUser);
        }

        return new PermissionContext(initialUser, effectiveUser, getCurrentUserSession(sessionFactory), userInjected);
    }

    private static JCRSessionWrapper getCurrentUserSession(JCRSessionFactory sessionFactory) throws RepositoryException {
        JCRSessionWrapper session = sessionFactory.getCurrentUserSession(DEFAULT_WORKSPACE);
        if (session != null) {
            return session;
        }

        session = sessionFactory.getCurrentUserSession(DEFAULT_WORKSPACE, Locale.ENGLISH);
        if (session != null) {
            return session;
        }

        session = sessionFactory.getCurrentUserSession(DEFAULT_WORKSPACE, Locale.ENGLISH, Locale.ENGLISH);
        if (session != null) {
            return session;
        }

        return sessionFactory.getCurrentUserSession();
    }

    private static boolean isGuestUser(JahiaUser user) {
        return user != null && "guest".equals(user.getName());
    }

    private static boolean isRootUser(JahiaUser user) {
        return user != null && user.isRoot();
    }

    private static boolean hasPermissionOnRequestedPath(JCRSessionWrapper session, String path, DataFetchingEnvironment environment) {
        String normalizedPath = normalize(path);
        return normalizedPath != null && hasAnyPermissionOnPath(session, normalizedPath, environment);
    }

    private static boolean hasPermissionOnSite(JCRSessionWrapper session, String siteKey, DataFetchingEnvironment environment) {
        String normalizedSiteKey = normalize(siteKey);
        if (normalizedSiteKey == null) {
            return false;
        }

        String sitePath = normalizedSiteKey.startsWith("/sites/") ? normalizedSiteKey : "/sites/" + normalizedSiteKey;
        return hasAnyPermissionOnPath(session, sitePath, environment);
    }

    private static boolean hasFallbackPermission(JCRSessionWrapper session, DataFetchingEnvironment environment) {
        return hasAnyPermissionOnPath(session, "/", environment)
                || hasAnyGlobalPermission(environment)
                || hasAnyPermissionOnAnySite(session, environment);
    }

    private static boolean hasAnyPermissionOnPath(JCRSessionWrapper session, String path, DataFetchingEnvironment environment) {
        return hasPermissionOnPath(session, path, ADMIN_PERMISSION, environment)
                || hasPermissionOnPath(session, path, CAN_ACCESS_JOBS_INFORMATION, environment);
    }

    private static boolean hasAnyGlobalPermission(DataFetchingEnvironment environment) {
        return hasPermissionFromSecurityFilter(ADMIN_PERMISSION, environment)
                || hasPermissionFromSecurityFilter(CAN_ACCESS_JOBS_INFORMATION, environment);
    }

    private static boolean hasAnyPermissionOnAnySite(JCRSessionWrapper session, DataFetchingEnvironment environment) {
        return hasPermissionOnAnySite(session, ADMIN_PERMISSION, environment)
                || hasPermissionOnAnySite(session, CAN_ACCESS_JOBS_INFORMATION, environment);
    }

    private static String normalize(String value) {
        if (value == null) {
            return null;
        }

        String trimmedValue = value.trim();
        return trimmedValue.isEmpty() ? null : trimmedValue;
    }

    private static List<JobDetail> getVisibleJobs() throws SchedulerException {
        PageBuilderBackgroundJobsService service = BundleUtils.getOsgiService(PageBuilderBackgroundJobsService.class, null);
        if (service != null) {
            return service.getVisibleJobs();
        }

        SchedulerService schedulerService = ServicesRegistry.getInstance().getSchedulerService();
        if (schedulerService == null) {
            return Collections.emptyList();
        }

        return schedulerService.getAllJobs().stream()
                .filter(PageBuilderBackgroundJobsService::isPublicationJob)
                .collect(Collectors.toList());
    }

    private static boolean hasPermissionOnPath(JCRSessionWrapper session, String path, String permission, DataFetchingEnvironment environment) {
        if (session != null) {
            try {
                if (session.nodeExists(path) && session.getNode(path).hasPermission(permission)) {
                    return true;
                }
            } catch (RepositoryException e) {
                // Continue with other strategies.
            }

            try {
                if (session.hasPermission(path, permission)) {
                    return true;
                }
            } catch (RepositoryException e) {
                // Continue with other strategies.
            }
        }

        return hasPermissionWithService(path, permission, environment);
    }

    private static boolean hasPermissionOnAnySite(JCRSessionWrapper session, String permission, DataFetchingEnvironment environment) {
        try {
            if (session == null) {
                return false;
            }
            if (!session.nodeExists("/sites")) {
                return false;
            }

            NodeIterator sites = session.getNode("/sites").getNodes();
            while (sites.hasNext()) {
                Node site = sites.nextNode();
                if (hasPermissionOnPath(session, site.getPath(), permission, environment)) {
                    return true;
                }
            }
            return false;
        } catch (RepositoryException e) {
            return false;
        }
    }

    private static boolean hasPermissionFromSecurityFilter(String permission, DataFetchingEnvironment environment) {
        try {
            PermissionService permissionService = BundleUtils.getOsgiService(PermissionService.class, null);
            return permissionService != null && permissionService.hasPermission(permission);
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean hasPermissionWithService(String path, String permission, DataFetchingEnvironment environment) {
        try {
            if (path == null || path.trim().isEmpty() || environment == null) {
                return false;
            }

            if (ContextUtil.getHttpServletRequest(environment.getGraphQlContext()) == null) {
                return false;
            }

            PermissionService permissionService = BundleUtils.getOsgiService(PermissionService.class, null);
            if (permissionService == null) {
                return false;
            }

            JCRSessionWrapper currentSession = JCRSessionFactory.getInstance().getCurrentUserSession(DEFAULT_WORKSPACE);
            if (currentSession != null && currentSession.nodeExists(path) &&
                    permissionService.hasPermission(permission, currentSession.getNode(path))) {
                return true;
            }

            JCRSessionWrapper systemSession = JCRSessionFactory.getInstance()
                    .getCurrentSystemSession(DEFAULT_WORKSPACE, Locale.ENGLISH, Locale.ENGLISH);
            if (systemSession != null && systemSession.nodeExists(path) &&
                    permissionService.hasPermission(permission, systemSession.getNode(path))) {
                return true;
            }

            return permissionService.hasPermission(permission);
        } catch (Exception e) {
            return false;
        }
    }

    private static JahiaUser resolveUserFromRequest(DataFetchingEnvironment environment) {
        try {
            if (environment == null) {
                return null;
            }

            javax.servlet.http.HttpServletRequest request = ContextUtil.getHttpServletRequest(environment.getGraphQlContext());
            if (request == null) {
                return null;
            }

            String username = request.getRemoteUser();
            if ((username == null || username.trim().isEmpty()) && request.getUserPrincipal() != null) {
                username = request.getUserPrincipal().getName();
            }
            if (username == null || username.trim().isEmpty()) {
                return null;
            }

            JahiaUserManagerService userService = ServicesRegistry.getInstance().getJahiaUserManagerService();
            if (userService == null) {
                return null;
            }

            JCRUserNode userNode = userService.lookupUser(username);
            return userNode != null ? userNode.getJahiaUser() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static final class PermissionContext implements AutoCloseable {
        private final JahiaUser initialUser;
        private final JahiaUser effectiveUser;
        private final JCRSessionWrapper session;
        private final boolean userInjected;

        private PermissionContext(JahiaUser initialUser, JahiaUser effectiveUser, JCRSessionWrapper session, boolean userInjected) {
            this.initialUser = initialUser;
            this.effectiveUser = effectiveUser;
            this.session = session;
            this.userInjected = userInjected;
        }

        @Override
        public void close() {
            if (userInjected) {
                JCRSessionFactory.getInstance().setCurrentUser(initialUser);
            }
        }
    }
}

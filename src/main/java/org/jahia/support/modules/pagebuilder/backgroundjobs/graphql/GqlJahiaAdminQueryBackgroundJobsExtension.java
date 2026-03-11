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
        return hasJobsPermission(siteKey, path, environment, null);
    }

    private static boolean hasJobsPermission(String siteKey, String path, DataFetchingEnvironment environment, StringBuilder debug) {
        JahiaUser initialUser = null;
        JahiaUser effectiveUser = null;
        boolean userInjected = false;
        try {
            initialUser = JCRSessionFactory.getInstance().getCurrentUser();
            if (debug != null) {
                debug.append("initialUser=").append(initialUser != null ? initialUser.getName() : "null").append("; ");
            }

            effectiveUser = initialUser != null ? initialUser : resolveUserFromRequest(environment);
            if (effectiveUser != null && initialUser == null) {
                JCRSessionFactory.getInstance().setCurrentUser(effectiveUser);
                userInjected = true;
            }
            if (debug != null) {
                debug.append("effectiveUser=").append(effectiveUser != null ? effectiveUser.getName() : "null")
                        .append("; injected=").append(userInjected).append("; ");
            }

            if (effectiveUser != null && "guest".equals(effectiveUser.getName())) {
                if (debug != null) {
                    debug.append("guest=true; ");
                }
                return false;
            }
            if (effectiveUser != null && effectiveUser.isRoot()) {
                if (debug != null) {
                    debug.append("isRoot=true; ");
                }
                return true;
            }

            JCRSessionFactory sessionFactory = JCRSessionFactory.getInstance();
            JCRSessionWrapper session = sessionFactory.getCurrentUserSession("default");
            if (session == null) {
                session = sessionFactory.getCurrentUserSession("default", Locale.ENGLISH);
            }
            if (session == null) {
                session = sessionFactory.getCurrentUserSession("default", Locale.ENGLISH, Locale.ENGLISH);
            }
            if (session == null) {
                session = sessionFactory.getCurrentUserSession();
            }
            if (debug != null) {
                debug.append("sessionAvailable=").append(session != null).append("; ");
            }

            if (path != null && !path.trim().isEmpty()) {
                String normalizedPath = path.trim();
                boolean hasAdminOnPath = hasPermissionOnPath(session, normalizedPath, ADMIN_PERMISSION, environment);
                boolean hasCustomOnPath = hasPermissionOnPath(session, normalizedPath, CAN_ACCESS_JOBS_INFORMATION, environment);
                if (debug != null) {
                    debug.append("path=").append(normalizedPath)
                            .append(",adminOnPath=").append(hasAdminOnPath)
                            .append(",customOnPath=").append(hasCustomOnPath).append("; ");
                }
                if (hasAdminOnPath || hasCustomOnPath) {
                    return true;
                }
            }

            if (siteKey != null && !siteKey.trim().isEmpty()) {
                String normalizedSiteKey = siteKey.trim();
                String sitePath = normalizedSiteKey.startsWith("/sites/") ? normalizedSiteKey : "/sites/" + normalizedSiteKey;
                boolean hasAdminOnSite = hasPermissionOnPath(session, sitePath, ADMIN_PERMISSION, environment);
                boolean hasCustomOnSite = hasPermissionOnPath(session, sitePath, CAN_ACCESS_JOBS_INFORMATION, environment);
                if (debug != null) {
                    debug.append("sitePath=").append(sitePath)
                            .append(",adminOnSite=").append(hasAdminOnSite)
                            .append(",customOnSite=").append(hasCustomOnSite).append("; ");
                }
                if (hasAdminOnSite || hasCustomOnSite) {
                    return true;
                }
            }

            boolean hasAdminOnRoot = hasPermissionOnPath(session, "/", ADMIN_PERMISSION, environment);
            boolean hasCustomOnRoot = hasPermissionOnPath(session, "/", CAN_ACCESS_JOBS_INFORMATION, environment);
            if (debug != null) {
                debug.append("adminOnRoot=").append(hasAdminOnRoot)
                        .append(",customOnRoot=").append(hasCustomOnRoot).append("; ");
            }
            if (hasAdminOnRoot || hasCustomOnRoot) {
                return true;
            }

            boolean hasGlobalAdmin = hasPermissionFromSecurityFilter(ADMIN_PERMISSION, environment);
            boolean hasGlobalCustom = hasPermissionFromSecurityFilter(CAN_ACCESS_JOBS_INFORMATION, environment);
            if (debug != null) {
                debug.append("globalAdmin=").append(hasGlobalAdmin)
                        .append(",globalCustom=").append(hasGlobalCustom).append("; ");
            }
            if (hasGlobalAdmin || hasGlobalCustom) {
                return true;
            }

            boolean hasAdminOnAnySite = hasPermissionOnAnySite(session, ADMIN_PERMISSION, environment);
            boolean hasCustomOnAnySite = hasPermissionOnAnySite(session, CAN_ACCESS_JOBS_INFORMATION, environment);
            if (debug != null) {
                debug.append("adminOnAnySite=").append(hasAdminOnAnySite)
                        .append(",customOnAnySite=").append(hasCustomOnAnySite).append("; ");
            }
            return hasAdminOnAnySite || hasCustomOnAnySite;
        } catch (RepositoryException e) {
            throw new RuntimeException("Unable to verify background jobs permission", e);
        } finally {
            if (userInjected) {
                JCRSessionFactory.getInstance().setCurrentUser(initialUser);
            }
        }
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

            JCRSessionWrapper currentSession = JCRSessionFactory.getInstance().getCurrentUserSession("default");
            if (currentSession != null && currentSession.nodeExists(path) &&
                    permissionService.hasPermission(permission, currentSession.getNode(path))) {
                return true;
            }

            JCRSessionWrapper systemSession = JCRSessionFactory.getInstance()
                    .getCurrentSystemSession("default", Locale.ENGLISH, Locale.ENGLISH);
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
}

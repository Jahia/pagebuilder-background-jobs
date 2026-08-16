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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@GraphQLTypeExtension(DXGraphQLProvider.Query.class)
@GraphQLDescription("Add Page Builder background jobs query at root level")
public class GqlJahiaAdminQueryBackgroundJobsExtension {
    private static final Logger LOGGER = LoggerFactory.getLogger(GqlJahiaAdminQueryBackgroundJobsExtension.class);
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
        // SEC-140: scope the result to the sites the caller is actually authorized on. getVisibleJobs()
        // returns jobs for the WHOLE instance, so the returned set must be filtered against the caller's
        // authorization, never against the caller-supplied siteKey argument (which is attacker-controlled
        // and may simply be omitted). Only an unrestricted principal (root / server-wide grant) sees all.
        JobsAccess access = resolveJobsAccess(siteKey, path, environment);
        if (!access.isGranted()) {
            throw new SecurityException(PERMISSION_DENIED_MESSAGE);
        }

        List<JobDetail> jobs = getVisibleJobs();
        java.util.stream.Stream<GqlPageBuilderBackgroundJob> stream =
                jobs.stream().map(GqlPageBuilderBackgroundJob::from);
        return stream.filter(job -> isVisibleTo(job, access)).collect(Collectors.toList());
    }

    /**
     * Whether a single job may be shown to a caller holding {@code access}. Package-private so the
     * SEC-140 scoping rule can be unit-tested without standing up a JCR session.
     */
    static boolean isVisibleTo(GqlPageBuilderBackgroundJob job, JobsAccess access) {
        if (!access.isGranted()) {
            return false;
        }
        if (access.isUnrestricted()) {
            return true;
        }
        // Jobs with no siteKey are instance-level and are not attributable to an authorized site,
        // so a site-scoped caller must not see them either.
        return job.getSiteKey() != null && access.getAuthorizedSiteKeys().contains(job.getSiteKey());
    }

    /** The {@code showAllJobs} config flag, read WITHOUT a permission gate (used for internal scoping). */
    private static boolean isShowAllJobsEnabled() {
        PageBuilderBackgroundJobsService service = BundleUtils.getOsgiService(PageBuilderBackgroundJobsService.class, null);
        return service != null && service.isShowAllJobs();
    }

    @GraphQLField
    @GraphQLName("canAccessPageBuilderBackgroundJobs")
    @GraphQLDescription("True when current user can access Page Builder background jobs")
    public static boolean canAccessPageBuilderBackgroundJobs(@GraphQLName("siteKey") String siteKey,
                                                             @GraphQLName("path") String path,
                                                             DataFetchingEnvironment environment) {
        return resolveJobsAccess(siteKey, path, environment).isGranted();
    }

    @GraphQLField
    @GraphQLName("pageBuilderBackgroundJobsShowAll")
    @GraphQLDescription("True when the dialog should expose all jobs and UI filters")
    public static boolean pageBuilderBackgroundJobsShowAll(DataFetchingEnvironment environment) {
        // SEC-140 (C3): gate this config probe behind the same jobs permission (was ungated, so any
        // authenticated user could read the flag). null site/path -> guest rejected, root allowed,
        // else granted only if the caller is authorized on at least one site.
        if (!resolveJobsAccess(null, null, environment).isGranted()) {
            return false;
        }
        return isShowAllJobsEnabled();
    }

    /**
     * Resolves what the caller may see, rather than answering a plain yes/no. The distinction matters:
     * the previous {@code hasJobsPermission} returned a boolean, so the caller-supplied {@code siteKey}
     * was the only thing left to filter on — and it is attacker-controlled (SEC-140).
     */
    private static JobsAccess resolveJobsAccess(String siteKey, String path, DataFetchingEnvironment environment) {
        try (PermissionContext context = openPermissionContext(environment)) {
            if (isGuestUser(context.effectiveUser)) {
                return JobsAccess.denied();
            }
            if (isRootUser(context.effectiveUser)) {
                return JobsAccess.unrestricted();
            }
            // A grant on the repository root is genuinely instance-wide, so such a principal legitimately
            // sees every site's jobs (including instance-level jobs that carry no siteKey).
            // The security-filter global scope check is deliberately NOT consulted here: it reports API
            // scopes, not the caller's roles on a node, and the advisory (§6, bullet 3) calls out that
            // fallback as part of the defect.
            if (hasAnyPermissionOnPath(context.session, "/", environment)) {
                return JobsAccess.unrestricted();
            }

            Set<String> authorizedSiteKeys = collectAuthorizedSiteKeys(context.session, environment);
            String requestedSiteKey = resolveRequestedSiteKey(siteKey, path);

            // SEC-140 remediation (§6, bullet 3): when a specific site is requested the caller must hold
            // the permission on THAT site. The previous any-site / global-scope fallback let a grantee on
            // site A pass the probe for site B — the scope confusion this advisory is about.
            if (requestedSiteKey != null) {
                boolean granted = authorizedSiteKeys.contains(requestedSiteKey)
                        || hasPermissionOnRequestedPath(context.session, path, environment);
                return granted
                        ? JobsAccess.scopedTo(Collections.singleton(requestedSiteKey))
                        : JobsAccess.denied();
            }

            // No site requested: fall back to the caller's own authorized set, never to the global list.
            return authorizedSiteKeys.isEmpty()
                    ? JobsAccess.denied()
                    : JobsAccess.scopedTo(authorizedSiteKeys);
        } catch (RepositoryException e) {
            throw new IllegalStateException("Unable to verify background jobs permission", e);
        }
    }

    /** Every site under {@code /sites} on which the caller holds one of the jobs permissions. */
    private static Set<String> collectAuthorizedSiteKeys(JCRSessionWrapper session, DataFetchingEnvironment environment) {
        Set<String> authorized = new LinkedHashSet<>();
        try {
            if (session == null || !session.nodeExists("/sites")) {
                return authorized;
            }

            NodeIterator sites = session.getNode("/sites").getNodes();
            while (sites.hasNext()) {
                Node site = sites.nextNode();
                if (hasAnyPermissionOnPath(session, site.getPath(), environment)) {
                    authorized.add(site.getName());
                }
            }
        } catch (RepositoryException e) {
            LOGGER.warn("Unable to enumerate authorized sites for background jobs; denying by default", e);
            return Collections.emptySet();
        }
        return authorized;
    }

    /**
     * The site key the caller is asking about, taken from {@code siteKey} or derived from {@code path}.
     * Package-private for testing.
     */
    static String resolveRequestedSiteKey(String siteKey, String path) {
        String normalizedSiteKey = normalize(siteKey);
        if (normalizedSiteKey != null) {
            return normalizedSiteKey.startsWith("/sites/")
                    ? firstSegment(normalizedSiteKey.substring("/sites/".length()))
                    : normalizedSiteKey;
        }

        String normalizedPath = normalize(path);
        if (normalizedPath != null && normalizedPath.startsWith("/sites/")) {
            return firstSegment(normalizedPath.substring("/sites/".length()));
        }

        return null;
    }

    private static String firstSegment(String value) {
        int slash = value.indexOf('/');
        String segment = slash == -1 ? value : value.substring(0, slash);
        return segment.isEmpty() ? null : segment;
    }

    /** Outcome of an authorization check: denied, unrestricted, or limited to a set of site keys. */
    static final class JobsAccess {
        private final boolean granted;
        private final boolean unrestricted;
        private final Set<String> authorizedSiteKeys;

        private JobsAccess(boolean granted, boolean unrestricted, Set<String> authorizedSiteKeys) {
            this.granted = granted;
            this.unrestricted = unrestricted;
            this.authorizedSiteKeys = authorizedSiteKeys;
        }

        static JobsAccess denied() {
            return new JobsAccess(false, false, Collections.emptySet());
        }

        static JobsAccess unrestricted() {
            return new JobsAccess(true, true, Collections.emptySet());
        }

        static JobsAccess scopedTo(Set<String> siteKeys) {
            return new JobsAccess(true, false, Collections.unmodifiableSet(new LinkedHashSet<>(siteKeys)));
        }

        boolean isGranted() {
            return granted;
        }

        boolean isUnrestricted() {
            return unrestricted;
        }

        Set<String> getAuthorizedSiteKeys() {
            return authorizedSiteKeys;
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

        try {
            return new PermissionContext(initialUser, effectiveUser, getCurrentUserSession(sessionFactory), userInjected);
        } catch (RepositoryException | RuntimeException e) {
            // Without this, a failure to open the session leaves the injected identity bound to the pooled
            // request thread (PermissionContext was never built, so its close() never runs) and the next
            // request served by that thread inherits it.
            if (userInjected) {
                sessionFactory.setCurrentUser(initialUser);
            }
            throw e;
        }
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

    private static boolean hasAnyPermissionOnPath(JCRSessionWrapper session, String path, DataFetchingEnvironment environment) {
        return hasPermissionOnPath(session, path, ADMIN_PERMISSION, environment)
                || hasPermissionOnPath(session, path, CAN_ACCESS_JOBS_INFORMATION, environment);
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

            // SEC-140: evaluate the permission ONLY against the caller's own session. The previous code
            // retried the same check on a system session — which bypasses ACLs by design, so any path that
            // merely existed satisfied it — and then fell through to the security-filter's global scope
            // check, which is about API scopes, not the caller's roles on this node. Both turned a
            // path-specific probe into an instance-wide grant.
            JCRSessionWrapper currentSession = JCRSessionFactory.getInstance().getCurrentUserSession(DEFAULT_WORKSPACE);
            return currentSession != null && currentSession.nodeExists(path)
                    && permissionService.hasPermission(permission, currentSession.getNode(path));
        } catch (RepositoryException e) {
            LOGGER.debug("Permission check via PermissionService failed for path {} and permission {}", path, permission, e);
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

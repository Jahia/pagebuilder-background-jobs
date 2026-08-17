package org.jahia.support.modules.pagebuilder.backgroundjobs.graphql;

import graphql.annotations.annotationTypes.GraphQLDescription;
import graphql.annotations.annotationTypes.GraphQLField;
import graphql.annotations.annotationTypes.GraphQLName;
import graphql.annotations.annotationTypes.GraphQLTypeExtension;
import graphql.schema.DataFetchingEnvironment;
import graphql.ErrorType;
import org.jahia.modules.graphql.provider.dxm.BaseGqlClientException;
import org.jahia.modules.graphql.provider.dxm.DXGraphQLProvider;
import org.jahia.modules.graphql.provider.dxm.util.ContextUtil;
import org.jahia.osgi.BundleUtils;
import org.jahia.registries.ServicesRegistry;
import org.jahia.services.content.JCRNodeWrapper;
import org.jahia.services.content.JCRSessionFactory;
import org.jahia.services.content.JCRSessionWrapper;
import org.jahia.services.content.decorator.JCRSiteNode;
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
    private static final int MAX_PATH_LENGTH = 2048;
    @GraphQLField
    @GraphQLName("pageBuilderBackgroundJobs")
    @GraphQLDescription("Background jobs list for Page Builder dialog")
    public static List<GqlPageBuilderBackgroundJob> getPageBuilderBackgroundJobs(@GraphQLName("siteKey") String siteKey,
                                                                                  @GraphQLName("path") String path,
                                                                                  DataFetchingEnvironment environment) throws SchedulerException {
        // Scope the result to the sites the caller is actually authorized on. getVisibleJobs()
        // returns jobs for the WHOLE instance, so the returned set must be filtered against the caller's
        // authorization, never against the caller-supplied siteKey argument (which is attacker-controlled
        // and may simply be omitted). Only an unrestricted principal (root / server-wide grant) sees all.
        JobsAccess access = resolveJobsAccess(siteKey, path, environment);
        if (!access.isGranted()) {
            // BaseGqlClientException, not SecurityException. DXM cannot classify a raw JDK exception, so
            // it masked the denial as "Internal Server Error(s) while executing query" -- indistinguishable
            // from a broken backend, and not the "Permission denied" the README documents. Surfaced by the
            // e2e suite.
            throw new BaseGqlClientException(PERMISSION_DENIED_MESSAGE, ErrorType.DataFetchingException);
        }

        List<JobDetail> jobs = getVisibleJobs();
        java.util.stream.Stream<GqlPageBuilderBackgroundJob> stream =
                jobs.stream().map(GqlPageBuilderBackgroundJob::from);
        return stream.filter(job -> isVisibleTo(job, access)).collect(Collectors.toList());
    }

    /**
     * Whether a single job may be shown to a caller holding {@code access}. Package-private so the
     * scoping rule can be unit-tested without standing up a JCR session.
     */
    static boolean isVisibleTo(GqlPageBuilderBackgroundJob job, JobsAccess access) {
        if (!access.isGranted()) {
            return false;
        }
        if (access.isUnrestricted()) {
            return true;
        }
        // Scope on the job's full site set, not its display siteKey. Jahia leaves JOB_SITEKEY unset on
        // the modern publication path, so the set is extrapolated from publicationPaths — see
        // GqlPageBuilderBackgroundJob#siteKeysFromPublicationPaths.
        //
        // containsAll, not "any": a job touching several sites is only visible to a caller authorized on
        // ALL of them. Showing it on a single match would disclose that another site's content was
        // published in the same job. An empty set means the job is not attributable to any site
        // (instance-level, or no usable paths), so a site-scoped caller does not see it.
        Set<String> jobSiteKeys = job.getScopingSiteKeys();
        return !jobSiteKeys.isEmpty() && access.getAuthorizedSiteKeys().containsAll(jobSiteKeys);
    }

    /**
     * Backing value for the public {@code pageBuilderBackgroundJobsShowAll} field. Callers must gate this
     * themselves — see {@link #pageBuilderBackgroundJobsShowAll}.
     */
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
        // Gate this config probe behind the same jobs permission (it was ungated, so any
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
     * was the only thing left to filter on — and it is attacker-controlled.
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

            // When a specific site is requested the caller must hold
            // the permission on THAT site. The previous any-site / global-scope fallback let a grantee on
            // site A pass the probe for site B — the scope confusion this advisory is about.
            if (requestedSiteKey != null) {
                return isAuthorizedOnRequestedSite(context, requestedSiteKey, path, authorizedSiteKeys, environment)
                        ? JobsAccess.scopedTo(Collections.singleton(requestedSiteKey))
                        : JobsAccess.denied();
            }

            // An argument WAS supplied but did not resolve to a site (e.g. path="/modules"). Deny rather
            // than fall through to "no site requested". Silently ignoring a caller-supplied argument is
            // the root pattern behind every bypass found in this resolver: the answer stops corresponding to the
            // question asked. Found by the e2e suite, which showed path="/modules" being granted.
            if (normalize(siteKey) != null || normalize(path) != null) {
                LOGGER.debug("Background jobs request denied: siteKey={} path={} resolved to no site",
                        siteKey, path);
                return JobsAccess.denied();
            }

            // Nothing was requested: fall back to the caller's own authorized set, never the global list.
            return authorizedSiteKeys.isEmpty()
                    ? JobsAccess.denied()
                    : JobsAccess.scopedTo(authorizedSiteKeys);
        } catch (RepositoryException e) {
            throw new IllegalStateException("Unable to verify background jobs permission", e);
        }
    }

    /**
     * Whether the caller may see {@code requestedSiteKey}.
     *
     * <p>A grant deeper than the site node (e.g. on a single page) legitimately authorizes that page's
     * site, so {@code path} is consulted as a fallback — but ONLY when {@code path} actually belongs to
     * the requested site. {@code resolveRequestedSiteKey} lets {@code siteKey} win over {@code path}, so
     * checking the permission against {@code path} while scoping the answer to {@code siteKey} would let
     * a caller pair {@code siteKey=siteB} with {@code path=/sites/siteA/home} and read siteB's jobs off a
     * siteA grant — re-introducing the exact scope confusion this guard exists to prevent.
     */
    private static boolean isAuthorizedOnRequestedSite(PermissionContext context, String requestedSiteKey,
                                                       String path, Set<String> authorizedSiteKeys,
                                                       DataFetchingEnvironment environment) {
        if (authorizedSiteKeys.contains(requestedSiteKey)) {
            return true;
        }

        String normalizedPath = normalize(path);
        if (normalizedPath == null || context.session == null || !isCanonicalPath(normalizedPath)) {
            return false;
        }

        try {
            if (!context.session.nodeExists(normalizedPath)) {
                return false;
            }

            // Resolve the node FIRST, then derive the site from the node JCR actually returned.
            // Deciding the site by parsing the caller's string and then asking JCR about that same
            // string lets the two disagree: JCR collapses "." and ".." before resolving, so
            // "/sites/siteB/../siteA" parses as siteB but authorizes against siteA. That is the
            // permission-checked-on-one-resource, answer-scoped-to-another bug all over again.
            JCRNodeWrapper node = context.session.getNode(normalizedPath);
            JCRSiteNode site = node.getResolveSite();
            if (site == null || !requestedSiteKey.equals(site.getSiteKey())) {
                return false;
            }

            // Check the permission on the canonical path of the resolved node, never the raw input.
            return hasAnyPermissionOnPath(context.session, node.getPath(), environment);
        } catch (RepositoryException e) {
            LOGGER.warn("Unable to resolve requested path {} for background jobs; denying", normalizedPath, e);
            return false;
        }
    }

    /**
     * Rejects non-canonical paths up front: no "." or ".." segments, no empty segments, and no Jahia
     * deref separator. Defense in depth — {@link #isAuthorizedOnRequestedSite} no longer relies on
     * string parsing to decide the site, but refusing traversal input early keeps the JCR lookup
     * honest and bounds the input. Package-private for testing.
     */
    static boolean isCanonicalPath(String path) {
        if (path == null || !path.startsWith("/") || path.length() > MAX_PATH_LENGTH || path.contains("//")
                || path.contains("/@/")) {
            return false;
        }

        for (String segment : path.substring(1).split("/")) {
            if (".".equals(segment) || "..".equals(segment)) {
                return false;
            }
        }
        return true;
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
            // Both forms go through firstSegment so "sitea/" and "/sites/sitea/" normalize alike.
            // Leaving the plain branch unnormalized failed closed (it simply matched nothing), but the
            // asymmetry is a trap for anyone extending this later.
            return normalizedSiteKey.startsWith("/sites/")
                    ? firstSegment(normalizedSiteKey.substring("/sites/".length()))
                    : firstSegment(normalizedSiteKey);
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

            // Evaluate the permission ONLY against the caller's own session. The previous code
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

package org.jahia.support.modules.pagebuilder.backgroundjobs.graphql;

import org.jahia.services.scheduler.BackgroundJob;
import org.jahia.support.modules.pagebuilder.backgroundjobs.graphql.GqlJahiaAdminQueryBackgroundJobsExtension.JobsAccess;
import org.junit.Test;
import org.quartz.JobDataMap;
import org.quartz.JobDetail;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Regression tests for SEC-140 (GHSA-4vfj-8pfg-4xrp): a caller authorized on one site must not receive
 * publication-job metadata belonging to other sites, regardless of what {@code siteKey} they pass — or
 * whether they pass one at all.
 */
public class GqlJahiaAdminQueryBackgroundJobsExtensionTest {

    private static GqlPageBuilderBackgroundJob jobForSite(String siteKey) {
        JobDataMap map = new JobDataMap();
        if (siteKey != null) {
            map.put(BackgroundJob.JOB_SITEKEY, siteKey);
        }

        JobDetail detail = mock(JobDetail.class);
        when(detail.getJobDataMap()).thenReturn(map);
        when(detail.getName()).thenReturn("job-" + siteKey);
        when(detail.getGroup()).thenReturn("PublicationJob");
        return GqlPageBuilderBackgroundJob.from(detail);
    }

    private static Set<String> setOf(String... values) {
        return new HashSet<>(Arrays.asList(values));
    }

    // --- resolveRequestedSiteKey -------------------------------------------------------------

    @Test
    public void resolveRequestedSiteKey_plainSiteKey_returnsIt() {
        assertEquals("sitea", GqlJahiaAdminQueryBackgroundJobsExtension.resolveRequestedSiteKey("sitea", null));
    }

    @Test
    public void resolveRequestedSiteKey_siteKeyGivenAsPath_isUnwrapped() {
        assertEquals("sitea", GqlJahiaAdminQueryBackgroundJobsExtension.resolveRequestedSiteKey("/sites/sitea", null));
    }

    @Test
    public void resolveRequestedSiteKey_derivedFromPath_returnsSiteSegment() {
        assertEquals("sitea",
                GqlJahiaAdminQueryBackgroundJobsExtension.resolveRequestedSiteKey(null, "/sites/sitea/home/page1"));
    }

    @Test
    public void resolveRequestedSiteKey_siteKeyWins_overPath() {
        assertEquals("sitea",
                GqlJahiaAdminQueryBackgroundJobsExtension.resolveRequestedSiteKey("sitea", "/sites/pocsite/home"));
    }

    @Test
    public void resolveRequestedSiteKey_blankAndNonSitePaths_returnNull() {
        assertNull(GqlJahiaAdminQueryBackgroundJobsExtension.resolveRequestedSiteKey(null, null));
        assertNull(GqlJahiaAdminQueryBackgroundJobsExtension.resolveRequestedSiteKey("  ", "   "));
        assertNull(GqlJahiaAdminQueryBackgroundJobsExtension.resolveRequestedSiteKey(null, "/modules/foo"));
        assertNull(GqlJahiaAdminQueryBackgroundJobsExtension.resolveRequestedSiteKey(null, "/sites/"));
    }

    // --- isCanonicalPath ----------------------------------------------------------------------

    /**
     * The traversal bypass. Deciding the site by string-parsing the caller's path while asking JCR
     * about that same raw string lets the two disagree: JCR collapses "." and ".." before resolving,
     * so "/sites/siteB/../siteA" parses as siteB but authorizes against siteA — permission checked on
     * one resource, answer scoped to another. Authorization now derives the site from the RESOLVED
     * node; isCanonicalPath rejects such input up front as defense in depth.
     */
    @Test
    public void isCanonicalPath_rejectsParentTraversal() {
        assertFalse(GqlJahiaAdminQueryBackgroundJobsExtension.isCanonicalPath("/sites/siteb/../sitea"));
        assertFalse(GqlJahiaAdminQueryBackgroundJobsExtension.isCanonicalPath("/sites/siteb/./../sitea"));
        assertFalse(GqlJahiaAdminQueryBackgroundJobsExtension.isCanonicalPath("/sites/siteb/../../sites/sitea"));
        assertFalse(GqlJahiaAdminQueryBackgroundJobsExtension.isCanonicalPath("/sites/sitea/.."));
        assertFalse(GqlJahiaAdminQueryBackgroundJobsExtension.isCanonicalPath("/.."));
    }

    @Test
    public void isCanonicalPath_rejectsCurrentDirectorySegments() {
        assertFalse(GqlJahiaAdminQueryBackgroundJobsExtension.isCanonicalPath("/sites/./sitea"));
        assertFalse(GqlJahiaAdminQueryBackgroundJobsExtension.isCanonicalPath("/sites/sitea/."));
    }

    @Test
    public void isCanonicalPath_rejectsEmptySegmentsDerefAndOverlongInput() {
        assertFalse(GqlJahiaAdminQueryBackgroundJobsExtension.isCanonicalPath("/sites//sitea"));
        assertFalse(GqlJahiaAdminQueryBackgroundJobsExtension.isCanonicalPath("/sites/sitea/@/foo"));
        assertFalse(GqlJahiaAdminQueryBackgroundJobsExtension.isCanonicalPath("relative/path"));
        assertFalse(GqlJahiaAdminQueryBackgroundJobsExtension.isCanonicalPath(null));

        StringBuilder overlong = new StringBuilder("/sites/sitea");
        while (overlong.length() <= 2048) {
            overlong.append("/segment");
        }
        assertFalse(GqlJahiaAdminQueryBackgroundJobsExtension.isCanonicalPath(overlong.toString()));
    }

    @Test
    public void isCanonicalPath_acceptsOrdinarySitePaths() {
        assertTrue(GqlJahiaAdminQueryBackgroundJobsExtension.isCanonicalPath("/"));
        assertTrue(GqlJahiaAdminQueryBackgroundJobsExtension.isCanonicalPath("/sites/sitea"));
        assertTrue(GqlJahiaAdminQueryBackgroundJobsExtension.isCanonicalPath("/sites/sitea/home/page1"));
        // A segment merely CONTAINING dots is fine; only whole "." / ".." segments traverse.
        assertTrue(GqlJahiaAdminQueryBackgroundJobsExtension.isCanonicalPath("/sites/sitea/my.page"));
        assertTrue(GqlJahiaAdminQueryBackgroundJobsExtension.isCanonicalPath("/sites/sitea/..page"));
    }

    // --- isVisibleTo -------------------------------------------------------------------------

    @Test
    public void deniedAccess_hidesEverything() {
        JobsAccess denied = JobsAccess.denied();
        assertFalse(GqlJahiaAdminQueryBackgroundJobsExtension.isVisibleTo(jobForSite("sitea"), denied));
        assertFalse(GqlJahiaAdminQueryBackgroundJobsExtension.isVisibleTo(jobForSite(null), denied));
    }

    @Test
    public void unrestrictedAccess_seesEveryJobIncludingInstanceLevel() {
        JobsAccess unrestricted = JobsAccess.unrestricted();
        assertTrue(GqlJahiaAdminQueryBackgroundJobsExtension.isVisibleTo(jobForSite("sitea"), unrestricted));
        assertTrue(GqlJahiaAdminQueryBackgroundJobsExtension.isVisibleTo(jobForSite("pocsite"), unrestricted));
        assertTrue(GqlJahiaAdminQueryBackgroundJobsExtension.isVisibleTo(jobForSite(null), unrestricted));
    }

    /** The exact leak reproduced in the advisory: a sitea grantee must not see the pocsite job. */
    @Test
    public void scopedAccess_hidesOtherSitesJobs() {
        JobsAccess scoped = JobsAccess.scopedTo(Collections.singleton("sitea"));
        assertTrue(GqlJahiaAdminQueryBackgroundJobsExtension.isVisibleTo(jobForSite("sitea"), scoped));
        assertFalse(GqlJahiaAdminQueryBackgroundJobsExtension.isVisibleTo(jobForSite("pocsite"), scoped));
    }

    @Test
    public void scopedAccess_hidesInstanceLevelJobsWithoutSiteKey() {
        JobsAccess scoped = JobsAccess.scopedTo(Collections.singleton("sitea"));
        assertFalse(GqlJahiaAdminQueryBackgroundJobsExtension.isVisibleTo(jobForSite(null), scoped));
    }

    @Test
    public void scopedAccess_withSeveralSites_showsEachAuthorizedSite() {
        JobsAccess scoped = JobsAccess.scopedTo(setOf("sitea", "siteb"));
        assertTrue(GqlJahiaAdminQueryBackgroundJobsExtension.isVisibleTo(jobForSite("sitea"), scoped));
        assertTrue(GqlJahiaAdminQueryBackgroundJobsExtension.isVisibleTo(jobForSite("siteb"), scoped));
        assertFalse(GqlJahiaAdminQueryBackgroundJobsExtension.isVisibleTo(jobForSite("pocsite"), scoped));
    }

    @Test
    public void scopedAccess_withEmptySiteSet_hidesEverything() {
        JobsAccess scoped = JobsAccess.scopedTo(Collections.<String>emptySet());
        assertFalse(GqlJahiaAdminQueryBackgroundJobsExtension.isVisibleTo(jobForSite("sitea"), scoped));
    }

    @Test
    public void authorizedSiteKeys_areNotMutableByCallers() {
        Set<String> source = setOf("sitea");
        JobsAccess scoped = JobsAccess.scopedTo(source);
        source.add("pocsite");
        // The snapshot taken at construction must not follow later edits of the caller's set.
        assertFalse(scoped.getAuthorizedSiteKeys().contains("pocsite"));
        assertFalse(GqlJahiaAdminQueryBackgroundJobsExtension.isVisibleTo(jobForSite("pocsite"), scoped));
    }

    /** Case-sensitive comparison: a scoped grant for "sitea" must not match a job for "SiteA". */
    @Test
    public void isVisibleTo_isCaseSensitive_differentCasingSiteKeyIsHidden() {
        JobsAccess scoped = JobsAccess.scopedTo(Collections.singleton("sitea"));
        assertFalse(GqlJahiaAdminQueryBackgroundJobsExtension.isVisibleTo(jobForSite("SiteA"), scoped));
    }

    // --- resolveRequestedSiteKey: additional edge cases not covered above ---------------------

    /** siteKey given as a nested path (e.g. "/sites/x/y") unwraps to only the first segment. */
    @Test
    public void resolveRequestedSiteKey_siteKeyGivenAsNestedPath_unwrapsToFirstSegmentOnly() {
        assertEquals("sitea",
                GqlJahiaAdminQueryBackgroundJobsExtension.resolveRequestedSiteKey("/sites/sitea/somepage", null));
    }

    @Test
    public void resolveRequestedSiteKey_siteKeyGivenAsPathWithTrailingSlash_isUnwrapped() {
        assertEquals("sitea",
                GqlJahiaAdminQueryBackgroundJobsExtension.resolveRequestedSiteKey("/sites/sitea/", null));
    }

    /**
     * A plain siteKey and a "/sites/"-prefixed one normalize identically. The plain branch used to
     * return the value verbatim, so "sitea/" matched nothing and the request was denied; it failed
     * closed, but the asymmetry was a trap for later edits.
     */
    @Test
    public void resolveRequestedSiteKey_trailingSlash_isStrippedForBothForms() {
        assertEquals("sitea",
                GqlJahiaAdminQueryBackgroundJobsExtension.resolveRequestedSiteKey("sitea/", null));
        assertEquals("sitea",
                GqlJahiaAdminQueryBackgroundJobsExtension.resolveRequestedSiteKey("/sites/sitea/", null));
    }

    /** siteKey resolution does not normalize case; callers must match casing exactly. */
    @Test
    public void resolveRequestedSiteKey_isCaseSensitive_doesNotNormalizeCase() {
        assertEquals("SiteA", GqlJahiaAdminQueryBackgroundJobsExtension.resolveRequestedSiteKey("SiteA", null));
    }


    // --- JobsAccess factories: direct assertions on the returned state ------------------------

    @Test
    public void jobsAccessDenied_isNotGrantedNorUnrestrictedAndHasNoSiteKeys() {
        JobsAccess denied = JobsAccess.denied();
        assertFalse(denied.isGranted());
        assertFalse(denied.isUnrestricted());
        assertTrue(denied.getAuthorizedSiteKeys().isEmpty());
    }

    @Test
    public void jobsAccessUnrestricted_isGrantedAndUnrestrictedWithNoExplicitSiteKeys() {
        JobsAccess unrestricted = JobsAccess.unrestricted();
        assertTrue(unrestricted.isGranted());
        assertTrue(unrestricted.isUnrestricted());
        // Unrestricted access sees everything regardless of siteKey membership (see
        // unrestrictedAccess_seesEveryJobIncludingInstanceLevel), so the explicit set is empty.
        assertTrue(unrestricted.getAuthorizedSiteKeys().isEmpty());
    }

    @Test
    public void jobsAccessScopedTo_isGrantedButNotUnrestrictedAndExposesGivenSiteKeys() {
        JobsAccess scoped = JobsAccess.scopedTo(setOf("sitea", "siteb"));
        assertTrue(scoped.isGranted());
        assertFalse(scoped.isUnrestricted());
        assertEquals(setOf("sitea", "siteb"), scoped.getAuthorizedSiteKeys());
    }
}

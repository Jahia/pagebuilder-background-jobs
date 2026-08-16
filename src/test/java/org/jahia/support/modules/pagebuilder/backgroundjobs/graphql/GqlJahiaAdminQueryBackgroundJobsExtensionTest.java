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
}

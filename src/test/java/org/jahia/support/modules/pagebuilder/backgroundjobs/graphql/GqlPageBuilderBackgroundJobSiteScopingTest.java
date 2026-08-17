package org.jahia.support.modules.pagebuilder.backgroundjobs.graphql;

import org.jahia.services.scheduler.BackgroundJob;
import org.jahia.support.modules.pagebuilder.backgroundjobs.graphql.GqlJahiaAdminQueryBackgroundJobsExtension.JobsAccess;
import org.junit.Test;
import org.quartz.JobDataMap;
import org.quartz.JobDetail;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Site attribution when Jahia does not set BackgroundJob.JOB_SITEKEY.
 *
 * <p>createJahiaJob() writes only created/status/userkey/currentLocale, PublicationJob never sets
 * siteKey, and the only class in jahia-impl that writes "sitekey" is the legacy GWT PublicationHelper.
 * So publications triggered through jContent/GraphQL produce a job with no site attribution at all,
 * which made the dialog empty for every site-scoped caller.
 *
 * <p>ComplexPublicationServiceImpl -- the service behind that modern path -- does record
 * "publicationPaths", so the site set is extrapolated from those. These tests pin that extrapolation
 * and, importantly, the all-or-nothing visibility rule built on top of it.
 */
public class GqlPageBuilderBackgroundJobSiteScopingTest {

    private static final String PUBLICATION_PATHS = "publicationPaths";

    private static JobDataMap mapWith(String key, Object value) {
        JobDataMap map = new JobDataMap();
        if (value != null) {
            map.put(key, value);
        }
        return map;
    }

    private static GqlPageBuilderBackgroundJob jobFrom(JobDataMap map) {
        JobDetail detail = mock(JobDetail.class);
        when(detail.getJobDataMap()).thenReturn(map);
        when(detail.getName()).thenReturn("publication-job");
        when(detail.getGroup()).thenReturn("PublicationJob");
        return GqlPageBuilderBackgroundJob.from(detail);
    }

    private static Set<String> setOf(String... values) {
        return new LinkedHashSet<>(Arrays.asList(values));
    }

    // --- extracting site keys from publicationPaths -------------------------------------------

    @Test
    public void extractsSiteKeyFromAListOfPaths() {
        JobDataMap map = mapWith(PUBLICATION_PATHS, Arrays.asList("/sites/sitea/home", "/sites/sitea/home/page1"));
        assertEquals(setOf("sitea"), GqlPageBuilderBackgroundJob.siteKeysFromPublicationPaths(map));
    }

    @Test
    public void extractsEverySiteAJobTouches() {
        JobDataMap map = mapWith(PUBLICATION_PATHS, Arrays.asList("/sites/sitea/home", "/sites/siteb/home"));
        assertEquals(setOf("sitea", "siteb"), GqlPageBuilderBackgroundJob.siteKeysFromPublicationPaths(map));
    }

    @Test
    public void acceptsTheSitesNodeItself() {
        JobDataMap map = mapWith(PUBLICATION_PATHS, Collections.singletonList("/sites/sitea"));
        assertEquals(setOf("sitea"), GqlPageBuilderBackgroundJob.siteKeysFromPublicationPaths(map));
    }

    @Test
    public void ignoresPathsOutsideSites() {
        JobDataMap map = mapWith(PUBLICATION_PATHS,
                Arrays.asList("/modules/foo", "/settings/bar", "/", "/sites/", "/sites"));
        assertTrue(GqlPageBuilderBackgroundJob.siteKeysFromPublicationPaths(map).isEmpty());
    }

    /** The JobDataMap value is untyped, so array and single-string forms must not break extraction. */
    @Test
    public void acceptsArrayAndSingleStringForms() {
        JobDataMap arrayForm = mapWith(PUBLICATION_PATHS, new Object[]{"/sites/sitea/home", null, "/sites/siteb"});
        assertEquals(setOf("sitea", "siteb"), GqlPageBuilderBackgroundJob.siteKeysFromPublicationPaths(arrayForm));

        JobDataMap stringForm = mapWith(PUBLICATION_PATHS, "/sites/sitea/home");
        assertEquals(setOf("sitea"), GqlPageBuilderBackgroundJob.siteKeysFromPublicationPaths(stringForm));
    }

    @Test
    public void absentOrEmptyKeyYieldsNoSites() {
        assertTrue(GqlPageBuilderBackgroundJob.siteKeysFromPublicationPaths(new JobDataMap()).isEmpty());
        assertTrue(GqlPageBuilderBackgroundJob.siteKeysFromPublicationPaths(null).isEmpty());
        assertTrue(GqlPageBuilderBackgroundJob
                .siteKeysFromPublicationPaths(mapWith(PUBLICATION_PATHS, Collections.emptyList())).isEmpty());
    }

    @Test
    public void siteKeyExtractionIsCaseSensitiveAndDoesNotMatchOnPrefix() {
        JobDataMap map = mapWith(PUBLICATION_PATHS, Arrays.asList("/sites/SiteA/home", "/sites/siteabc/home"));
        assertEquals(setOf("SiteA", "siteabc"), GqlPageBuilderBackgroundJob.siteKeysFromPublicationPaths(map));
    }

    // --- choosing the scoping set --------------------------------------------------------------

    /**
     * An explicit JOB_SITEKEY is Jahia's own attribution and wins outright. It must NOT be merged with
     * path-derived keys: a stray path elsewhere would widen the set and make the job visible to callers
     * Jahia never attributed it to.
     */
    @Test
    public void explicitSiteKeyWinsAndIsNotWidenedByPaths() {
        assertEquals(Collections.singleton("sitea"),
                GqlPageBuilderBackgroundJob.resolveScopingSiteKeys("sitea", setOf("siteb", "sitec")));
    }

    @Test
    public void blankExplicitSiteKeyFallsBackToPaths() {
        assertEquals(setOf("siteb"), GqlPageBuilderBackgroundJob.resolveScopingSiteKeys("   ", setOf("siteb")));
        assertEquals(setOf("siteb"), GqlPageBuilderBackgroundJob.resolveScopingSiteKeys(null, setOf("siteb")));
    }

    @Test
    public void noAttributionAtAllYieldsAnEmptySet() {
        assertTrue(GqlPageBuilderBackgroundJob.resolveScopingSiteKeys(null, Collections.emptySet()).isEmpty());
    }

    // --- the display siteKey field -------------------------------------------------------------

    @Test
    public void displaySiteKeyIsExtrapolatedWhenUnambiguous() {
        GqlPageBuilderBackgroundJob job = jobFrom(
                mapWith(PUBLICATION_PATHS, Collections.singletonList("/sites/sitea/home")));
        assertEquals("sitea", job.getSiteKey());
        assertEquals(setOf("sitea"), job.getScopingSiteKeys());
    }

    /** A multi-site job has no single correct siteKey, so the display field stays null. */
    @Test
    public void displaySiteKeyIsNullForAMultiSiteJobButScopingStillSeesBoth() {
        GqlPageBuilderBackgroundJob job = jobFrom(
                mapWith(PUBLICATION_PATHS, Arrays.asList("/sites/sitea/home", "/sites/siteb/home")));
        assertNull(job.getSiteKey());
        assertEquals(setOf("sitea", "siteb"), job.getScopingSiteKeys());
    }

    @Test
    public void explicitSiteKeyIsUsedForDisplayWhenPresent() {
        JobDataMap map = mapWith(PUBLICATION_PATHS, Collections.singletonList("/sites/siteb/home"));
        map.put(BackgroundJob.JOB_SITEKEY, "sitea");
        GqlPageBuilderBackgroundJob job = jobFrom(map);
        assertEquals("sitea", job.getSiteKey());
        assertEquals(Collections.singleton("sitea"), job.getScopingSiteKeys());
    }

    // --- visibility: the security-relevant part ------------------------------------------------

    @Test
    public void extrapolatedJobIsNowVisibleToItsSiteScopedOwner() {
        // The regression this whole change is about: before extrapolation this job had no site at all,
        // so a sitea grantee saw an empty dialog.
        GqlPageBuilderBackgroundJob job = jobFrom(
                mapWith(PUBLICATION_PATHS, Collections.singletonList("/sites/sitea/home")));
        JobsAccess scoped = JobsAccess.scopedTo(Collections.singleton("sitea"));
        assertTrue(GqlJahiaAdminQueryBackgroundJobsExtension.isVisibleTo(job, scoped));
    }

    @Test
    public void extrapolatedJobStaysHiddenFromOtherSites() {
        GqlPageBuilderBackgroundJob job = jobFrom(
                mapWith(PUBLICATION_PATHS, Collections.singletonList("/sites/siteb/home")));
        JobsAccess scoped = JobsAccess.scopedTo(Collections.singleton("sitea"));
        assertFalse(GqlJahiaAdminQueryBackgroundJobsExtension.isVisibleTo(job, scoped));
    }

    /**
     * All-or-nothing. Showing a multi-site job on a single match would tell a sitea-only grantee that
     * siteb content was published in the same job -- the disclosure SEC-140 is about.
     */
    @Test
    public void multiSiteJobIsHiddenUnlessTheCallerHoldsEverySiteItTouches() {
        GqlPageBuilderBackgroundJob job = jobFrom(
                mapWith(PUBLICATION_PATHS, Arrays.asList("/sites/sitea/home", "/sites/siteb/home")));

        assertFalse("authorized on only one of the two sites",
                GqlJahiaAdminQueryBackgroundJobsExtension.isVisibleTo(job,
                        JobsAccess.scopedTo(Collections.singleton("sitea"))));
        assertTrue("authorized on both sites",
                GqlJahiaAdminQueryBackgroundJobsExtension.isVisibleTo(job,
                        JobsAccess.scopedTo(new HashSet<>(Arrays.asList("sitea", "siteb")))));
    }

    @Test
    public void unattributableJobIsHiddenFromScopedCallersButVisibleToUnrestricted() {
        GqlPageBuilderBackgroundJob job = jobFrom(mapWith(PUBLICATION_PATHS, Collections.singletonList("/settings")));
        assertFalse(GqlJahiaAdminQueryBackgroundJobsExtension.isVisibleTo(job,
                JobsAccess.scopedTo(Collections.singleton("sitea"))));
        assertTrue(GqlJahiaAdminQueryBackgroundJobsExtension.isVisibleTo(job, JobsAccess.unrestricted()));
    }

    @Test
    public void deniedAccessHidesAnExtrapolatedJobToo() {
        GqlPageBuilderBackgroundJob job = jobFrom(
                mapWith(PUBLICATION_PATHS, Collections.singletonList("/sites/sitea/home")));
        assertFalse(GqlJahiaAdminQueryBackgroundJobsExtension.isVisibleTo(job, JobsAccess.denied()));
    }
}

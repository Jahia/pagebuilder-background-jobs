package org.jahia.support.modules.pagebuilder.backgroundjobs.service;

import org.junit.Test;
import org.quartz.JobDetail;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Regression tests for {@link PageBuilderBackgroundJobsService}.
 *
 * <p>NOTE: {@link PageBuilderBackgroundJobsService#getVisibleJobs()} reaches the static
 * {@code ServicesRegistry.getInstance()} to obtain a {@code SchedulerService}. Plain mockito-core
 * (no mockito-inline / mockito-static, which this module deliberately does not depend on) cannot
 * mock static method calls, so that branch -- and by extension the showAllJobs-vs-filtered
 * dispatch inside it -- is NOT covered here. Only the statically-mockable pure helpers
 * ({@code toBoolean} via {@code updateConfiguration}, and {@code isPublicationJob}) are covered.
 */
public class PageBuilderBackgroundJobsServiceTest {

    private static PageBuilderBackgroundJobsService newService() {
        return new PageBuilderBackgroundJobsService();
    }

    private static Map<String, Object> propsWith(Object value) {
        Map<String, Object> props = new HashMap<>();
        props.put("showAllJobs", value);
        return props;
    }

    // --- toBoolean, exercised via updateConfiguration / isShowAllJobs ------------------------

    @Test
    public void updateConfiguration_booleanTrue_setsShowAllJobsTrue() {
        PageBuilderBackgroundJobsService service = newService();

        service.updateConfiguration(propsWith(Boolean.TRUE));

        assertTrue(service.isShowAllJobs());
    }

    @Test
    public void updateConfiguration_booleanFalse_setsShowAllJobsFalse() {
        PageBuilderBackgroundJobsService service = newService();

        service.updateConfiguration(propsWith(Boolean.FALSE));

        assertFalse(service.isShowAllJobs());
    }

    @Test
    public void updateConfiguration_stringTrueLowercase_setsShowAllJobsTrue() {
        PageBuilderBackgroundJobsService service = newService();

        service.updateConfiguration(propsWith("true"));

        assertTrue(service.isShowAllJobs());
    }

    @Test
    public void updateConfiguration_stringTrueUppercase_setsShowAllJobsTrue() {
        PageBuilderBackgroundJobsService service = newService();

        service.updateConfiguration(propsWith("TRUE"));

        assertTrue(service.isShowAllJobs());
    }

    @Test
    public void updateConfiguration_stringTrueWithSurroundingWhitespace_isTrimmedAndParsedAsTrue() {
        PageBuilderBackgroundJobsService service = newService();

        service.updateConfiguration(propsWith(" true "));

        assertTrue(service.isShowAllJobs());
    }

    @Test
    public void updateConfiguration_stringFalse_setsShowAllJobsFalse() {
        PageBuilderBackgroundJobsService service = newService();
        // Start from true so the assertion actually proves the value was flipped, not left at
        // the field's default.
        service.updateConfiguration(propsWith(Boolean.TRUE));

        service.updateConfiguration(propsWith("false"));

        assertFalse(service.isShowAllJobs());
    }

    @Test
    public void updateConfiguration_nullPropertiesMap_defaultsToFalse() {
        PageBuilderBackgroundJobsService service = newService();
        service.updateConfiguration(propsWith(Boolean.TRUE));

        service.updateConfiguration(null);

        assertFalse(service.isShowAllJobs());
    }

    @Test
    public void updateConfiguration_showAllJobsKeyAbsent_defaultsToFalse() {
        PageBuilderBackgroundJobsService service = newService();
        service.updateConfiguration(propsWith(Boolean.TRUE));

        service.updateConfiguration(Collections.emptyMap());

        assertFalse(service.isShowAllJobs());
    }

    // Non-boolean, non-"true"/"false" types are coerced via Boolean.parseBoolean(String), which
    // only recognizes the literal (case-insensitive) string "true" as true -- an Integer 1 is
    // NOT treated as truthy here, unlike some other languages' conventions.
    @Test
    public void updateConfiguration_nonBooleanNonStringType_isNotTreatedAsTrue() {
        PageBuilderBackgroundJobsService service = newService();

        service.updateConfiguration(propsWith(1));

        assertFalse(service.isShowAllJobs());
    }

    // --- isPublicationJob (substring match on the job's group) --------------------------------

    @Test
    public void isPublicationJob_nullJobDetail_returnsFalse() {
        assertFalse(PageBuilderBackgroundJobsService.isPublicationJob(null));
    }

    @Test
    public void isPublicationJob_nullGroup_returnsFalse() {
        JobDetail detail = mock(JobDetail.class);
        when(detail.getGroup()).thenReturn(null);

        assertFalse(PageBuilderBackgroundJobsService.isPublicationJob(detail));
    }

    @Test
    public void isPublicationJob_exactGroupMatch_returnsTrue() {
        JobDetail detail = mock(JobDetail.class);
        when(detail.getGroup()).thenReturn("PublicationJob");

        assertTrue(PageBuilderBackgroundJobsService.isPublicationJob(detail));
    }

    // The match is deliberately substring-based (String#contains), not an exact-equality check,
    // so any group merely CONTAINING the token is treated as a publication job.
    @Test
    public void isPublicationJob_groupContainingToken_returnsTrue() {
        JobDetail detail = mock(JobDetail.class);
        when(detail.getGroup()).thenReturn("scheduled/PublicationJob/site1");

        assertTrue(PageBuilderBackgroundJobsService.isPublicationJob(detail));
    }

    @Test
    public void isPublicationJob_nonMatchingGroup_returnsFalse() {
        JobDetail detail = mock(JobDetail.class);
        when(detail.getGroup()).thenReturn("SomeOtherJobGroup");

        assertFalse(PageBuilderBackgroundJobsService.isPublicationJob(detail));
    }
}

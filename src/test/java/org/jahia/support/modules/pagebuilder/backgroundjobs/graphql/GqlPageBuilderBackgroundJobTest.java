package org.jahia.support.modules.pagebuilder.backgroundjobs.graphql;

import org.jahia.services.scheduler.BackgroundJob;
import org.junit.Test;
import org.quartz.JobDataMap;
import org.quartz.JobDetail;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Calendar;
import java.util.Date;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Regression tests for {@link GqlPageBuilderBackgroundJob}: date/timestamp normalization, the
 * DATE_KEYS lookup precedence, numeric coercion and the status-to-state collapse. All private
 * helpers are exercised indirectly through the public {@link GqlPageBuilderBackgroundJob#from}
 * factory, which is the only entry point the class exposes.
 */
public class GqlPageBuilderBackgroundJobTest {

    private static JobDetail jobDetailWithMap(JobDataMap map) {
        JobDetail detail = mock(JobDetail.class);
        when(detail.getJobDataMap()).thenReturn(map);
        when(detail.getName()).thenReturn("job-name");
        when(detail.getGroup()).thenReturn("job-group");
        when(detail.getDescription()).thenReturn("job-description");
        return detail;
    }

    private static GqlPageBuilderBackgroundJob fromMap(JobDataMap map) {
        return GqlPageBuilderBackgroundJob.from(jobDetailWithMap(map));
    }

    // --- normalizeTimestamp boundary (seconds vs millis heuristic) ----------------------------

    @Test
    public void from_dateValueJustBelowBoundary_treatedAsSecondsAndMultipliedByThousand() {
        JobDataMap map = new JobDataMap();
        // 99999999999 < 100000000000 -> treated as seconds.
        map.put(BackgroundJob.JOB_CREATED, 99999999999L);

        GqlPageBuilderBackgroundJob job = fromMap(map);

        assertEquals(Long.valueOf(99999999999000L), job.getCreatedTimestamp());
        assertEquals("99999999999000", job.getCreatedRaw());
    }

    @Test
    public void from_dateValueAtBoundary_treatedAsAlreadyMillis() {
        JobDataMap map = new JobDataMap();
        // 100000000000 is NOT < 100000000000 -> treated as already-millis, left untouched.
        map.put(BackgroundJob.JOB_CREATED, 100000000000L);

        GqlPageBuilderBackgroundJob job = fromMap(map);

        assertEquals(Long.valueOf(100000000000L), job.getCreatedTimestamp());
        assertEquals("100000000000", job.getCreatedRaw());
    }

    @Test
    public void from_zeroOrNegativeDateValue_yieldsNullTimestampAndRaw() {
        JobDataMap zeroMap = new JobDataMap();
        zeroMap.put(BackgroundJob.JOB_CREATED, 0L);
        GqlPageBuilderBackgroundJob zeroJob = fromMap(zeroMap);
        assertNull(zeroJob.getCreatedTimestamp());
        assertNull(zeroJob.getCreatedRaw());

        JobDataMap negativeMap = new JobDataMap();
        negativeMap.put(BackgroundJob.JOB_CREATED, -5L);
        GqlPageBuilderBackgroundJob negativeJob = fromMap(negativeMap);
        assertNull(negativeJob.getCreatedTimestamp());
        assertNull(negativeJob.getCreatedRaw());
    }

    // --- Date / millis / seconds / formatted-string all resolve to the SAME instant ----------

    @Test
    public void from_dateMillisSecondsAndFormattedString_allResolveToSameInstant() {
        Calendar calendar = Calendar.getInstance();
        calendar.set(2024, Calendar.JANUARY, 15, 10, 30, 0);
        calendar.set(Calendar.MILLISECOND, 0);
        Date date = calendar.getTime();
        long millis = date.getTime();
        long seconds = millis / 1000L;
        String formatted = "15.01.2024 10:30:00";

        JobDataMap dateMap = new JobDataMap();
        dateMap.put(BackgroundJob.JOB_CREATED, date);
        JobDataMap millisMap = new JobDataMap();
        millisMap.put(BackgroundJob.JOB_CREATED, millis);
        JobDataMap secondsMap = new JobDataMap();
        secondsMap.put(BackgroundJob.JOB_CREATED, seconds);
        JobDataMap stringMap = new JobDataMap();
        stringMap.put(BackgroundJob.JOB_CREATED, formatted);

        assertEquals(Long.valueOf(millis), fromMap(dateMap).getCreatedTimestamp());
        assertEquals(Long.valueOf(millis), fromMap(millisMap).getCreatedTimestamp());
        assertEquals(Long.valueOf(millis), fromMap(secondsMap).getCreatedTimestamp());
        assertEquals(Long.valueOf(millis), fromMap(stringMap).getCreatedTimestamp());
    }

    // --- dd.MM.yyyy HH:mm formatter (no seconds) + unparseable garbage ------------------------

    @Test
    public void from_stringWithoutSeconds_parsedByFallbackFormatter() {
        LocalDateTime expected = LocalDateTime.of(2024, 1, 15, 10, 30, 0);
        long expectedMillis = expected.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();

        JobDataMap map = new JobDataMap();
        map.put(BackgroundJob.JOB_CREATED, "15.01.2024 10:30");

        GqlPageBuilderBackgroundJob job = fromMap(map);

        assertEquals(Long.valueOf(expectedMillis), job.getCreatedTimestamp());
    }

    @Test
    public void from_unparseableDateString_yieldsNullTimestampButKeepsRawValue() {
        JobDataMap map = new JobDataMap();
        map.put(BackgroundJob.JOB_CREATED, "not-a-date");

        GqlPageBuilderBackgroundJob job = fromMap(map);

        assertNull(job.getCreatedTimestamp());
        // toRawString falls through to String.valueOf for non-Date/non-Number values, so the raw
        // (unparseable) value is preserved even though no timestamp could be derived from it.
        assertEquals("not-a-date", job.getCreatedRaw());
    }

    // --- getFirstNonNullDateValue precedence (follows DATE_KEYS array order) -----------------

    @Test
    public void from_multipleDateKeysPresent_earliestArrayEntryWins() {
        JobDataMap map = new JobDataMap();
        // Per DATE_KEYS order: JOB_CREATED ("created") is index 0, "createdAt" is index 7.
        // JOB_CREATED must win even though "createdAt" is also present.
        map.put(BackgroundJob.JOB_CREATED, 1_700_000_000_000L);
        map.put("createdAt", 1_800_000_000_000L);

        GqlPageBuilderBackgroundJob job = fromMap(map);

        assertEquals(Long.valueOf(1_700_000_000_000L), job.getCreatedTimestamp());
    }

    @Test
    public void from_dateKeyBeatsLaterFireTimeAndStartTimeKeys() {
        JobDataMap map = new JobDataMap();
        // Per DATE_KEYS order: "date" is index 15, "fireTime" is index 16, "startTime" is index 19.
        // "date" must win when all three are present.
        map.put("startTime", 1_800_000_000_000L);
        map.put("fireTime", 1_750_000_000_000L);
        map.put("date", 1_700_000_000_000L);

        GqlPageBuilderBackgroundJob job = fromMap(map);

        assertEquals(Long.valueOf(1_700_000_000_000L), job.getCreatedTimestamp());
    }

    @Test
    public void from_onlyLastResortKeyPresent_jobEndIsUsed() {
        JobDataMap map = new JobDataMap();
        // JOB_END ("end") is the very last entry in DATE_KEYS: only reached when nothing else matches.
        map.put(BackgroundJob.JOB_END, 1_650_000_000_000L);

        GqlPageBuilderBackgroundJob job = fromMap(map);

        assertEquals(Long.valueOf(1_650_000_000_000L), job.getCreatedTimestamp());
    }

    @Test
    public void from_noDateKeysPresent_createdTimestampAndRawAreNull() {
        JobDataMap map = new JobDataMap();

        GqlPageBuilderBackgroundJob job = fromMap(map);

        assertNull(job.getCreatedTimestamp());
        assertNull(job.getCreatedRaw());
    }

    // --- getMapValueAsLong (exercised via duration) -------------------------------------------

    @Test
    public void from_durationAsNumericString_parsesToLong() {
        JobDataMap map = new JobDataMap();
        map.put(BackgroundJob.JOB_DURATION, "1500");

        assertEquals(Long.valueOf(1500L), fromMap(map).getDuration());
    }

    @Test
    public void from_durationAsNonNumericString_fallsBackToDefault() {
        JobDataMap map = new JobDataMap();
        map.put(BackgroundJob.JOB_DURATION, "not-a-number");

        assertEquals(Long.valueOf(-1L), fromMap(map).getDuration());
    }

    @Test
    public void from_durationAsNumberInstance_isReturnedDirectly() {
        JobDataMap map = new JobDataMap();
        map.put(BackgroundJob.JOB_DURATION, 42);

        assertEquals(Long.valueOf(42L), fromMap(map).getDuration());
    }

    @Test
    public void from_durationKeyAbsent_defaultsToMinusOne() {
        JobDataMap map = new JobDataMap();

        assertEquals(Long.valueOf(-1L), fromMap(map).getDuration());
    }

    @Test
    public void from_durationValueExplicitlyNull_defaultsToMinusOne() {
        JobDataMap map = new JobDataMap();
        map.put(BackgroundJob.JOB_DURATION, null);

        assertEquals(Long.valueOf(-1L), fromMap(map).getDuration());
    }

    // --- status -> state mapping ---------------------------------------------------------------

    @Test
    public void from_executingStatus_mapsToStartedState() {
        JobDataMap map = new JobDataMap();
        map.put(BackgroundJob.JOB_STATUS, BackgroundJob.STATUS_EXECUTING);

        GqlPageBuilderBackgroundJob job = fromMap(map);

        assertEquals("EXECUTING", job.getJobStatus());
        assertEquals("STARTED", job.getJobState());
    }

    // Current behaviour pinned as-is: ANY non-"EXECUTING" status collapses to "FINISHED", even
    // "scheduled" (a job that hasn't run yet) and "failed" (a job that errored). This looks
    // questionable for SCHEDULED in particular -- a job that is merely queued is reported as
    // "FINISHED" -- but that is the current implementation and this test only pins it.
    @Test
    public void from_scheduledStatus_collapsesToFinishedState() {
        JobDataMap map = new JobDataMap();
        map.put(BackgroundJob.JOB_STATUS, BackgroundJob.STATUS_SCHEDULED);

        GqlPageBuilderBackgroundJob job = fromMap(map);

        assertEquals("SCHEDULED", job.getJobStatus());
        assertEquals("FINISHED", job.getJobState());
    }

    @Test
    public void from_failedStatus_collapsesToFinishedState() {
        JobDataMap map = new JobDataMap();
        map.put(BackgroundJob.JOB_STATUS, BackgroundJob.STATUS_FAILED);

        GqlPageBuilderBackgroundJob job = fromMap(map);

        assertEquals("FAILED", job.getJobStatus());
        assertEquals("FINISHED", job.getJobState());
    }

    // --- normalizeUpperCase ---------------------------------------------------------------------

    @Test
    public void from_statusKeyAbsent_defaultsToUnknownStatusAndFinishedState() {
        JobDataMap map = new JobDataMap();

        GqlPageBuilderBackgroundJob job = fromMap(map);

        assertEquals("UNKNOWN", job.getJobStatus());
        assertEquals("FINISHED", job.getJobState());
    }

    @Test
    public void from_blankStatus_defaultsToUnknown() {
        JobDataMap map = new JobDataMap();
        map.put(BackgroundJob.JOB_STATUS, "   ");

        assertEquals("UNKNOWN", fromMap(map).getJobStatus());
    }

    @Test
    public void from_lowercaseStatusWithSurroundingWhitespace_isUppercasedAndTrimmed() {
        JobDataMap map = new JobDataMap();
        map.put(BackgroundJob.JOB_STATUS, "  executing  ");

        assertEquals("EXECUTING", fromMap(map).getJobStatus());
    }
}

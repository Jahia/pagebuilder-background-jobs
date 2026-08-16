package org.jahia.support.modules.pagebuilder.backgroundjobs.graphql;

import graphql.annotations.annotationTypes.GraphQLDescription;
import graphql.annotations.annotationTypes.GraphQLField;
import org.jahia.services.scheduler.BackgroundJob;
import org.quartz.JobDataMap;
import org.quartz.JobDetail;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Date;

@GraphQLDescription("Background job representation for Page Builder dialog")
public class GqlPageBuilderBackgroundJob {
    private static final String[] DATE_KEYS = {
            BackgroundJob.JOB_CREATED,
            "creationTime",
            "creation_time",
            "creationDate",
            "creationDateTime",
            "creationTimestamp",
            "createdOn",
            "createdAt",
            "createdTs",
            "createdDate",
            "createdTime",
            "createTime",
            "scheduleDate",
            "scheduledDate",
            "scheduledTime",
            "date",
            "fireTime",
            "nextFireTime",
            "previousFireTime",
            "startTime",
            "startDate",
            BackgroundJob.JOB_BEGIN,
            BackgroundJob.JOB_END
    };

    private static final DateTimeFormatter[] LOCAL_DATE_FORMATTERS = {
            DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss"),
            DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")
    };

    private String name;
    private String group;
    private String jobDescription;
    private Long duration;
    private String jobState;
    private String jobStatus;
    private String siteKey;
    private String userKey;
    private String createdRaw;
    private Long createdTimestamp;

    private GqlPageBuilderBackgroundJob() {
        // Instances are created exclusively through the from(JobDetail) factory.
    }

    public static GqlPageBuilderBackgroundJob from(JobDetail jobDetail) {
        JobDataMap map = jobDetail.getJobDataMap();
        String status = normalizeUpperCase(getMapValueAsString(map, BackgroundJob.JOB_STATUS), "UNKNOWN");
        String state = "EXECUTING".equals(status) ? "STARTED" : "FINISHED";
        Long duration = getMapValueAsLong(map, BackgroundJob.JOB_DURATION);
        if (duration == null) {
            duration = -1L;
        }

        Object createdValue = getFirstNonNullDateValue(map);

        GqlPageBuilderBackgroundJob job = new GqlPageBuilderBackgroundJob();
        job.name = jobDetail.getName();
        job.group = jobDetail.getGroup();
        job.jobDescription = jobDetail.getDescription();
        job.duration = duration;
        job.jobState = state;
        job.jobStatus = status;
        job.siteKey = getMapValueAsString(map, BackgroundJob.JOB_SITEKEY);
        job.userKey = getMapValueAsString(map, BackgroundJob.JOB_USERKEY);
        job.createdRaw = toRawString(createdValue);
        job.createdTimestamp = toTimestamp(createdValue);
        return job;
    }

    private static String normalizeUpperCase(String value, String defaultValue) {
        if (value == null || value.trim().isEmpty()) {
            return defaultValue;
        }
        return value.trim().toUpperCase();
    }

    private static String getMapValueAsString(JobDataMap map, String key) {
        if (!map.containsKey(key)) {
            return null;
        }
        Object value = map.get(key);
        return value == null ? null : String.valueOf(value);
    }

    private static Long getMapValueAsLong(JobDataMap map, String key) {
        if (!map.containsKey(key)) {
            return null;
        }
        Object value = map.get(key);
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        if (value instanceof String) {
            String asString = ((String) value).trim();
            if (asString.matches("^\\d+$")) {
                return Long.parseLong(asString);
            }
        }
        return null;
    }

    private static Object getFirstNonNullDateValue(JobDataMap map) {
        for (String key : DATE_KEYS) {
            if (map.containsKey(key)) {
                Object value = map.get(key);
                if (value != null) {
                    return value;
                }
            }
        }
        return null;
    }

    private static String toRawString(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Date) {
            return String.valueOf(((Date) value).getTime());
        }
        if (value instanceof Number) {
            Long normalized = normalizeTimestamp(((Number) value).longValue());
            return normalized == null ? null : String.valueOf(normalized);
        }
        return String.valueOf(value);
    }

    private static Long toTimestamp(Object value) {
        if (value == null) {
            return null;
        }

        if (value instanceof Date) {
            return ((Date) value).getTime();
        }

        if (value instanceof Number) {
            return normalizeTimestamp(((Number) value).longValue());
        }

        String asString = String.valueOf(value).trim();
        if (asString.isEmpty()) {
            return null;
        }

        if (asString.matches("^\\d+$")) {
            return normalizeTimestamp(Long.parseLong(asString));
        }

        for (DateTimeFormatter formatter : LOCAL_DATE_FORMATTERS) {
            try {
                LocalDateTime parsed = LocalDateTime.parse(asString, formatter);
                return parsed.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
            } catch (DateTimeParseException e) {
                // Try next formatter.
            }
        }

        return null;
    }

    private static Long normalizeTimestamp(long value) {
        if (value <= 0) {
            return null;
        }

        if (value < 100000000000L) {
            return value * 1000L;
        }

        return value;
    }

    @GraphQLField
    public String getName() {
        return name;
    }

    @GraphQLField
    public String getGroup() {
        return group;
    }

    @GraphQLField
    public String getJobDescription() {
        return jobDescription;
    }

    @GraphQLField
    public Long getDuration() {
        return duration;
    }

    @GraphQLField
    public String getJobState() {
        return jobState;
    }

    @GraphQLField
    public String getJobStatus() {
        return jobStatus;
    }

    @GraphQLField
    public String getSiteKey() {
        return siteKey;
    }

    @GraphQLField
    public String getUserKey() {
        return userKey;
    }

    @GraphQLField
    public String getCreatedRaw() {
        return createdRaw;
    }

    @GraphQLField
    public Long getCreatedTimestamp() {
        return createdTimestamp;
    }
}

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

    private final String name;
    private final String group;
    private final String jobDescription;
    private final Long duration;
    private final String jobState;
    private final String jobStatus;
    private final String siteKey;
    private final String userKey;
    private final String createdRaw;
    private final Long createdTimestamp;

    private GqlPageBuilderBackgroundJob(String name, String group, String jobDescription, Long duration, String jobState, String jobStatus, String siteKey, String userKey, String createdRaw, Long createdTimestamp) {
        this.name = name;
        this.group = group;
        this.jobDescription = jobDescription;
        this.duration = duration;
        this.jobState = jobState;
        this.jobStatus = jobStatus;
        this.siteKey = siteKey;
        this.userKey = userKey;
        this.createdRaw = createdRaw;
        this.createdTimestamp = createdTimestamp;
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
        String createdRaw = toRawString(createdValue);
        Long createdTimestamp = toTimestamp(createdValue);

        return new GqlPageBuilderBackgroundJob(
                jobDetail.getName(),
                jobDetail.getGroup(),
                jobDetail.getDescription(),
                duration,
                state,
                status,
                getMapValueAsString(map, BackgroundJob.JOB_SITEKEY),
                getMapValueAsString(map, BackgroundJob.JOB_USERKEY),
                createdRaw,
                createdTimestamp
        );
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

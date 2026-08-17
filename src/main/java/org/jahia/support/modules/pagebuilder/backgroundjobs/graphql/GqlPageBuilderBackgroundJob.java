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
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

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

    /**
     * Every site this job touches, used for authorization scoping rather than display.
     *
     * <p>Kept separate from {@link #siteKey} because a job can touch more than one site, and a
     * site-scoped caller must not see such a job unless it is authorized on ALL of them — otherwise the
     * mere presence of the job discloses that content in another site was published alongside theirs.
     * Empty means "not attributable to any site", which for a scoped caller means not visible.
     */
    private Set<String> scopingSiteKeys = Collections.emptySet();

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
        // Jahia does not populate JOB_SITEKEY on the modern publication path: createJahiaJob() writes
        // only created/status/userkey/currentLocale, PublicationJob never sets it, and the only class in
        // jahia-impl that does is the legacy GWT PublicationHelper. ComplexPublicationServiceImpl -- the
        // service behind jContent/GraphQL publication -- does however record "publicationPaths", so the
        // site can be extrapolated from those when the explicit key is missing.
        String explicitSiteKey = getMapValueAsString(map, BackgroundJob.JOB_SITEKEY);
        Set<String> pathSiteKeys = siteKeysFromPublicationPaths(map);

        job.scopingSiteKeys = resolveScopingSiteKeys(explicitSiteKey, pathSiteKeys);
        // Display value: the explicit key when present, otherwise the extrapolated one -- but only when
        // it is unambiguous. A job spanning several sites has no single correct answer, so it stays null
        // rather than naming one arbitrarily. Scoping still uses the full set above.
        job.siteKey = explicitSiteKey != null ? explicitSiteKey
                : (pathSiteKeys.size() == 1 ? pathSiteKeys.iterator().next() : null);
        job.userKey = getMapValueAsString(map, BackgroundJob.JOB_USERKEY);
        job.createdRaw = toRawString(createdValue);
        job.createdTimestamp = toTimestamp(createdValue);
        return job;
    }

    /** Key written by ComplexPublicationServiceImpl; equals PublicationJob.PUBLICATION_PATHS. */
    private static final String PUBLICATION_PATHS_KEY = "publicationPaths";
    private static final String SITES_PREFIX = "/sites/";

    /**
     * Site keys extracted from the job's {@code publicationPaths}. These are canonical, server-produced
     * JCR paths — not caller input — so unlike the request-argument handling in the resolver there is no
     * traversal concern here. Paths outside {@code /sites/} contribute nothing.
     */
    static Set<String> siteKeysFromPublicationPaths(JobDataMap map) {
        if (map == null || !map.containsKey(PUBLICATION_PATHS_KEY)) {
            return Collections.emptySet();
        }

        Set<String> siteKeys = new LinkedHashSet<>();
        for (String path : toStringCollection(map.get(PUBLICATION_PATHS_KEY))) {
            String siteKey = siteKeyOfPath(path);
            if (siteKey != null) {
                siteKeys.add(siteKey);
            }
        }
        return siteKeys;
    }

    /** {@code /sites/foo/home/page} -> {@code foo}; anything not under /sites/ -> null. */
    private static String siteKeyOfPath(String path) {
        if (path == null) {
            return null;
        }
        String trimmed = path.trim();
        if (!trimmed.startsWith(SITES_PREFIX)) {
            return null;
        }

        String remainder = trimmed.substring(SITES_PREFIX.length());
        int slash = remainder.indexOf('/');
        String siteKey = slash == -1 ? remainder : remainder.substring(0, slash);
        return siteKey.isEmpty() ? null : siteKey;
    }

    /** The JobDataMap value is an untyped Object; accept a collection, an array, or a single string. */
    private static Collection<String> toStringCollection(Object value) {
        if (value == null) {
            return Collections.emptyList();
        }
        if (value instanceof Collection) {
            List<String> values = new ArrayList<>();
            for (Object element : (Collection<?>) value) {
                if (element != null) {
                    values.add(String.valueOf(element));
                }
            }
            return values;
        }
        if (value instanceof Object[]) {
            List<String> values = new ArrayList<>();
            for (Object element : (Object[]) value) {
                if (element != null) {
                    values.add(String.valueOf(element));
                }
            }
            return values;
        }
        return Collections.singletonList(String.valueOf(value));
    }

    /**
     * The set a site-scoped caller is checked against. An explicit JOB_SITEKEY wins, because that is
     * Jahia's own attribution; otherwise the extrapolated set is used. Deliberately does NOT merge the
     * two: if Jahia says the job belongs to site A, a stray path elsewhere must not widen the set and
     * make the job visible to more callers.
     */
    static Set<String> resolveScopingSiteKeys(String explicitSiteKey, Set<String> pathSiteKeys) {
        if (explicitSiteKey != null && !explicitSiteKey.trim().isEmpty()) {
            return Collections.singleton(explicitSiteKey.trim());
        }
        return pathSiteKeys.isEmpty() ? Collections.emptySet() : Collections.unmodifiableSet(pathSiteKeys);
    }

    /** Package-private: consumed by the resolver's visibility check, not exposed over GraphQL. */
    Set<String> getScopingSiteKeys() {
        return scopingSiteKeys;
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

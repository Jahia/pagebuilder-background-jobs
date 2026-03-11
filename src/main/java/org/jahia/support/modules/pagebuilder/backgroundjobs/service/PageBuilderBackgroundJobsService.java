package org.jahia.support.modules.pagebuilder.backgroundjobs.service;

import org.jahia.registries.ServicesRegistry;
import org.jahia.services.scheduler.SchedulerService;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Modified;
import org.quartz.JobDetail;
import org.quartz.SchedulerException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Component(service = PageBuilderBackgroundJobsService.class, immediate = true, configurationPid = PageBuilderBackgroundJobsService.CONFIG_PID)
public class PageBuilderBackgroundJobsService {
    public static final String CONFIG_PID = "org.jahia.support.modules.pagebuilder.backgroundjobs";
    private static final Logger LOGGER = LoggerFactory.getLogger(PageBuilderBackgroundJobsService.class);
    private static final String SHOW_ALL_JOBS_PROPERTY = "showAllJobs";
    private static final String PUBLICATION_JOB_TOKEN = "PublicationJob";

    private volatile boolean showAllJobs;

    @Activate
    @Modified
    public void updateConfiguration(Map<String, Object> properties) {
        showAllJobs = toBoolean(properties != null ? properties.get(SHOW_ALL_JOBS_PROPERTY) : null, false);
        LOGGER.info("Page Builder background jobs configuration updated: {}={}", SHOW_ALL_JOBS_PROPERTY, showAllJobs);
    }

    public boolean isShowAllJobs() {
        return showAllJobs;
    }

    public List<JobDetail> getVisibleJobs() throws SchedulerException {
        SchedulerService schedulerService = ServicesRegistry.getInstance().getSchedulerService();
        if (schedulerService == null) {
            return Collections.emptyList();
        }

        List<JobDetail> jobs = schedulerService.getAllJobs();
        if (showAllJobs) {
            return jobs;
        }

        return jobs.stream()
                .filter(PageBuilderBackgroundJobsService::isPublicationJob)
                .collect(Collectors.toList());
    }

    public static boolean isPublicationJob(JobDetail jobDetail) {
        if (jobDetail == null) {
            return false;
        }

        String group = jobDetail.getGroup();
        return group != null && group.contains(PUBLICATION_JOB_TOKEN);
    }

    private static boolean toBoolean(Object value, boolean defaultValue) {
        if (value == null) {
            return defaultValue;
        }

        if (value instanceof Boolean) {
            return (Boolean) value;
        }

        return Boolean.parseBoolean(String.valueOf(value).trim());
    }
}

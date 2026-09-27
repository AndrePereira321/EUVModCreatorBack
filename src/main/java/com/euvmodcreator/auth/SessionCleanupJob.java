package com.euvmodcreator.auth;

import com.euvmodcreator.auth.repository.UserSessionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.CronTask;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.ZoneOffset;

@Slf4j
@Component
@EnableConfigurationProperties(SessionCleanupProperties.class)
@RequiredArgsConstructor
class SessionCleanupJob implements SchedulingConfigurer {

    private final UserSessionRepository userSessionRepository;

    private final SessionCleanupProperties sessionCleanupProperties;

    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        String cron = sessionCleanupProperties.cron();
        if (!ScheduledTaskRegistrar.CRON_DISABLED.equals(cron)) {
            registrar.addCronTask(new CronTask(this::deleteEndedSessions, new CronTrigger(cron, ZoneOffset.UTC)));
        }
    }

    void deleteEndedSessions() {
        Instant cutoff = Instant.now().minus(sessionCleanupProperties.retention());
        int deleted = userSessionRepository.deleteEndedBefore(cutoff);
        log.info("Deleted {} sessions that expired or were revoked before {}", deleted, cutoff);
    }

}

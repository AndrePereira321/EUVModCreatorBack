package com.euvmodcreator.auth;

import com.euvmodcreator.auth.repository.UserSessionRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.config.CronTask;
import org.springframework.scheduling.config.ScheduledTask;
import org.springframework.scheduling.config.ScheduledTaskHolder;
import org.springframework.scheduling.support.SimpleTriggerContext;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

// Integration tests switch the cron off, so without these a broken default would first fail when the app starts.
class SessionCleanupSettingsTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(SchedulingConfig.class)
            .withBean(UserSessionRepository.class, () -> mock(UserSessionRepository.class))
            .withBean(SessionCleanupJob.class);

    // A server clock in New York still gets 05:00 UTC, not 05:00 New York time.
    @Test
    void runsDailyAtFiveUtcByDefault() {
        contextRunner.run(context -> assertThat(cronTasks(context)).singleElement().satisfies(task -> {
            assertThat(task.getExpression()).isEqualTo("0 0 5 * * *");

            Clock newYorkNoon = Clock.fixed(Instant.parse("2026-09-27T16:00:00Z"), ZoneId.of("America/New_York"));
            assertThat(task.getTrigger().nextExecution(new SimpleTriggerContext(newYorkNoon)))
                    .isEqualTo(Instant.parse("2026-09-28T05:00:00Z"));
        }));
    }

    @Test
    void cronCanBeChanged() {
        contextRunner.withPropertyValues("euv-app.auth.session-cleanup.cron=0 0 3 * * SUN")
                .run(context -> assertThat(cronTasks(context))
                        .extracting(CronTask::getExpression)
                        .containsExactly("0 0 3 * * SUN"));
    }

    @Test
    void dashSwitchesTheCronOff() {
        contextRunner.withPropertyValues("euv-app.auth.session-cleanup.cron=-")
                .run(context -> assertThat(cronTasks(context)).isEmpty());
    }

    // Unix cron has 5 fields, Spring's 6 with seconds first: a pasted Unix one must not start at all.
    @Test
    void unixCronStopsStartup() {
        contextRunner.withPropertyValues("euv-app.auth.session-cleanup.cron=0 5 * * *")
                .run(context -> assertThat(context).hasFailed()
                        .getFailure().hasStackTraceContaining("0 5 * * *"));
    }

    @Test
    void keepsEndedSessionsFor30DaysByDefault() {
        contextRunner.run(context -> assertThat(cutoff(context)).isCloseTo(
                Instant.now().minus(Duration.ofDays(30)), within(1, ChronoUnit.MINUTES)));
    }

    @Test
    void retentionCanBeChanged() {
        contextRunner.withPropertyValues("euv-app.auth.session-cleanup.retention=7d")
                .run(context -> assertThat(cutoff(context)).isCloseTo(
                        Instant.now().minus(Duration.ofDays(7)), within(1, ChronoUnit.MINUTES)));
    }

    // The cutoff would land in the future and delete live sessions.
    @Test
    void negativeRetentionStopsStartup() {
        contextRunner.withPropertyValues("euv-app.auth.session-cleanup.retention=-1d")
                .run(context -> assertThat(context).hasFailed()
                        .getFailure().hasStackTraceContaining("retention"));
    }

    private static List<CronTask> cronTasks(ApplicationContext context) {
        return context.getBean(ScheduledTaskHolder.class).getScheduledTasks().stream()
                .map(ScheduledTask::getTask)
                .filter(CronTask.class::isInstance)
                .map(CronTask.class::cast)
                .toList();
    }

    private static Instant cutoff(ApplicationContext context) {
        context.getBean(SessionCleanupJob.class).deleteEndedSessions();

        ArgumentCaptor<Instant> cutoff = ArgumentCaptor.forClass(Instant.class);
        verify(context.getBean(UserSessionRepository.class)).deleteEndedBefore(cutoff.capture());
        return cutoff.getValue();
    }

    @Configuration
    @EnableScheduling
    static class SchedulingConfig {
    }

}

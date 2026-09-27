package com.euvmodcreator;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.resilience.annotation.ConcurrencyLimitBeanPostProcessor;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;

import static org.assertj.core.api.Assertions.assertThat;

class EuvModCreatorBackApplicationTests extends IntegrationTest {

    @Autowired
    private ApplicationContext context;

    @Test
    void contextLoads() {
    }

    // Without @EnableResilientMethods, @ConcurrencyLimit is ignored without a word.
    @Test
    void resilientMethodsAreOn() {
        assertThat(context.getBeanNamesForType(ConcurrencyLimitBeanPostProcessor.class)).isNotEmpty();
    }

    // Without @EnableScheduling, SessionCleanupJob never runs, and nothing else would notice.
    @Test
    void schedulingIsOn() {
        assertThat(context.getBeanNamesForType(ScheduledAnnotationBeanPostProcessor.class)).isNotEmpty();
    }

}

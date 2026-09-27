package com.euvmodcreator;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.util.ClassUtils;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

// The project's rule: every property of ours sits under euv-app., so it can't clash with a key of Spring's or of a
// library's.
class ConfigurationPropertiesPrefixTest {

    @Test
    void everyPropertiesRecordIsUnderEuvApp() {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(ConfigurationProperties.class));

        Set<BeanDefinition> records = scanner.findCandidateComponents("com.euvmodcreator");

        assertThat(records).isNotEmpty().allSatisfy(record -> {
            Class<?> type = ClassUtils.resolveClassName(record.getBeanClassName(), getClass().getClassLoader());
            ConfigurationProperties annotation = AnnotatedElementUtils.findMergedAnnotation(
                    type, ConfigurationProperties.class);

            assertThat(annotation).isNotNull();
            assertThat(annotation.prefix()).as(type.getName()).startsWith("euv-app.");
        });
    }

}

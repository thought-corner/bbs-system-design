package com.project.msa.common.monitoring;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

class MonitoringDefaultsEnvironmentPostProcessorTest {

    private final MonitoringDefaultsEnvironmentPostProcessor postProcessor =
            new MonitoringDefaultsEnvironmentPostProcessor();

    @Test
    @DisplayName("서비스가 아무것도 주지 않으면 Prometheus 노출·application 태그·HTTP 히스토그램 기본값이 들어간다")
    void appliesMonitoringDefaults() {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("service",
                Map.of("spring.application.name", "like")));

        postProcessor.postProcessEnvironment(environment, new SpringApplication());

        assertThat(environment.getProperty("management.endpoints.web.exposure.include")).isEqualTo("health,prometheus");
        assertThat(environment.getProperty("management.metrics.tags.application")).isEqualTo("like");
        assertThat(environment.getProperty(
                "management.metrics.distribution.percentiles-histogram.http.server.requests")).isEqualTo("true");
        assertThat(environment.getProperty("server.tomcat.mbeanregistry.enabled")).isEqualTo("true");
        assertThat(environment.getProperty(
                "management.metrics.distribution.percentiles-histogram.event.consume.lag")).isEqualTo("true");
    }

    @Test
    @DisplayName("서비스 설정이 같은 키를 주면 서비스 값이 기본값을 이긴다")
    void serviceSettingOverridesDefault() {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("service",
                Map.of("management.endpoints.web.exposure.include", "health")));

        postProcessor.postProcessEnvironment(environment, new SpringApplication());

        assertThat(environment.getProperty("management.endpoints.web.exposure.include")).isEqualTo("health");
    }
}

package com.project.msa.common.monitoring;

import java.util.Map;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/**
 * 모든 서비스가 같은 기준으로 지표를 내보내도록 기본 설정을 넣는다.
 * 가장 낮은 우선순위로 넣으므로 서비스의 `application.yaml`·프로필·환경 변수가 같은 키를 주면 그 값이 이긴다.
 * 도메인 지표(Outbox 적체, 이벤트 반영 지연 등)는 여기 두지 않고 그 개념이 있는 모듈에 둔다.
 */
public class MonitoringDefaultsEnvironmentPostProcessor implements EnvironmentPostProcessor {

    static final String PROPERTY_SOURCE_NAME = "monitoringDefaults";

    static final Map<String, Object> DEFAULTS = Map.of(
            "management.endpoints.web.exposure.include", "health,prometheus",
            "management.metrics.tags.application", "${spring.application.name}",
            // p95·p99를 Prometheus에서 계산할 수 있게 응답 시간을 버킷으로 내보낸다
            "management.metrics.distribution.percentiles-histogram.http.server.requests", "true",
            // 이벤트 반영 지연도 p95를 볼 수 있게 버킷으로 내보낸다
            "management.metrics.distribution.percentiles-histogram.event.consume.lag", "true",
            // 버킷 기본 상한(30초)으로는 적체가 생겼을 때 지연을 구별하지 못한다 (v1.0.0 실측: p95가 30초 버킷에 붙음)
            "management.metrics.distribution.maximum-expected-value.event.consume.lag", "10m",
            // Tomcat 스레드 지표는 MBean에서 읽는다. Boot는 기본으로 MBean 등록을 꺼 두므로 켠다
            "server.tomcat.mbeanregistry.enabled", "true");

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        environment.getPropertySources().addLast(new MapPropertySource(PROPERTY_SOURCE_NAME, DEFAULTS));
    }
}

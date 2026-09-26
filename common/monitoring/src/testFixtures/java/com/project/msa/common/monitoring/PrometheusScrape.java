package com.project.msa.common.monitoring;

import java.util.Arrays;
import java.util.Map;

/**
 * `/actuator/prometheus` 응답(텍스트 형식)에서 지표 값을 읽는다.
 * 이름이 같고 주어진 라벨을 모두 가진 줄의 값을 더한다. 없으면 0이다.
 */
public final class PrometheusScrape {

    private final String exposition;

    private PrometheusScrape(String exposition) {
        this.exposition = exposition;
    }

    public static PrometheusScrape of(String exposition) {
        return new PrometheusScrape(exposition);
    }

    public double value(String metricName, Map<String, String> labels) {
        return Arrays.stream(exposition.split("\n"))
                .filter(line -> line.startsWith(metricName + "{") || line.startsWith(metricName + " "))
                .filter(line -> labels.entrySet().stream()
                        .allMatch(label -> line.contains(label.getKey() + "=\"" + label.getValue() + "\"")))
                .mapToDouble(line -> Double.parseDouble(line.substring(line.lastIndexOf(' ') + 1)))
                .sum();
    }

    public double value(String metricName) {
        return value(metricName, Map.of());
    }

    public boolean has(String metricName, Map<String, String> labels) {
        return Arrays.stream(exposition.split("\n"))
                .filter(line -> line.startsWith(metricName + "{"))
                .anyMatch(line -> labels.entrySet().stream()
                        .allMatch(label -> line.contains(label.getKey() + "=\"" + label.getValue() + "\"")));
    }
}

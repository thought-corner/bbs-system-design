package com.project.msa.hotarticle;

import java.time.ZoneId;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param topCount    날짜별 랭킹에 남기고 확정할 순위 수
 * @param zone        날짜 경계와 확정 시각의 시각대
 * @param confirmHour 전날 목록을 확정하는 시각(시). 늦게 오는 전날 이벤트를 기다린다 (D14)
 */
@ConfigurationProperties("hot-article")
public record HotArticleProperties(double likeWeight, double commentWeight, double viewWeight, int topCount,
                                   ZoneId zone, int confirmHour) {
}

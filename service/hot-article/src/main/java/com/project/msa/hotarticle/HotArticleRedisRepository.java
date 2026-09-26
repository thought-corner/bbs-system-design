package com.project.msa.hotarticle;

import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations.TypedTuple;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

@Repository
class HotArticleRedisRepository {

    /** 생성일 다음 날 새벽 확정까지 살아 있으면 된다. */
    static final Duration AGGREGATION_TTL = Duration.ofDays(2);
    static final Duration CONFIRMED_LIST_TTL = Duration.ofDays(30);

    private static final DateTimeFormatter DATE_KEY = DateTimeFormatter.BASIC_ISO_DATE;
    private static final RedisScript<Long> APPLY_COUNT_SCRIPT =
            RedisScript.of(new ClassPathResource("scripts/apply-count.lua"), Long.class);
    private static final JsonMapper JSON_MAPPER = JsonMapper.builder().build();

    private final StringRedisTemplate redisTemplate;
    private final HotArticleProperties properties;

    HotArticleRedisRepository(StringRedisTemplate redisTemplate, HotArticleProperties properties) {
        this.redisTemplate = redisTemplate;
        this.properties = properties;
    }

    static String createdKey(long articleId) {
        return "hot-article::article::" + articleId + "::created";
    }

    static String countKey(long articleId, HotArticleMetric metric) {
        return "hot-article::article::" + articleId + "::" + metric.keySuffix();
    }

    static String lastEventIdKey(long articleId, HotArticleMetric metric) {
        return countKey(articleId, metric) + "::last-event-id";
    }

    static String rankingKey(LocalDate createdDate) {
        return "hot-article::ranking::" + createdDate.format(DATE_KEY);
    }

    static String confirmedListKey(LocalDate createdDate) {
        return "hot-article::list::" + createdDate.format(DATE_KEY);
    }

    void saveCreated(long articleId, ArticleCreatedDay createdDay) {
        redisTemplate.opsForValue().set(createdKey(articleId), JSON_MAPPER.writeValueAsString(createdDay),
                AGGREGATION_TTL);
    }

    Optional<ArticleCreatedDay> findCreated(long articleId) {
        return Optional.ofNullable(redisTemplate.opsForValue().get(createdKey(articleId)))
                .map(json -> JSON_MAPPER.readValue(json, ArticleCreatedDay.class));
    }

    /** @return 반영했으면 true, 생성일이 없거나 늦게 온 이벤트라 버렸으면 false */
    boolean applyCount(long articleId, LocalDate createdDate, HotArticleMetric metric, long count, long eventId) {
        Long applied = redisTemplate.execute(APPLY_COUNT_SCRIPT,
                List.of(countKey(articleId, metric), lastEventIdKey(articleId, metric),
                        countKey(articleId, HotArticleMetric.LIKE), countKey(articleId, HotArticleMetric.COMMENT),
                        countKey(articleId, HotArticleMetric.VIEW), rankingKey(createdDate), createdKey(articleId)),
                String.valueOf(count), String.valueOf(eventId), metric.orderRule().name(),
                String.valueOf(AGGREGATION_TTL.toSeconds()),
                String.valueOf(properties.likeWeight()), String.valueOf(properties.commentWeight()),
                String.valueOf(properties.viewWeight()), String.valueOf(articleId),
                String.valueOf(properties.topCount()));
        return applied != null && applied == 1L;
    }

    /** 생성일 키를 먼저 지워 뒤따르는 이벤트가 랭킹에 다시 넣지 못하게 한 뒤 랭킹에서 뺀다. */
    void excludeDeleted(long articleId, LocalDate createdDate) {
        redisTemplate.delete(createdKey(articleId));
        redisTemplate.opsForZSet().remove(rankingKey(createdDate), String.valueOf(articleId));
    }

    List<Long> findTopArticleIds(LocalDate createdDate) {
        Set<TypedTuple<String>> ranked = redisTemplate.opsForZSet()
                .reverseRangeWithScores(rankingKey(createdDate), 0, properties.topCount() - 1);
        if (ranked == null) {
            return List.of();
        }
        return ranked.stream().map(tuple -> Long.valueOf(tuple.getValue())).toList();
    }

    void saveConfirmedList(LocalDate createdDate, List<HotArticleResponse> hotArticles) {
        redisTemplate.opsForValue().set(confirmedListKey(createdDate), JSON_MAPPER.writeValueAsString(hotArticles),
                CONFIRMED_LIST_TTL);
    }

    List<HotArticleResponse> findConfirmedList(LocalDate createdDate) {
        String json = redisTemplate.opsForValue().get(confirmedListKey(createdDate));
        if (json == null) {
            return List.of();
        }
        return JSON_MAPPER.readValue(json, new TypeReference<>() {
        });
    }

    record ArticleCreatedDay(LocalDate createdDate, Long boardId) {
    }
}

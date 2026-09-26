package com.project.msa.articleread;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

@Repository
class ArticleReadRedisRepository {

    static final Duration ARTICLE_TTL = Duration.ofDays(1);
    static final int BOARD_ARTICLE_LIST_SIZE = 1_000;

    private static final RedisScript<Long> APPLY_FIELD = script("apply-field.lua");
    private static final RedisScript<Long> APPLY_CREATED = script("apply-created.lua");
    private static final RedisScript<Long> APPLY_DELETED = script("apply-deleted.lua");
    private static final RedisScript<Long> APPLY_BOARD_ARTICLE_COUNT = script("apply-board-article-count.lua");
    private static final JsonMapper JSON_MAPPER = JsonMapper.builder().build();

    private final StringRedisTemplate redisTemplate;

    ArticleReadRedisRepository(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    private static RedisScript<Long> script(String name) {
        return RedisScript.of(new ClassPathResource("scripts/" + name), Long.class);
    }

    static String articleKey(long articleId) {
        return "article-read::article::" + articleId;
    }

    static String boardArticleListKey(long boardId) {
        return "article-read::board::" + boardId + "::article-list";
    }

    static String boardArticleCountKey(long boardId) {
        return "article-read::board::" + boardId + "::article-count";
    }

    static String boardArticleCountEventIdKey(long boardId) {
        return boardArticleCountKey(boardId) + "::last-event-id";
    }

    void applyCreated(ArticleBody body, long eventId) {
        redisTemplate.execute(APPLY_CREATED,
                List.of(articleKey(body.articleId()), boardArticleListKey(body.boardId())),
                ArticleReadField.ARTICLE.field(), JSON_MAPPER.writeValueAsString(body),
                ArticleReadField.ARTICLE.eventIdField(), String.valueOf(eventId), ttlSeconds(),
                String.valueOf(body.articleId()), String.valueOf(BOARD_ARTICLE_LIST_SIZE));
    }

    void applyUpdated(ArticleBody body, long eventId) {
        applyField(body.articleId(), ArticleReadField.ARTICLE, JSON_MAPPER.writeValueAsString(body), eventId);
    }

    void applyCount(long articleId, ArticleReadField countField, long count, long eventId) {
        applyField(articleId, countField, String.valueOf(count), eventId);
    }

    void applyDeleted(long articleId, long boardId, long eventId) {
        redisTemplate.execute(APPLY_DELETED, List.of(articleKey(articleId), boardArticleListKey(boardId)),
                String.valueOf(eventId), ttlSeconds(), String.valueOf(articleId));
    }

    void applyBoardArticleCount(long boardId, long boardArticleCount, long eventId) {
        redisTemplate.execute(APPLY_BOARD_ARTICLE_COUNT,
                List.of(boardArticleCountKey(boardId), boardArticleCountEventIdKey(boardId)),
                String.valueOf(boardArticleCount), String.valueOf(eventId));
    }

    private void applyField(long articleId, ArticleReadField field, String value, long eventId) {
        redisTemplate.execute(APPLY_FIELD, List.of(articleKey(articleId)),
                field.field(), value, field.eventIdField(), String.valueOf(eventId), ttlSeconds());
    }

    private String ttlSeconds() {
        return String.valueOf(ARTICLE_TTL.toSeconds());
    }

    /** 본문이 아직 없거나(카운트 이벤트만 도착) 삭제 표시된 게시글은 읽기 모델에 없는 것으로 본다. */
    Optional<ArticleReadResponse> findArticle(long articleId) {
        return toResponse(redisTemplate.<String, String>opsForHash().entries(articleKey(articleId)));
    }

    List<ArticleReadResponse> findArticles(List<Long> articleIds) {
        List<Object> hashes = redisTemplate.executePipelined((RedisCallback<Object>)
                connection -> {
                    articleIds.forEach(articleId -> connection.hashCommands().hGetAll(
                            articleKey(articleId).getBytes(StandardCharsets.UTF_8)));
                    return null;
                });
        List<ArticleReadResponse> articles = new ArrayList<>();
        for (Object hash : hashes) {
            @SuppressWarnings("unchecked")
            Map<String, String> fields = (Map<String, String>) hash;
            toResponse(fields).ifPresent(articles::add);
        }
        return articles;
    }

    List<Long> findBoardArticleIdsByRank(long boardId, long offset, long limit) {
        Set<String> articleIds = redisTemplate.opsForZSet()
                .reverseRange(boardArticleListKey(boardId), offset, offset + limit - 1);
        return articleIds == null ? List.of() : articleIds.stream().map(Long::valueOf).toList();
    }

    /**
     * 점수는 articleId를 double로 바꾼 값이라 2^53을 넘는 ID는 가까운 값끼리 같은 점수가 된다.
     * 점수로는 커서 경계를 포함해 넉넉히 가져오고, ID를 정확히 비교해 커서 뒤의 것만 남긴다.
     */
    List<Long> findBoardArticleIdsBefore(long boardId, long lastArticleId, long limit) {
        List<Long> olderArticleIds = new ArrayList<>();
        long offset = 0;
        while (olderArticleIds.size() < limit) {
            Set<String> batch = redisTemplate.opsForZSet().reverseRangeByScore(boardArticleListKey(boardId),
                    Double.NEGATIVE_INFINITY, (double) lastArticleId, offset, limit);
            if (batch == null || batch.isEmpty()) {
                break;
            }
            for (String articleId : batch) {
                long candidateArticleId = Long.parseLong(articleId);
                if (candidateArticleId < lastArticleId && olderArticleIds.size() < limit) {
                    olderArticleIds.add(candidateArticleId);
                }
            }
            offset += batch.size();
        }
        return olderArticleIds;
    }

    long findBoardArticleCount(long boardId) {
        String count = redisTemplate.opsForValue().get(boardArticleCountKey(boardId));
        return count == null ? 0L : Long.parseLong(count);
    }

    private Optional<ArticleReadResponse> toResponse(Map<String, String> fields) {
        if (fields == null || "1".equals(fields.get(ArticleReadField.DELETED))
                || !fields.containsKey(ArticleReadField.ARTICLE.field())) {
            return Optional.empty();
        }
        ArticleBody body = JSON_MAPPER.readValue(fields.get(ArticleReadField.ARTICLE.field()), ArticleBody.class);
        return Optional.of(ArticleReadResponse.of(body,
                Long.parseLong(fields.getOrDefault(ArticleReadField.COMMENT_COUNT.field(), "0")),
                Long.parseLong(fields.getOrDefault(ArticleReadField.LIKE_COUNT.field(), "0"))));
    }
}

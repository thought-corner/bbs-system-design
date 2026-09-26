package com.project.msa.view;

import java.time.Duration;
import java.util.Optional;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

/** 조회수의 원천이다 (D9). MySQL 값은 백업일 뿐이다. */
@Repository
class ArticleViewCountRedisRepository {

    private final StringRedisTemplate redisTemplate;

    ArticleViewCountRedisRepository(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    static String viewCountKey(long articleId) {
        return "view::article::" + articleId + "::view-count";
    }

    static String abuseLockKey(long articleId, long userId) {
        return "view::article::" + articleId + "::user::" + userId + "::lock";
    }

    /** 획득과 만료 설정을 `SET NX EX` 한 명령으로 한다. 둘로 나누면 사이 장애에 키가 영원히 남는다 (D10). */
    boolean acquireAbuseLock(long articleId, long userId, Duration ttl) {
        return Boolean.TRUE.equals(redisTemplate.opsForValue().setIfAbsent(abuseLockKey(articleId, userId), "", ttl));
    }

    Optional<Long> findViewCount(long articleId) {
        return Optional.ofNullable(redisTemplate.opsForValue().get(viewCountKey(articleId))).map(Long::valueOf);
    }

    /** 키가 없을 때만 채운다. 동시에 복구해도 먼저 올라간 증가분을 덮어쓰지 않는다. */
    void fillIfAbsent(long articleId, long backupViewCount) {
        redisTemplate.opsForValue().setIfAbsent(viewCountKey(articleId), String.valueOf(backupViewCount));
    }

    long increase(long articleId) {
        return redisTemplate.opsForValue().increment(viewCountKey(articleId));
    }
}

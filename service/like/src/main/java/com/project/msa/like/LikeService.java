package com.project.msa.like;

import com.project.msa.common.event.EventType;
import com.project.msa.common.event.payload.ArticleLikedEventPayload;
import com.project.msa.common.event.payload.ArticleUnlikedEventPayload;
import com.project.msa.common.outbox.OutboxEventPublisher;
import com.project.msa.common.snowflake.Snowflake;
import java.time.Clock;
import java.time.LocalDateTime;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LikeService {

    private final ArticleLikeRepository articleLikeRepository;
    private final ArticleLikeCountRepository articleLikeCountRepository;
    private final OutboxEventPublisher outboxEventPublisher;
    private final Snowflake snowflake;
    private final Clock clock;

    LikeService(ArticleLikeRepository articleLikeRepository, ArticleLikeCountRepository articleLikeCountRepository,
                OutboxEventPublisher outboxEventPublisher, Snowflake snowflake, Clock clock) {
        this.articleLikeRepository = articleLikeRepository;
        this.articleLikeCountRepository = articleLikeCountRepository;
        this.outboxEventPublisher = outboxEventPublisher;
        this.snowflake = snowflake;
        this.clock = clock;
    }

    /** 이미 좋아요한 사용자면 아무것도 바꾸지 않고 이벤트도 내지 않는다. */
    @Transactional
    public void like(long articleId, long userId) {
        int likedRows = articleLikeRepository.insertIfAbsent(snowflake.nextId(), articleId, userId,
                LocalDateTime.now(clock));
        if (likedRows == 0) {
            return;
        }
        articleLikeCountRepository.increase(articleId);
        outboxEventPublisher.publish(EventType.ARTICLE_LIKED,
                new ArticleLikedEventPayload(articleId, userId, countOnArticle(articleId)), articleId);
    }

    /** 좋아요하지 않은 사용자면 아무것도 바꾸지 않고 이벤트도 내지 않는다. */
    @Transactional
    public void unlike(long articleId, long userId) {
        int unlikedRows = articleLikeRepository.deleteByArticleIdAndUserId(articleId, userId);
        if (unlikedRows == 0) {
            return;
        }
        articleLikeCountRepository.decrease(articleId);
        outboxEventPublisher.publish(EventType.ARTICLE_UNLIKED,
                new ArticleUnlikedEventPayload(articleId, userId, countOnArticle(articleId)), articleId);
    }

    @Transactional(readOnly = true)
    public long count(long articleId) {
        return countOnArticle(articleId);
    }

    private long countOnArticle(long articleId) {
        return articleLikeCountRepository.findLikeCount(articleId).orElse(0L);
    }
}

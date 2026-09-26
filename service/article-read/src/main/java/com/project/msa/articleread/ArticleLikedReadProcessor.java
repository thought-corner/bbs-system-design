package com.project.msa.articleread;

import com.project.msa.common.event.EventType;
import com.project.msa.common.event.payload.ArticleLikedEventPayload;
import org.springframework.stereotype.Component;

@Component
class ArticleLikedReadProcessor extends ArticleCountReadProcessor<ArticleLikedEventPayload> {

    ArticleLikedReadProcessor(ArticleReadRedisRepository articleReadRedisRepository) {
        super(articleReadRedisRepository, EventType.ARTICLE_LIKED, ArticleReadField.LIKE_COUNT,
                ArticleLikedEventPayload::articleId, ArticleLikedEventPayload::articleLikeCount);
    }
}

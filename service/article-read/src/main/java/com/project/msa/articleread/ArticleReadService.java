package com.project.msa.articleread;

import com.project.msa.articleread.ArticleOriginClient.OriginArticlePage;
import com.project.msa.articleread.ArticleReadResponse.ArticleReadPageResponse;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * 읽기는 이벤트로 만든 Redis 읽기 모델에서 끝나고, 없거나 논리 만료된 것만 원본으로 채운다 (D12·D13).
 */
@Service
public class ArticleReadService {

    private final ArticleReadRedisRepository articleReadRedisRepository;
    private final ArticleOriginClient articleOriginClient;
    private final ArticleCountOriginClient articleCountOriginClient;
    private final ViewClient viewClient;
    private final ArticleReadProperties properties;
    private final Clock clock;
    private final ArticleReadMetrics articleReadMetrics;

    ArticleReadService(ArticleReadRedisRepository articleReadRedisRepository, ArticleOriginClient articleOriginClient,
                       ArticleCountOriginClient articleCountOriginClient, ViewClient viewClient,
                       ArticleReadProperties properties, Clock clock, ArticleReadMetrics articleReadMetrics) {
        this.articleReadRedisRepository = articleReadRedisRepository;
        this.articleReadMetrics = articleReadMetrics;
        this.articleOriginClient = articleOriginClient;
        this.articleCountOriginClient = articleCountOriginClient;
        this.viewClient = viewClient;
        this.properties = properties;
        this.clock = clock;
    }

    /** 상세 조회가 곧 조회이므로 userId가 있으면 조회수를 올리고 그 값을 싣는다. */
    public ArticleReadDetailResponse read(long boardId, long articleId, Long userId) {
        ArticleReadResponse article = readArticle(boardId, articleId);
        Long viewCount = userId == null ? null : viewClient.increase(articleId, userId);
        return ArticleReadDetailResponse.of(article, viewCount);
    }

    private ArticleReadResponse readArticle(long boardId, long articleId) {
        ArticleReadSnapshot snapshot = articleReadRedisRepository.findSnapshot(articleId);
        if (snapshot.deleted()) {
            throw new ArticleNotFoundException(boardId, articleId);
        }
        Optional<ArticleReadResponse> cachedArticle = snapshot.article();
        if (cachedArticle.isPresent() && cachedArticle.get().boardId() != boardId) {
            throw new ArticleNotFoundException(boardId, articleId);
        }
        if (cachedArticle.isPresent() && !snapshot.logicallyExpiredAt(clock.instant())) {
            articleReadMetrics.recordSource(ArticleReadMetrics.READ_MODEL);
            return cachedArticle.get();
        }
        if (tryRefreshLock(articleId)) {
            ArticleReadResponse refreshedArticle = refreshFromOrigin(boardId, articleId, snapshot);
            articleReadMetrics.recordSource(ArticleReadMetrics.ORIGIN_REFRESH);
            return refreshedArticle;
        }
        // 다른 요청이 원본으로 맞추는 중이다. 기존 값이 있으면 그대로, 없으면 원본을 읽기만 한다
        if (cachedArticle.isPresent()) {
            articleReadMetrics.recordSource(ArticleReadMetrics.READ_MODEL);
            return cachedArticle.get();
        }
        ArticleReadResponse originArticle = readOriginWithoutWriting(boardId, articleId);
        articleReadMetrics.recordSource(ArticleReadMetrics.ORIGIN_PASSTHROUGH);
        return originArticle;
    }

    private boolean tryRefreshLock(long articleId) {
        boolean acquired = articleReadRedisRepository.tryRefreshLock(articleId, properties.refreshLockTtl());
        articleReadMetrics.recordRefreshLock(acquired);
        return acquired;
    }

    private ArticleReadResponse refreshFromOrigin(long boardId, long articleId, ArticleReadSnapshot snapshot) {
        ArticleBody body = articleOriginClient.readArticle(boardId, articleId)
                .orElseThrow(() -> new ArticleNotFoundException(boardId, articleId));
        fillFromOrigin(snapshot, body);
        return articleReadRedisRepository.findArticle(articleId)
                .orElseThrow(() -> new ArticleNotFoundException(boardId, articleId));
    }

    private void fillFromOrigin(ArticleReadSnapshot snapshot, ArticleBody body) {
        long commentCount = articleCountOriginClient.readCommentCount(body.articleId());
        long likeCount = articleCountOriginClient.readLikeCount(body.articleId());
        articleReadRedisRepository.fillFromOrigin(snapshot, body, commentCount, likeCount, nextLogicalExpiry());
    }

    private ArticleReadResponse readOriginWithoutWriting(long boardId, long articleId) {
        ArticleBody body = articleOriginClient.readArticle(boardId, articleId)
                .orElseThrow(() -> new ArticleNotFoundException(boardId, articleId));
        return ArticleReadResponse.of(body, articleCountOriginClient.readCommentCount(articleId),
                articleCountOriginClient.readLikeCount(articleId));
    }

    private Instant nextLogicalExpiry() {
        return clock.instant().plus(properties.logicalTtl());
    }

    /** 요청 범위가 최신 목록 ZSET에 다 있으면 ZSET으로, 모자라면 article 원본 목록으로 읽는다. */
    public ArticleReadPageResponse readPage(long boardId, long page, long pageSize) {
        long offset = (page - 1) * pageSize;
        if (boardArticleListCovers(boardId, offset + pageSize)) {
            List<Long> articleIds = articleReadRedisRepository.findBoardArticleIdsByRank(boardId, offset, pageSize);
            return new ArticleReadPageResponse(articleReadRedisRepository.findArticles(articleIds),
                    articleReadRedisRepository.findBoardArticleCount(boardId));
        }
        OriginArticlePage originPage = articleOriginClient.readPage(boardId, page, pageSize);
        return new ArticleReadPageResponse(completeFromReadModel(originPage.articles()),
                articleReadRedisRepository.findKnownBoardArticleCount(boardId).orElse(originPage.articleCount()));
    }

    public List<ArticleReadResponse> readScroll(long boardId, long pageSize, Long lastArticleId) {
        if (lastArticleId == null) {
            return readPage(boardId, 1, pageSize).articles();
        }
        List<Long> articleIds = articleReadRedisRepository.findBoardArticleIdsBefore(boardId, lastArticleId, pageSize);
        if (articleIds.size() == pageSize || boardArticleListIsWholeBoard(boardId)) {
            return articleReadRedisRepository.findArticles(articleIds);
        }
        return completeFromReadModel(articleOriginClient.readScroll(boardId, pageSize, lastArticleId));
    }

    /** ZSET이 그 순위까지 차 있거나, 게시판 전체를 담고 있으면 원본이 필요 없다. */
    private boolean boardArticleListCovers(long boardId, long rankEnd) {
        return articleReadRedisRepository.countBoardArticleList(boardId) >= rankEnd
                || boardArticleListIsWholeBoard(boardId);
    }

    private boolean boardArticleListIsWholeBoard(long boardId) {
        return articleReadRedisRepository.findKnownBoardArticleCount(boardId)
                .filter(boardArticleCount -> articleReadRedisRepository.countBoardArticleList(boardId)
                        >= boardArticleCount)
                .isPresent();
    }

    /** 읽기 모델에 있는 글은 그대로 쓰고, 없는 글만 원본 목록 행과 원본 댓글·좋아요 수로 채운다. */
    private List<ArticleReadResponse> completeFromReadModel(List<ArticleBody> originArticles) {
        return originArticles.stream()
                .map(body -> articleReadRedisRepository.findArticle(body.articleId())
                        .orElseGet(() -> fillListedArticle(body)))
                .toList();
    }

    private ArticleReadResponse fillListedArticle(ArticleBody body) {
        ArticleReadSnapshot snapshot = articleReadRedisRepository.findSnapshot(body.articleId());
        long commentCount = articleCountOriginClient.readCommentCount(body.articleId());
        long likeCount = articleCountOriginClient.readLikeCount(body.articleId());
        if (tryRefreshLock(body.articleId())) {
            articleReadRedisRepository.fillFromOrigin(snapshot, body, commentCount, likeCount, nextLogicalExpiry());
        }
        return ArticleReadResponse.of(body, commentCount, likeCount);
    }
}

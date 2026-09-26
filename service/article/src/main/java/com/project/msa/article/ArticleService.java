package com.project.msa.article;

import com.project.msa.article.ArticleDtos.ArticleCreateRequest;
import com.project.msa.article.ArticleDtos.ArticlePageResponse;
import com.project.msa.article.ArticleDtos.ArticleResponse;
import com.project.msa.article.ArticleDtos.ArticleUpdateRequest;
import com.project.msa.common.event.EventType;
import com.project.msa.common.event.payload.ArticleCreatedEventPayload;
import com.project.msa.common.event.payload.ArticleDeletedEventPayload;
import com.project.msa.common.event.payload.ArticleUpdatedEventPayload;
import com.project.msa.common.outbox.OutboxEventPublisher;
import com.project.msa.common.snowflake.Snowflake;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ArticleService {

    private final ArticleRepository articleRepository;
    private final BoardArticleCountRepository boardArticleCountRepository;
    private final OutboxEventPublisher outboxEventPublisher;
    private final Snowflake snowflake;
    private final Clock clock;

    ArticleService(ArticleRepository articleRepository, BoardArticleCountRepository boardArticleCountRepository,
                   OutboxEventPublisher outboxEventPublisher, Snowflake snowflake, Clock clock) {
        this.articleRepository = articleRepository;
        this.boardArticleCountRepository = boardArticleCountRepository;
        this.outboxEventPublisher = outboxEventPublisher;
        this.snowflake = snowflake;
        this.clock = clock;
    }

    @Transactional
    public ArticleResponse write(long boardId, ArticleCreateRequest request) {
        Article article = Article.write(snowflake.nextId(), boardId, request.writerId(),
                request.title(), request.content(), LocalDateTime.now(clock));
        articleRepository.save(article);
        boardArticleCountRepository.increase(boardId);
        outboxEventPublisher.publish(EventType.ARTICLE_CREATED, new ArticleCreatedEventPayload(
                article.getArticleId(), article.getBoardId(), article.getWriterId(), article.getTitle(),
                article.getContent(), article.getCreatedAt(), article.getModifiedAt(), countOnBoard(boardId)
        ), article.getArticleId());
        return ArticleResponse.from(article);
    }

    @Transactional
    public ArticleResponse edit(long boardId, long articleId, ArticleUpdateRequest request) {
        Article article = findOnBoard(boardId, articleId);
        article.edit(request.title(), request.content(), LocalDateTime.now(clock));
        outboxEventPublisher.publish(EventType.ARTICLE_UPDATED, new ArticleUpdatedEventPayload(
                article.getArticleId(), article.getBoardId(), article.getWriterId(), article.getTitle(),
                article.getContent(), article.getCreatedAt(), article.getModifiedAt(), countOnBoard(boardId)
        ), article.getArticleId());
        return ArticleResponse.from(article);
    }

    @Transactional
    public void delete(long boardId, long articleId) {
        Article article = findOnBoard(boardId, articleId);
        articleRepository.delete(article);
        boardArticleCountRepository.decrease(boardId);
        outboxEventPublisher.publish(EventType.ARTICLE_DELETED, new ArticleDeletedEventPayload(
                article.getArticleId(), article.getBoardId(), article.getWriterId(), article.getTitle(),
                article.getContent(), article.getCreatedAt(), article.getModifiedAt(), countOnBoard(boardId)
        ), article.getArticleId());
    }

    @Transactional(readOnly = true)
    public ArticleResponse read(long boardId, long articleId) {
        return ArticleResponse.from(findOnBoard(boardId, articleId));
    }

    @Transactional(readOnly = true)
    public ArticlePageResponse readPage(long boardId, long page, long pageSize) {
        List<ArticleResponse> articles = articleRepository.findPage(boardId, (page - 1) * pageSize, pageSize).stream()
                .map(ArticleResponse::from)
                .toList();
        return new ArticlePageResponse(articles, countOnBoard(boardId));
    }

    @Transactional(readOnly = true)
    public List<ArticleResponse> readScroll(long boardId, long pageSize, Long lastArticleId) {
        List<Article> articles = lastArticleId == null
                ? articleRepository.findFirstScroll(boardId, pageSize)
                : articleRepository.findScrollAfter(boardId, lastArticleId, pageSize);
        return articles.stream().map(ArticleResponse::from).toList();
    }

    private Article findOnBoard(long boardId, long articleId) {
        return articleRepository.findByArticleIdAndBoardId(articleId, boardId)
                .orElseThrow(() -> new ArticleNotFoundException(boardId, articleId));
    }

    private long countOnBoard(long boardId) {
        return boardArticleCountRepository.findArticleCount(boardId).orElse(0L);
    }
}

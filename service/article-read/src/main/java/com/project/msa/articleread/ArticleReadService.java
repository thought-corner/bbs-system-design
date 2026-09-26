package com.project.msa.articleread;

import com.project.msa.articleread.ArticleReadResponse.ArticleReadPageResponse;
import java.util.List;
import org.springframework.stereotype.Service;

/** 읽기는 원본 DB가 아니라 이벤트로 만든 Redis 읽기 모델에서 끝난다 (D12). */
@Service
public class ArticleReadService {

    private final ArticleReadRedisRepository articleReadRedisRepository;

    ArticleReadService(ArticleReadRedisRepository articleReadRedisRepository) {
        this.articleReadRedisRepository = articleReadRedisRepository;
    }

    public ArticleReadResponse read(long boardId, long articleId) {
        return articleReadRedisRepository.findArticle(articleId)
                .filter(article -> article.boardId() == boardId)
                .orElseThrow(() -> new ArticleNotFoundException(boardId, articleId));
    }

    public ArticleReadPageResponse readPage(long boardId, long page, long pageSize) {
        List<Long> articleIds = articleReadRedisRepository.findBoardArticleIdsByRank(boardId, (page - 1) * pageSize,
                pageSize);
        return new ArticleReadPageResponse(articleReadRedisRepository.findArticles(articleIds),
                articleReadRedisRepository.findBoardArticleCount(boardId));
    }

    public List<ArticleReadResponse> readScroll(long boardId, long pageSize, Long lastArticleId) {
        List<Long> articleIds = lastArticleId == null
                ? articleReadRedisRepository.findBoardArticleIdsByRank(boardId, 0, pageSize)
                : articleReadRedisRepository.findBoardArticleIdsBefore(boardId, lastArticleId, pageSize);
        return articleReadRedisRepository.findArticles(articleIds);
    }
}

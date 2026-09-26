package com.project.msa.article;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface ArticleRepository extends JpaRepository<Article, Long> {

    Optional<Article> findByArticleIdAndBoardId(Long articleId, Long boardId);

    /** 버리는 행은 (board_id, article_id) 인덱스에서만 읽고, 본문은 고른 페이지만 읽는다 (D4). */
    @Query(value = """
            SELECT article.article_id, article.board_id, article.writer_id, article.title, article.content,
                   article.created_at, article.modified_at
            FROM (
                SELECT article_id FROM article
                WHERE board_id = :boardId
                ORDER BY article_id DESC
                LIMIT :limit OFFSET :offset
            ) page_article_ids
            JOIN article ON article.article_id = page_article_ids.article_id
            ORDER BY article.article_id DESC
            """, nativeQuery = true)
    List<Article> findPage(@Param("boardId") Long boardId, @Param("offset") long offset, @Param("limit") long limit);

    @Query(value = """
            SELECT article_id, board_id, writer_id, title, content, created_at, modified_at
            FROM article
            WHERE board_id = :boardId
            ORDER BY article_id DESC
            LIMIT :limit
            """, nativeQuery = true)
    List<Article> findFirstScroll(@Param("boardId") Long boardId, @Param("limit") long limit);

    @Query(value = """
            SELECT article_id, board_id, writer_id, title, content, created_at, modified_at
            FROM article
            WHERE board_id = :boardId AND article_id < :lastArticleId
            ORDER BY article_id DESC
            LIMIT :limit
            """, nativeQuery = true)
    List<Article> findScrollAfter(@Param("boardId") Long boardId, @Param("lastArticleId") Long lastArticleId, @Param("limit") long limit);
}

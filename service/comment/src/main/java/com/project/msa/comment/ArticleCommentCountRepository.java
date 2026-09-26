package com.project.msa.comment;

import java.util.Optional;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/** 게시글 댓글 수는 읽어서 더하지 않고 행 락 아래에서 원자적으로 증감한다. */
interface ArticleCommentCountRepository extends Repository<ArticleCommentCount, Long> {

    @Query(value = "SELECT comment_count FROM article_comment_count WHERE article_id = :articleId", nativeQuery = true)
    Optional<Long> findCommentCount(@Param("articleId") Long articleId);

    @Modifying
    @Query(value = """
            INSERT INTO article_comment_count (article_id, comment_count) VALUES (:articleId, 1)
            ON DUPLICATE KEY UPDATE comment_count = comment_count + 1
            """, nativeQuery = true)
    void increase(@Param("articleId") Long articleId);

    @Modifying
    @Query(value = "UPDATE article_comment_count SET comment_count = comment_count - 1 WHERE article_id = :articleId",
            nativeQuery = true)
    void decrease(@Param("articleId") Long articleId);
}

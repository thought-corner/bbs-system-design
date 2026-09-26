package com.project.msa.like;

import java.util.Optional;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/** 좋아요 수는 읽어서 더하지 않고 행 락 아래에서 원자적으로 증감한다 (D8). */
interface ArticleLikeCountRepository extends Repository<ArticleLikeCount, Long> {

    @Query(value = "SELECT like_count FROM article_like_count WHERE article_id = :articleId", nativeQuery = true)
    Optional<Long> findLikeCount(@Param("articleId") Long articleId);

    @Modifying
    @Query(value = """
            INSERT INTO article_like_count (article_id, like_count) VALUES (:articleId, 1)
            ON DUPLICATE KEY UPDATE like_count = like_count + 1
            """, nativeQuery = true)
    void increase(@Param("articleId") Long articleId);

    @Modifying
    @Query(value = "UPDATE article_like_count SET like_count = like_count - 1 WHERE article_id = :articleId",
            nativeQuery = true)
    void decrease(@Param("articleId") Long articleId);
}

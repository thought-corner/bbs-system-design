package com.project.msa.view;

import java.util.Optional;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

interface ArticleViewCountBackupRepository extends Repository<ArticleViewCount, Long> {

    @Query(value = "SELECT view_count FROM article_view_count WHERE article_id = :articleId", nativeQuery = true)
    Optional<Long> findViewCount(@Param("articleId") Long articleId);

    /** 늦게 도착한 더 작은 값이 더 큰 백업을 덮어쓰지 않게 기존 값이 작을 때만 바꾼다 (D9). */
    @Modifying
    @Query(value = """
            INSERT INTO article_view_count (article_id, view_count) VALUES (:articleId, :viewCount)
            ON DUPLICATE KEY UPDATE view_count = GREATEST(view_count, :viewCount)
            """, nativeQuery = true)
    void backup(@Param("articleId") Long articleId, @Param("viewCount") Long viewCount);
}

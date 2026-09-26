package com.project.msa.like;

import java.time.LocalDateTime;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

interface ArticleLikeRepository extends Repository<ArticleLike, Long> {

    /**
     * 이미 좋아요한 사용자면 유니크 인덱스에 걸려 0행을 돌려준다.
     * 예외로 처리하면 트랜잭션이 롤백 전용이 되므로 중복을 영향받은 행 수로 판단한다 (D8).
     */
    @Modifying
    @Query(value = """
            INSERT IGNORE INTO article_like (article_like_id, article_id, user_id, created_at)
            VALUES (:articleLikeId, :articleId, :userId, :likedAt)
            """, nativeQuery = true)
    int insertIfAbsent(@Param("articleLikeId") Long articleLikeId, @Param("articleId") Long articleId,
                       @Param("userId") Long userId, @Param("likedAt") LocalDateTime likedAt);

    @Modifying
    @Query(value = "DELETE FROM article_like WHERE article_id = :articleId AND user_id = :userId", nativeQuery = true)
    int deleteByArticleIdAndUserId(@Param("articleId") Long articleId, @Param("userId") Long userId);

    @Query(value = "SELECT COUNT(*) FROM article_like WHERE article_id = :articleId", nativeQuery = true)
    long countLikeRows(@Param("articleId") Long articleId);
}

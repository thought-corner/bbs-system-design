package com.project.msa.comment;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface CommentRepository extends JpaRepository<Comment, Long> {

    Optional<Comment> findByCommentIdAndArticleId(Long commentId, Long articleId);

    /** 동시에 같은 댓글을 지워 댓글 수가 두 번 줄지 않게 행을 잠그고 읽는다. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM Comment c WHERE c.commentId = :commentId AND c.articleId = :articleId")
    Optional<Comment> findForDelete(@Param("commentId") Long commentId, @Param("articleId") Long articleId);

    Optional<Comment> findByArticleIdAndPath(Long articleId, String path);

    /** 부모 경로 아래 가장 큰 자손 경로. 새 자식 경로의 기준이다 (D6). */
    @Query(value = """
            SELECT path FROM comment
            WHERE article_id = :articleId AND path > :parentPath AND path LIKE CONCAT(:parentPath, '%')
            ORDER BY path DESC
            LIMIT 1
            """, nativeQuery = true)
    Optional<String> findLastDescendantPath(@Param("articleId") Long articleId, @Param("parentPath") String parentPath);

    /** 자기 자신 포함 두 행까지만 읽어 자식 존재를 판단한다 (D7). */
    @Query(value = """
            SELECT COUNT(*) FROM (
                SELECT comment_id FROM comment
                WHERE article_id = :articleId AND path LIKE CONCAT(:path, '%')
                LIMIT 2
            ) self_and_first_child
            """, nativeQuery = true)
    long countSelfAndFirstChild(@Param("articleId") Long articleId, @Param("path") String path);

    @Query(value = """
            SELECT comment.comment_id, comment.article_id, comment.writer_id, comment.content, comment.path,
                   comment.deleted, comment.created_at
            FROM (
                SELECT comment_id FROM comment
                WHERE article_id = :articleId
                ORDER BY path
                LIMIT :limit OFFSET :offset
            ) page_comment_ids
            JOIN comment ON comment.comment_id = page_comment_ids.comment_id
            ORDER BY comment.path
            """, nativeQuery = true)
    List<Comment> findPage(@Param("articleId") Long articleId, @Param("offset") long offset, @Param("limit") long limit);

    @Query(value = """
            SELECT comment_id, article_id, writer_id, content, path, deleted, created_at
            FROM comment
            WHERE article_id = :articleId
            ORDER BY path
            LIMIT :limit
            """, nativeQuery = true)
    List<Comment> findFirstScroll(@Param("articleId") Long articleId, @Param("limit") long limit);

    @Query(value = """
            SELECT comment_id, article_id, writer_id, content, path, deleted, created_at
            FROM comment
            WHERE article_id = :articleId AND path > :lastPath
            ORDER BY path
            LIMIT :limit
            """, nativeQuery = true)
    List<Comment> findScrollAfter(@Param("articleId") Long articleId, @Param("lastPath") String lastPath,
                                  @Param("limit") long limit);
}

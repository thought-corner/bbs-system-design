package com.project.msa.comment;

import java.time.LocalDateTime;
import java.util.List;

final class CommentDtos {

    private CommentDtos() {
    }

    /**
     * @param parentCommentId 없으면 최상위 댓글
     */
    record CommentCreateRequest(Long writerId, String content, Long parentCommentId) {
    }

    record CommentResponse(Long commentId, Long articleId, Long writerId, String content, String path,
                           Boolean deleted, LocalDateTime createdAt) {

        /**
         * 삭제 표시된 댓글은 자리만 남기고 본문을 내보내지 않는다.
         */
        static CommentResponse from(Comment comment) {
            return new CommentResponse(comment.getCommentId(), comment.getArticleId(), comment.getWriterId(),
                    comment.getDeleted() ? "" : comment.getContent(), comment.getPath(), comment.getDeleted(),
                    comment.getCreatedAt());
        }
    }

    record CommentPageResponse(List<CommentResponse> comments, long articleCommentCount) {
    }
}

package com.project.msa.comment;

import com.project.msa.comment.CommentDtos.CommentCreateRequest;
import com.project.msa.comment.CommentDtos.CommentPageResponse;
import com.project.msa.comment.CommentDtos.CommentResponse;
import com.project.msa.common.event.EventType;
import com.project.msa.common.event.payload.CommentCreatedEventPayload;
import com.project.msa.common.event.payload.CommentDeletedEventPayload;
import com.project.msa.common.outbox.OutboxEventPublisher;
import com.project.msa.common.snowflake.Snowflake;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class CommentService {

    /**
     * 같은 부모에 동시에 답글이 달려 경로가 겹칠 때 다시 계산하는 횟수 상한 (D6).
     * 한 작성자는 먼저 커밋한 경쟁자마다 한 번씩만 지므로 같은 부모에 동시 답글 이 수만큼은 반드시 성공한다.
     */
    static final int MAX_PATH_CONFLICT_ATTEMPTS = 10;

    private final CommentRepository commentRepository;
    private final ArticleCommentCountRepository articleCommentCountRepository;
    private final OutboxEventPublisher outboxEventPublisher;
    private final TransactionTemplate transactionTemplate;
    private final Snowflake snowflake;
    private final Clock clock;

    CommentService(CommentRepository commentRepository, ArticleCommentCountRepository articleCommentCountRepository,
                   OutboxEventPublisher outboxEventPublisher, PlatformTransactionManager transactionManager,
                   Snowflake snowflake, Clock clock) {
        this.commentRepository = commentRepository;
        this.articleCommentCountRepository = articleCommentCountRepository;
        this.outboxEventPublisher = outboxEventPublisher;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.snowflake = snowflake;
        this.clock = clock;
    }

    /** 경로가 겹치면 유니크 인덱스가 거부하고, 새 트랜잭션에서 경로를 다시 계산한다. */
    public CommentResponse write(long articleId, CommentCreateRequest request) {
        for (int attempt = 1; ; attempt++) {
            try {
                return transactionTemplate.execute(status -> writeOnce(articleId, request));
            } catch (DataIntegrityViolationException pathConflict) {
                if (attempt >= MAX_PATH_CONFLICT_ATTEMPTS) {
                    throw pathConflict;
                }
            }
        }
    }

    private CommentResponse writeOnce(long articleId, CommentCreateRequest request) {
        CommentPath parentPath = request.parentCommentId() == null
                ? CommentPath.articleRoot()
                : findOnArticle(articleId, request.parentCommentId()).commentPath();
        String lastDescendantPath = commentRepository.findLastDescendantPath(articleId, parentPath.value())
                .orElse(null);
        Comment comment = Comment.write(snowflake.nextId(), articleId, request.writerId(), request.content(),
                parentPath.nextChildPath(lastDescendantPath), LocalDateTime.now(clock));
        commentRepository.saveAndFlush(comment);
        articleCommentCountRepository.increase(articleId);
        outboxEventPublisher.publish(EventType.COMMENT_CREATED, new CommentCreatedEventPayload(
                comment.getCommentId(), articleId, comment.getPath(), false, countOnArticle(articleId)
        ), articleId);
        return CommentResponse.from(comment);
    }

    /**
     * 자식이 있으면 삭제 표시만 하고, 없으면 물리 삭제한 뒤 자식이 없어진 삭제 표시 조상을 거슬러 올라가며 정리한다 (D7).
     * 이미 삭제 표시된 댓글은 댓글 수를 다시 줄이지 않는다.
     */
    @Transactional
    public void delete(long articleId, long commentId) {
        Comment comment = commentRepository.findForDelete(commentId, articleId)
                .orElseThrow(() -> new CommentNotFoundException(articleId, commentId));
        if (comment.getDeleted()) {
            return;
        }

        if (hasChildren(comment)) {
            comment.markDeleted();
        } else {
            commentRepository.delete(comment);
            commentRepository.flush();
            removeChildlessDeletedAncestors(comment);
        }
        articleCommentCountRepository.decrease(articleId);
        outboxEventPublisher.publish(EventType.COMMENT_DELETED, new CommentDeletedEventPayload(
                commentId, articleId, comment.getPath(), true, countOnArticle(articleId)
        ), articleId);
    }

    private void removeChildlessDeletedAncestors(Comment removedComment) {
        CommentPath ancestorPath = removedComment.commentPath().parentPath();
        while (!ancestorPath.isArticleRoot()) {
            Optional<Comment> ancestor = commentRepository.findByArticleIdAndPath(
                    removedComment.getArticleId(), ancestorPath.value());
            if (ancestor.isEmpty() || !ancestor.get().getDeleted() || hasChildren(ancestor.get())) {
                return;
            }
            commentRepository.delete(ancestor.get());
            commentRepository.flush();
            ancestorPath = ancestorPath.parentPath();
        }
    }

    @Transactional(readOnly = true)
    public CommentResponse read(long articleId, long commentId) {
        return CommentResponse.from(findOnArticle(articleId, commentId));
    }

    @Transactional(readOnly = true)
    public CommentPageResponse readPage(long articleId, long page, long pageSize) {
        List<CommentResponse> comments = commentRepository.findPage(articleId, (page - 1) * pageSize, pageSize).stream()
                .map(CommentResponse::from)
                .toList();
        return new CommentPageResponse(comments, countOnArticle(articleId));
    }

    @Transactional(readOnly = true)
    public List<CommentResponse> readScroll(long articleId, long pageSize, String lastPath) {
        List<Comment> comments = lastPath == null
                ? commentRepository.findFirstScroll(articleId, pageSize)
                : commentRepository.findScrollAfter(articleId, lastPath, pageSize);
        return comments.stream().map(CommentResponse::from).toList();
    }

    private boolean hasChildren(Comment comment) {
        return commentRepository.countSelfAndFirstChild(comment.getArticleId(), comment.getPath()) > 1;
    }

    private Comment findOnArticle(long articleId, long commentId) {
        return commentRepository.findByCommentIdAndArticleId(commentId, articleId)
                .orElseThrow(() -> new CommentNotFoundException(articleId, commentId));
    }

    private long countOnArticle(long articleId) {
        return articleCommentCountRepository.findCommentCount(articleId).orElse(0L);
    }
}

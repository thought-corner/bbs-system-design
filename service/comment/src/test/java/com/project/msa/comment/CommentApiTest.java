package com.project.msa.comment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.project.msa.comment.CommentDtos.CommentCreateRequest;
import com.project.msa.comment.CommentDtos.CommentPageResponse;
import com.project.msa.comment.CommentDtos.CommentResponse;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc
@Import(CommentTestConfig.class)
class CommentApiTest {

    /** 테스트마다 다른 게시글을 써서 댓글 트리와 댓글 수가 섞이지 않게 한다. */
    private static final AtomicLong ARTICLE_SEQUENCE = new AtomicLong(1_000);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private CommentService commentService;

    @Test
    @DisplayName("최상위 댓글 경로는 5자이고 형제는 생성 순서대로 커진다")
    void topLevelPathsGrowInCreationOrder() throws Exception {
        long articleId = newArticle();

        CommentResponse first = write(articleId, null, "첫째");
        CommentResponse second = write(articleId, null, "둘째");
        CommentResponse third = write(articleId, null, "셋째");

        assertThat(first.path()).isEqualTo("00000");
        assertThat(second.path()).isEqualTo("00001");
        assertThat(third.path()).isEqualTo("00002");
    }

    @Test
    @DisplayName("답글 경로는 부모 경로 + 5자이고, 손자가 있어도 다음 자식은 마지막 직계 자식 다음 경로를 받는다")
    void replyPathFollowsLastDirectChild() throws Exception {
        long articleId = newArticle();
        CommentResponse parent = write(articleId, null, "부모");
        CommentResponse firstChild = write(articleId, parent.commentId(), "첫 자식");
        CommentResponse grandchild = write(articleId, firstChild.commentId(), "손자");

        CommentResponse secondChild = write(articleId, parent.commentId(), "둘째 자식");

        assertThat(firstChild.path()).isEqualTo(parent.path() + "00000");
        assertThat(grandchild.path()).isEqualTo(firstChild.path() + "00000");
        assertThat(secondChild.path()).isEqualTo(parent.path() + "00001");
    }

    @Test
    @DisplayName("페이지 번호 목록은 부모 바로 뒤에 그 서브트리 전체가 오는 트리 순서다")
    void pageListIsTreeOrder() throws Exception {
        long articleId = newArticle();
        CommentResponse a = write(articleId, null, "A");
        CommentResponse b = write(articleId, null, "B");
        CommentResponse a1 = write(articleId, a.commentId(), "A1");
        write(articleId, b.commentId(), "B1");
        write(articleId, a.commentId(), "A2");
        write(articleId, a1.commentId(), "A1a");

        List<CommentResponse> firstPage = readPage(articleId, 1, 4).comments();
        List<CommentResponse> secondPage = readPage(articleId, 2, 4).comments();

        assertThat(contentsOf(firstPage)).containsExactly("A", "A1", "A1a", "A2");
        assertThat(contentsOf(secondPage)).containsExactly("B", "B1");
        assertThat(readPage(articleId, 1, 10).articleCommentCount()).isEqualTo(6);
    }

    @Test
    @DisplayName("커서로 끝까지 이어 읽은 결과는 페이지 번호로 전부 읽은 결과와 같다")
    void scrollToEndMatchesAllPages() throws Exception {
        long articleId = newArticle();
        for (int i = 0; i < 4; i++) {
            CommentResponse topLevel = write(articleId, null, "최상위 " + i);
            CommentResponse reply = write(articleId, topLevel.commentId(), "답글 " + i);
            write(articleId, reply.commentId(), "답글의 답글 " + i);
        }

        List<Long> scrolledIds = new ArrayList<>();
        String lastPath = null;
        while (true) {
            List<CommentResponse> scroll = readScroll(articleId, 5, lastPath);
            if (scroll.isEmpty()) {
                break;
            }
            scrolledIds.addAll(idsOf(scroll));
            lastPath = scroll.get(scroll.size() - 1).path();
        }
        List<Long> pagedIds = new ArrayList<>();
        for (int page = 1; page <= 3; page++) {
            pagedIds.addAll(idsOf(readPage(articleId, page, 5).comments()));
        }

        assertThat(scrolledIds).hasSize(12).containsExactlyElementsOf(pagedIds);
    }

    @Test
    @DisplayName("다른 게시글의 댓글은 섞이지 않고, 다른 게시글 경로로 조회·삭제하거나 부모로 지정하면 404다")
    void otherArticleIsIsolated() throws Exception {
        long articleId = newArticle();
        long otherArticleId = newArticle();
        CommentResponse comment = write(articleId, null, "이 게시글 댓글");
        write(otherArticleId, null, "다른 게시글 댓글");

        assertThat(contentsOf(readPage(articleId, 1, 10).comments())).containsExactly("이 게시글 댓글");
        mockMvc.perform(get(commentPath(otherArticleId, comment.commentId()))).andExpect(status().isNotFound());
        mockMvc.perform(delete(commentPath(otherArticleId, comment.commentId()))).andExpect(status().isNotFound());
        mockMvc.perform(post("/v1/articles/" + otherArticleId + "/comments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new CommentCreateRequest(1L, "엉뚱한 부모", comment.commentId()))))
                .andExpect(status().isNotFound());

        assertThat(read(articleId, comment.commentId()).deleted()).isFalse();
    }

    @Test
    @DisplayName("자식이 있는 댓글을 지우면 삭제 표시만 되어 본문 없이 목록에 남고 댓글 수는 1 줄어든다")
    void deletingCommentWithChildrenMarksDeleted() throws Exception {
        long articleId = newArticle();
        CommentResponse parent = write(articleId, null, "지울 부모");
        write(articleId, parent.commentId(), "남는 자식");

        mockMvc.perform(delete(commentPath(articleId, parent.commentId()))).andExpect(status().isOk());

        CommentResponse markedParent = read(articleId, parent.commentId());
        assertThat(markedParent.deleted()).isTrue();
        assertThat(markedParent.content()).isEmpty();
        CommentPageResponse page = readPage(articleId, 1, 10);
        assertThat(idsOf(page.comments())).contains(parent.commentId());
        assertThat(page.articleCommentCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("자식 없는 댓글을 지우면 물리 삭제되고, 자식이 없어진 삭제 표시 조상도 두 단계까지 연쇄로 지워진다")
    void deletingLeafRemovesChildlessDeletedAncestors() throws Exception {
        long articleId = newArticle();
        CommentResponse keptTopLevel = write(articleId, null, "남는 최상위");
        CommentResponse grandparent = write(articleId, null, "조부모");
        CommentResponse parent = write(articleId, grandparent.commentId(), "부모");
        CommentResponse leaf = write(articleId, parent.commentId(), "잎");
        mockMvc.perform(delete(commentPath(articleId, grandparent.commentId()))).andExpect(status().isOk());
        mockMvc.perform(delete(commentPath(articleId, parent.commentId()))).andExpect(status().isOk());

        mockMvc.perform(delete(commentPath(articleId, leaf.commentId()))).andExpect(status().isOk());

        mockMvc.perform(get(commentPath(articleId, leaf.commentId()))).andExpect(status().isNotFound());
        mockMvc.perform(get(commentPath(articleId, parent.commentId()))).andExpect(status().isNotFound());
        mockMvc.perform(get(commentPath(articleId, grandparent.commentId()))).andExpect(status().isNotFound());
        CommentPageResponse page = readPage(articleId, 1, 10);
        assertThat(idsOf(page.comments())).containsExactly(keptTopLevel.commentId());
        assertThat(page.articleCommentCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("이미 삭제 표시된 댓글을 다시 지워도 댓글 수는 한 번만 줄어든다")
    void deletingMarkedCommentAgainDoesNotDecreaseTwice() throws Exception {
        long articleId = newArticle();
        CommentResponse parent = write(articleId, null, "부모");
        write(articleId, parent.commentId(), "자식");

        mockMvc.perform(delete(commentPath(articleId, parent.commentId()))).andExpect(status().isOk());
        mockMvc.perform(delete(commentPath(articleId, parent.commentId()))).andExpect(status().isOk());

        assertThat(readPage(articleId, 1, 10).articleCommentCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("같은 부모에 재시도 상한만큼 답글을 동시에 달아도 전부 성공하고 경로가 모두 다르다")
    void concurrentRepliesGetDistinctPaths() throws Exception {
        long articleId = newArticle();
        CommentResponse parent = write(articleId, null, "부모");
        int replyCount = CommentService.MAX_PATH_CONFLICT_ATTEMPTS;
        ExecutorService executor = Executors.newFixedThreadPool(replyCount);
        CountDownLatch startSignal = new CountDownLatch(1);
        List<Future<CommentResponse>> replies = new ArrayList<>();

        for (int i = 0; i < replyCount; i++) {
            long writerId = i;
            replies.add(executor.submit(() -> {
                startSignal.await();
                return commentService.write(articleId, new CommentCreateRequest(writerId, "동시 답글", parent.commentId()));
            }));
        }
        startSignal.countDown();
        List<String> replyPaths = new ArrayList<>();
        List<Throwable> failures = new ArrayList<>();
        for (Future<CommentResponse> reply : replies) {
            try {
                replyPaths.add(reply.get(60, TimeUnit.SECONDS).path());
            } catch (ExecutionException failure) {
                failures.add(failure.getCause());
            }
        }
        executor.shutdown();

        assertThat(failures).isEmpty();
        assertThat(new HashSet<>(replyPaths)).hasSize(replyCount);
        assertThat(replyPaths).allSatisfy(path -> {
            assertThat(path).startsWith(parent.path());
            assertThat(path).hasSize(parent.path().length() + 5);
        });
        assertThat(readPage(articleId, 1, 50).articleCommentCount()).isEqualTo(replyCount + 1);
    }

    @Test
    @DisplayName("depth 600까지 답글을 달 수 있고 601번째는 거부된다")
    void replyChainStopsAtDepth600() {
        long articleId = newArticle();
        CommentResponse deepest = commentService.write(articleId, new CommentCreateRequest(1L, "depth 1", null));
        for (int depth = 2; depth <= CommentPath.MAX_DEPTH; depth++) {
            deepest = commentService.write(articleId,
                    new CommentCreateRequest(1L, "depth " + depth, deepest.commentId()));
        }
        long deepestCommentId = deepest.commentId();

        assertThat(deepest.path()).hasSize(3000);
        assertThatThrownBy(() -> commentService.write(articleId,
                new CommentCreateRequest(1L, "depth 601", deepestCommentId)))
                .isInstanceOf(CommentPathLimitExceededException.class);
    }

    @Test
    @DisplayName("댓글 수 API는 삭제 표시를 뺀 게시글 댓글 수를 돌려준다")
    void countApiReturnsArticleCommentCount() throws Exception {
        long articleId = newArticle();
        long untouchedArticleId = newArticle();
        CommentResponse parent = write(articleId, null, "부모");
        write(articleId, parent.commentId(), "자식");
        write(articleId, null, "둘째 최상위");
        mockMvc.perform(delete(commentPath(articleId, parent.commentId()))).andExpect(status().isOk());

        assertThat(commentCount(articleId)).isEqualTo(2);
        assertThat(commentCount(untouchedArticleId)).isZero();
    }

    private long commentCount(long articleId) throws Exception {
        MvcResult result = mockMvc.perform(get("/v1/articles/" + articleId + "/comments/count"))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readValue(result.getResponse().getContentAsString(),
                CommentController.ArticleCommentCountResponse.class).commentCount();
    }

    private long newArticle() {
        return ARTICLE_SEQUENCE.incrementAndGet();
    }

    private String commentPath(long articleId, long commentId) {
        return "/v1/articles/" + articleId + "/comments/" + commentId;
    }

    private CommentResponse write(long articleId, Long parentCommentId, String content) throws Exception {
        String body = objectMapper.writeValueAsString(new CommentCreateRequest(1L, content, parentCommentId));
        MvcResult result = mockMvc.perform(post("/v1/articles/" + articleId + "/comments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readValue(result.getResponse().getContentAsString(), CommentResponse.class);
    }

    private CommentResponse read(long articleId, long commentId) throws Exception {
        MvcResult result = mockMvc.perform(get(commentPath(articleId, commentId)))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readValue(result.getResponse().getContentAsString(), CommentResponse.class);
    }

    private CommentPageResponse readPage(long articleId, long page, long pageSize) throws Exception {
        MvcResult result = mockMvc.perform(get("/v1/articles/" + articleId + "/comments")
                        .param("page", String.valueOf(page))
                        .param("pageSize", String.valueOf(pageSize)))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readValue(result.getResponse().getContentAsString(), CommentPageResponse.class);
    }

    private List<CommentResponse> readScroll(long articleId, long pageSize, String lastPath) throws Exception {
        var request = get("/v1/articles/" + articleId + "/comments/infinite-scroll")
                .param("pageSize", String.valueOf(pageSize));
        if (lastPath != null) {
            request.param("lastPath", lastPath);
        }
        MvcResult result = mockMvc.perform(request).andExpect(status().isOk()).andReturn();
        return objectMapper.readValue(result.getResponse().getContentAsString(), new TypeReference<>() {
        });
    }

    private List<Long> idsOf(List<CommentResponse> comments) {
        return comments.stream().map(CommentResponse::commentId).toList();
    }

    private List<String> contentsOf(List<CommentResponse> comments) {
        return comments.stream().map(CommentResponse::content).toList();
    }
}

package com.project.msa.article;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.project.msa.article.ArticleDtos.ArticlePageResponse;
import com.project.msa.article.ArticleDtos.ArticleResponse;
import com.project.msa.article.ArticleTestConfig.MovableClock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
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
@Import(ArticleTestConfig.class)
class ArticleApiTest {

    /** 테스트마다 다른 게시판을 써서 게시판 게시글 수가 서로 섞이지 않게 한다. */
    private static final AtomicLong BOARD_SEQUENCE = new AtomicLong(1_000);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private MovableClock movableClock;

    @Test
    @DisplayName("생성한 게시글을 조회하면 게시판·작성자·제목·본문이 그대로 나온다")
    void writeThenRead() throws Exception {
        long boardId = newBoard();

        ArticleResponse written = write(boardId, 7L, "첫 글", "본문");
        ArticleResponse read = read(boardId, written.articleId());

        assertThat(read.boardId()).isEqualTo(boardId);
        assertThat(read.writerId()).isEqualTo(7L);
        assertThat(read.title()).isEqualTo("첫 글");
        assertThat(read.content()).isEqualTo("본문");
    }

    @Test
    @DisplayName("수정은 제목·본문과 수정 시각만 바꾸고 생성 시각·게시판·작성자는 그대로 둔다")
    void editChangesOnlyTitleAndContent() throws Exception {
        long boardId = newBoard();
        ArticleResponse written = write(boardId, 7L, "원래 제목", "원래 본문");

        movableClock.advance(Duration.ofMinutes(5));
        mockMvc.perform(put(articlePath(boardId, written.articleId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"바뀐 제목\",\"content\":\"바뀐 본문\"}"))
                .andExpect(status().isOk());
        ArticleResponse edited = read(boardId, written.articleId());

        assertThat(edited.title()).isEqualTo("바뀐 제목");
        assertThat(edited.content()).isEqualTo("바뀐 본문");
        assertThat(edited.modifiedAt()).isEqualTo(written.modifiedAt().plusMinutes(5));
        assertThat(edited.createdAt()).isEqualTo(written.createdAt());
        assertThat(edited.boardId()).isEqualTo(boardId);
        assertThat(edited.writerId()).isEqualTo(7L);
    }

    @Test
    @DisplayName("삭제한 게시글은 404이고 게시판 게시글 수가 1 줄어든다")
    void deleteRemovesArticleAndDecreasesCount() throws Exception {
        long boardId = newBoard();
        write(boardId, 1L, "남는 글", "본문");
        ArticleResponse deleting = write(boardId, 1L, "지울 글", "본문");
        assertThat(readPage(boardId, 1, 10).articleCount()).isEqualTo(2);

        mockMvc.perform(delete(articlePath(boardId, deleting.articleId()))).andExpect(status().isOk());

        mockMvc.perform(get(articlePath(boardId, deleting.articleId()))).andExpect(status().isNotFound());
        assertThat(readPage(boardId, 1, 10).articleCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("다른 게시판 경로로 조회·수정·삭제하면 404다")
    void otherBoardPathIsNotFound() throws Exception {
        long boardId = newBoard();
        long otherBoardId = newBoard();
        ArticleResponse written = write(boardId, 1L, "제목", "본문");
        String otherBoardPath = articlePath(otherBoardId, written.articleId());

        mockMvc.perform(get(otherBoardPath)).andExpect(status().isNotFound());
        mockMvc.perform(put(otherBoardPath)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"x\",\"content\":\"x\"}"))
                .andExpect(status().isNotFound());
        mockMvc.perform(delete(otherBoardPath)).andExpect(status().isNotFound());

        assertThat(read(boardId, written.articleId()).title()).isEqualTo("제목");
        assertThat(readPage(boardId, 1, 10).articleCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("없는 게시글을 조회·수정·삭제하면 404다")
    void missingArticleIsNotFound() throws Exception {
        long boardId = newBoard();
        String missingPath = articlePath(boardId, 123_456_789L);

        mockMvc.perform(get(missingPath)).andExpect(status().isNotFound());
        mockMvc.perform(put(missingPath)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"x\",\"content\":\"x\"}"))
                .andExpect(status().isNotFound());
        mockMvc.perform(delete(missingPath)).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("페이지 번호 목록은 최신순이고 페이지 경계가 정확하며 게시판 게시글 수를 함께 준다")
    void pageListIsNewestFirstWithExactBoundaries() throws Exception {
        long boardId = newBoard();
        List<Long> writtenIds = writeMany(boardId, 7);

        ArticlePageResponse firstPage = readPage(boardId, 1, 3);
        ArticlePageResponse secondPage = readPage(boardId, 2, 3);
        ArticlePageResponse lastPage = readPage(boardId, 3, 3);
        ArticlePageResponse beyondLastPage = readPage(boardId, 4, 3);

        List<Long> newestFirstIds = newestFirst(writtenIds);
        assertThat(idsOf(firstPage.articles())).containsExactlyElementsOf(newestFirstIds.subList(0, 3));
        assertThat(idsOf(secondPage.articles())).containsExactlyElementsOf(newestFirstIds.subList(3, 6));
        assertThat(idsOf(lastPage.articles())).containsExactlyElementsOf(newestFirstIds.subList(6, 7));
        assertThat(beyondLastPage.articles()).isEmpty();
        assertThat(firstPage.articleCount()).isEqualTo(7);
    }

    @Test
    @DisplayName("페이지 번호 목록은 다른 게시판 게시글을 섞지 않는다")
    void pageListDoesNotMixBoards() throws Exception {
        long boardId = newBoard();
        long otherBoardId = newBoard();
        List<Long> boardArticleIds = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            boardArticleIds.add(write(boardId, 1L, "게시판 글 " + i, "본문").articleId());
            write(otherBoardId, 1L, "다른 게시판 글 " + i, "본문");
        }

        ArticlePageResponse page = readPage(boardId, 1, 10);

        assertThat(idsOf(page.articles())).containsExactlyElementsOf(newestFirst(boardArticleIds));
        assertThat(page.articles()).allSatisfy(article -> assertThat(article.boardId()).isEqualTo(boardId));
        assertThat(page.articleCount()).isEqualTo(3);
    }

    @Test
    @DisplayName("커서 목록은 lastArticleId가 없으면 첫 페이지, 있으면 그보다 작은 ID만 준다")
    void scrollStartsFromNewestAndContinuesBelowCursor() throws Exception {
        long boardId = newBoard();
        List<Long> newestFirstIds = newestFirst(writeMany(boardId, 5));

        List<ArticleResponse> firstScroll = readScroll(boardId, 2, null);
        List<ArticleResponse> nextScroll = readScroll(boardId, 2, lastOf(firstScroll).articleId());

        assertThat(idsOf(firstScroll)).containsExactlyElementsOf(newestFirstIds.subList(0, 2));
        assertThat(idsOf(nextScroll)).containsExactlyElementsOf(newestFirstIds.subList(2, 4));
        assertThat(idsOf(nextScroll)).allSatisfy(id -> assertThat(id).isLessThan(lastOf(firstScroll).articleId()));
    }

    @Test
    @DisplayName("커서로 끝까지 이어 읽은 결과는 페이지 번호로 전부 읽은 결과와 같다")
    void scrollToEndMatchesAllPages() throws Exception {
        long boardId = newBoard();
        writeMany(boardId, 11);

        List<Long> scrolledIds = new ArrayList<>();
        Long lastArticleId = null;
        while (true) {
            List<ArticleResponse> scroll = readScroll(boardId, 4, lastArticleId);
            if (scroll.isEmpty()) {
                break;
            }
            scrolledIds.addAll(idsOf(scroll));
            lastArticleId = lastOf(scroll).articleId();
        }
        List<Long> pagedIds = new ArrayList<>();
        for (int page = 1; page <= 3; page++) {
            pagedIds.addAll(idsOf(readPage(boardId, page, 4).articles()));
        }

        assertThat(scrolledIds).hasSize(11).containsExactlyElementsOf(pagedIds);
    }

    @Test
    @DisplayName("같은 게시판에 동시에 여러 건을 생성해도 게시판 게시글 수가 정확하다")
    void concurrentWritesKeepExactCount() throws Exception {
        long boardId = newBoard();
        int writerCount = 20;

        ExecutorService executor = Executors.newFixedThreadPool(writerCount);
        CountDownLatch startSignal = new CountDownLatch(1);
        List<Future<ArticleResponse>> writings = new ArrayList<>();
        for (int i = 0; i < writerCount; i++) {
            long writerId = i;
            writings.add(executor.submit(() -> {
                startSignal.await();
                return write(boardId, writerId, "동시 글", "본문");
            }));
        }
        startSignal.countDown();
        List<Throwable> failures = new ArrayList<>();
        for (Future<ArticleResponse> writing : writings) {
            try {
                writing.get(30, TimeUnit.SECONDS);
            } catch (ExecutionException failure) {
                failures.add(failure.getCause());
            }
        }
        executor.shutdown();

        assertThat(failures).isEmpty();
        ArticlePageResponse page = readPage(boardId, 1, 50);
        assertThat(page.articleCount()).isEqualTo(writerCount);
        assertThat(page.articles()).hasSize(writerCount);
    }

    private long newBoard() {
        return BOARD_SEQUENCE.incrementAndGet();
    }

    private String articlePath(long boardId, long articleId) {
        return "/v1/boards/" + boardId + "/articles/" + articleId;
    }

    private ArticleResponse write(long boardId, long writerId, String title, String content) throws Exception {
        String body = objectMapper.writeValueAsString(new ArticleDtos.ArticleCreateRequest(writerId, title, content));
        MvcResult result = mockMvc.perform(post("/v1/boards/" + boardId + "/articles")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readValue(result.getResponse().getContentAsString(), ArticleResponse.class);
    }

    private List<Long> writeMany(long boardId, int count) throws Exception {
        List<Long> writtenIds = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            writtenIds.add(write(boardId, 1L, "글 " + i, "본문 " + i).articleId());
        }
        return writtenIds;
    }

    private ArticleResponse read(long boardId, long articleId) throws Exception {
        MvcResult result = mockMvc.perform(get(articlePath(boardId, articleId)))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readValue(result.getResponse().getContentAsString(), ArticleResponse.class);
    }

    private ArticlePageResponse readPage(long boardId, long page, long pageSize) throws Exception {
        MvcResult result = mockMvc.perform(get("/v1/boards/" + boardId + "/articles")
                        .param("page", String.valueOf(page))
                        .param("pageSize", String.valueOf(pageSize)))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readValue(result.getResponse().getContentAsString(), ArticlePageResponse.class);
    }

    private List<ArticleResponse> readScroll(long boardId, long pageSize, Long lastArticleId) throws Exception {
        var request = get("/v1/boards/" + boardId + "/articles/infinite-scroll")
                .param("pageSize", String.valueOf(pageSize));
        if (lastArticleId != null) {
            request.param("lastArticleId", String.valueOf(lastArticleId));
        }
        MvcResult result = mockMvc.perform(request).andExpect(status().isOk()).andReturn();
        return objectMapper.readValue(result.getResponse().getContentAsString(), new TypeReference<>() {
        });
    }

    private List<Long> newestFirst(List<Long> writtenIds) {
        List<Long> newestFirst = new ArrayList<>(writtenIds);
        Collections.reverse(newestFirst);
        return newestFirst;
    }

    private ArticleResponse lastOf(List<ArticleResponse> articles) {
        return articles.get(articles.size() - 1);
    }

    private List<Long> idsOf(List<ArticleResponse> articles) {
        return articles.stream().map(ArticleResponse::articleId).toList();
    }
}

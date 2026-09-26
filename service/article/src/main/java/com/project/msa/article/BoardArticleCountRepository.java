package com.project.msa.article;

import java.util.Optional;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/** 게시판 게시글 수는 읽어서 더하지 않고 행 락 아래에서 원자적으로 증감한다 (D5). */
interface BoardArticleCountRepository extends Repository<BoardArticleCount, Long> {

    /** 같은 트랜잭션의 증감 직후 누적값을 영속성 컨텍스트가 아니라 DB에서 읽는다. */
    @Query(value = "SELECT article_count FROM board_article_count WHERE board_id = :boardId", nativeQuery = true)
    Optional<Long> findArticleCount(@Param("boardId") Long boardId);

    @Modifying
    @Query(value = """
            INSERT INTO board_article_count (board_id, article_count) VALUES (:boardId, 1)
            ON DUPLICATE KEY UPDATE article_count = article_count + 1
            """, nativeQuery = true)
    void increase(@Param("boardId") Long boardId);

    @Modifying
    @Query(value = "UPDATE board_article_count SET article_count = article_count - 1 WHERE board_id = :boardId",
            nativeQuery = true)
    void decrease(@Param("boardId") Long boardId);
}

package com.project.msa.articleread;

import java.time.LocalDateTime;

/** 게시글 이벤트가 싣는 본문 묶음. 상세 Hash의 `article` 필드에 JSON으로 둔다. */
record ArticleBody(Long articleId, Long boardId, Long writerId, String title, String content,
                   LocalDateTime createdAt, LocalDateTime modifiedAt) {
}

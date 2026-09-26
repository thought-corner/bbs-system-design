package com.project.msa.hotarticle;

/** 클라이언트가 상세 경로(D3)를 만들 수 있게 boardId를 함께 준다. */
public record HotArticleResponse(Long articleId, Long boardId) {
}

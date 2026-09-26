package com.project.msa.hotarticle;

/** 점수에 들어가는 지표와 늦게 온 이벤트를 거르는 규칙 (D15). */
enum HotArticleMetric {

    /** 좋아요·댓글 수는 줄 수 있고, eventId가 카운트 행 락 아래에서 만들어져 카운트 순서와 같다. */
    LIKE("like-count", OrderRule.EVENT_ORDER),
    COMMENT("comment-count", OrderRule.EVENT_ORDER),
    /** 조회수는 줄지 않지만 백업 이벤트의 eventId는 카운트 순서를 보장하지 않는다. 큰 값을 남긴다. */
    VIEW("view-count", OrderRule.MAX);

    private final String keySuffix;
    private final OrderRule orderRule;

    HotArticleMetric(String keySuffix, OrderRule orderRule) {
        this.keySuffix = keySuffix;
        this.orderRule = orderRule;
    }

    String keySuffix() {
        return keySuffix;
    }

    OrderRule orderRule() {
        return orderRule;
    }

    enum OrderRule {
        EVENT_ORDER, MAX
    }
}

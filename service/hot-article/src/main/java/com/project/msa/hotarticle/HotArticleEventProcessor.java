package com.project.msa.hotarticle;

import com.project.msa.common.event.EventPayload;
import com.project.msa.common.eventdispatcher.EventProcessor;

/**
 * 이 서비스의 처리기 표시. 분배 규칙과 등록 시 타입 검사는 공용 {@link EventProcessor}·디스패처에 있다.
 *
 * @param <T> 맡은 이벤트 타입의 페이로드
 */
interface HotArticleEventProcessor<T extends EventPayload> extends EventProcessor<T> {
}

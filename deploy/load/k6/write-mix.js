// 실험 ①: 쓰기 혼합(글 10%·댓글 20%·좋아요 30%·조회 40%)을 계단식 도착률로 건다.
// 질문: 쓰기가 늘 때 이벤트 반영 지연(p95)·outbox 적체·컨슈머 랙이 어디서 벌어지는가, 끝나면 원본과 읽기 모델이 같은가.
import http from 'k6/http';
import { check } from 'k6';
import {
  BASE, HOT_SAMPLE, JSON_HEADERS, SYSTEM_TAGS, WRITE_THRESHOLDS, hotSample, runSalt, stairsWithWarm, uniqueUser,
  verifyConsistency, warmReadModel,
} from './lib.js';

const PLAN = stairsWithWarm('write_mix', __ENV.STAGES || '100:3m,200:3m,400:3m,800:3m');

export const options = {
  scenarios: PLAN.scenarios,
  thresholds: WRITE_THRESHOLDS,
  systemTags: SYSTEM_TAGS,
  setupTimeout: '10m',
  teardownTimeout: '10m',
};

// 검사 표본은 쓰기 전에 읽기 모델을 채워 둔다(이벤트가 반영되는지 봐야 하므로). 실험이 길면 끝나기 8분 전에 채운다(stairsWithWarm)
export function setup() {
  const sample = hotSample(HOT_SAMPLE);
  if (PLAN.warmAt === 0) {
    warmReadModel(sample);
  }
  return { sample, salt: runSalt() };
}

export function warm(data) {
  warmReadModel(data.sample);
}

export default function (data) {
  const article = data.sample[Math.floor(Math.random() * data.sample.length)];
  const user = uniqueUser(1000000000, data.salt);
  const pick = Math.random();
  let res;
  if (pick < 0.1) {
    res = http.post(`${BASE.article}/v1/boards/${article.board}/articles`,
      JSON.stringify({ writerId: user, title: 'k6 title', content: 'k6 content' }),
      Object.assign({ tags: { name: 'article_create' } }, JSON_HEADERS));
  } else if (pick < 0.3) {
    res = http.post(`${BASE.comment}/v1/articles/${article.id}/comments`,
      JSON.stringify({ writerId: user, content: 'k6 comment' }),
      Object.assign({ tags: { name: 'comment_create' } }, JSON_HEADERS));
  } else if (pick < 0.6) {
    res = http.post(`${BASE.like}/v1/articles/${article.id}/likes/users/${user}`, null, { tags: { name: 'like' } });
  } else {
    res = http.post(`${BASE.view}/v1/articles/${article.id}/views/users/${user}`, null, { tags: { name: 'view' } });
  }
  check(res, { '2xx': (r) => r.status >= 200 && r.status < 300 });
}

export function teardown(data) {
  verifyConsistency(data.sample);
}

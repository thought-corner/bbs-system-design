// 실험 ③: 한 글에 서로 다른 사용자의 좋아요·조회를 고정 횟수로 몰아 넣는다.
// 질문: 동시 요청에서 좋아요 수·조회 수가 성공 응답 수만큼 정확히 늘었는가(유실·중복 없음), 행 락 대기와 응답 시간.
// 조회는 사용자마다 10분 어뷰징 잠금이 있으므로 반복마다 다른 사용자를 쓴다.
import http from 'k6/http';
import { check } from 'k6';
import { Counter } from 'k6/metrics';
import {
  BASE, SYSTEM_TAGS, WRITE_THRESHOLDS, hotSample, runSalt, uniqueUser, verifyConsistency, warmReadModel,
} from './lib.js';

const likeOk = new Counter('contention_like_ok');
const viewOk = new Counter('contention_view_ok');

export const options = {
  scenarios: {
    hot_contention: {
      executor: 'shared-iterations',
      vus: Number(__ENV.CONTENTION_VUS || 200),
      iterations: Number(__ENV.CONTENTION_ITERATIONS || 20000),
      // setup에서 채운 읽기 모델이 논리 TTL(10분) 안에 검사되도록 짧게 끊는다
      maxDuration: '8m',
    },
  },
  thresholds: WRITE_THRESHOLDS,
  systemTags: SYSTEM_TAGS,
  setupTimeout: '5m',
  teardownTimeout: '5m',
};

function currentCounts(article) {
  // 아직 조회가 없으면 수가 비어 있을 수 있다 (0으로 본다)
  return {
    likes: http.get(`${BASE.like}/v1/articles/${article.id}/likes/count`, { tags: { name: 'verify_like' } }).json('likeCount') || 0,
    views: http.get(`${BASE.view}/v1/articles/${article.id}/views/count`, { tags: { name: 'verify_view' } }).json('viewCount') || 0,
  };
}

export function setup() {
  const target = hotSample(1)[0];
  warmReadModel([target]);
  return { target, before: currentCounts(target), salt: runSalt() };
}

export default function (data) {
  const user = uniqueUser(2000000000, data.salt);
  const like = http.post(`${BASE.like}/v1/articles/${data.target.id}/likes/users/${user}`, null, { tags: { name: 'like' } });
  if (check(like, { 'like 2xx': (r) => r.status >= 200 && r.status < 300 })) {
    likeOk.add(1);
  }
  const view = http.post(`${BASE.view}/v1/articles/${data.target.id}/views/users/${user}`, null, { tags: { name: 'view' } });
  if (check(view, { 'view 2xx': (r) => r.status >= 200 && r.status < 300 })) {
    viewOk.add(1);
  }
}

export function teardown(data) {
  verifyConsistency([data.target]);
}

// 성공 응답 수로 기대값을 계산해 실제 수와 비교한다. 결과는 요약 파일의 contention에 남고, 어긋나면 run.sh가 실패로 끝낸다
export function handleSummary(summary) {
  const data = summary.setup_data;
  const after = currentCounts(data.target);
  const likeSuccess = summary.metrics.contention_like_ok ? summary.metrics.contention_like_ok.values.count : 0;
  const viewSuccess = summary.metrics.contention_view_ok ? summary.metrics.contention_view_ok.values.count : 0;
  const contention = {
    article: data.target.id,
    likes: { before: data.before.likes, success: likeSuccess, after: after.likes, expected: data.before.likes + likeSuccess },
    views: { before: data.before.views, success: viewSuccess, after: after.views, expected: data.before.views + viewSuccess },
  };
  contention.ok = contention.likes.after === contention.likes.expected && contention.views.after === contention.views.expected;
  summary.contention = contention;
  summary.contention_ok = contention.ok;
  return {
    stdout: `contention ${JSON.stringify(contention)}\n`,
    [`/scripts/out/${__ENV.TESTID}-summary.json`]: JSON.stringify(summary),
  };
}

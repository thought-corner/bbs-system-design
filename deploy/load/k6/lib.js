// 세 실험이 함께 쓰는 설정·도우미. 서비스 주소는 앱 노드 NodePort다.
// ID는 2^53을 넘으므로 JSON 숫자로 파싱하지 않고 응답 본문에서 문자열로 꺼낸다.
import http from 'k6/http';
import { sleep } from 'k6';
import { Counter } from 'k6/metrics';

const APP = __ENV.APP_HOST;
export const BASE = {
  article: `http://${APP}:30081`,
  comment: `http://${APP}:30082`,
  like: `http://${APP}:30083`,
  view: `http://${APP}:30084`,
  hot: `http://${APP}:30085`,
  read: `http://${APP}:30086`,
};
export const BOARDS = Number(__ENV.SEED_BOARDS || 10);
export const HOT_SAMPLE = Number(__ENV.HOT_SAMPLE || 1000);
export const JSON_HEADERS = { headers: { 'Content-Type': 'application/json' } };

// 원본 수와 읽기 모델 수가 다른 게시글 수. 검사를 했다는 증거로 0이어도 반드시 더한다
export const consistencyMismatch = new Counter('consistency_mismatch');
// 검사하는 동안 article-read가 읽기 모델 대신 원본으로 간 조회 수(원본 다시 채우기·통과).
// 0이 아니면 그만큼은 원본에서 막 읽은 값과 원본을 비교한 것이라 정합성 검사가 되지 않았다
export const consistencyUnverified = new Counter('consistency_unverified');

// url 태그는 ID가 들어가 시계열이 폭발한다. 요청은 name 태그로 묶는다
export const SYSTEM_TAGS = ['status', 'method', 'name', 'scenario', 'expected_response', 'check'];

// 읽기 전용 실험의 임계값. 쓰기가 없으니 검사는 읽기 경로가 수를 흐트러뜨리지 않는지만 보고, 원본 경유 조회는 참고로 남긴다
export const THRESHOLDS = {
  http_req_failed: ['rate<0.01'],
  consistency_mismatch: ['count==0'],
};
// 쓰기가 있는 실험의 임계값. 검사가 읽기 모델을 실제로 읽었어야 한다
export const WRITE_THRESHOLDS = Object.assign({ consistency_unverified: ['count==0'] }, THRESHOLDS);

const PROMETHEUS = __ENV.PROMETHEUS_URL || 'http://prometheus:9090';

// 읽기 모델의 논리 TTL(article-read application.yaml의 logical-ttl). 이 시간이 지난 항목은 상세 조회가 원본에서 다시 채운다
const LOGICAL_TTL_SECONDS = Number(__ENV.LOGICAL_TTL_SECONDS || 600);
// 검사 표본을 처음 채우는 시점은 끝나기 이만큼 전이다. teardown 검사(대기 포함)가 채운 뒤 TTL 안에 끝나도록 TTL에서 2분을 뺀다.
// 읽기 모델은 논리 만료 전에는 다시 채우지 않으므로, 이미 채워진 항목을 "다시" 채울 수는 없다 — 처음 채우는 시점을 늦춘다
const WARM_BEFORE_END_SECONDS = LOGICAL_TTL_SECONDS - 120;

// "3m", "90s", "1h", "1m30s" → 초
export function seconds(duration) {
  let total = 0;
  const pattern = /(\d+)(h|m|s)/g;
  let match;
  while ((match = pattern.exec(duration)) !== null) {
    total += Number(match[1]) * { h: 3600, m: 60, s: 1 }[match[2]];
  }
  return total;
}

// 계단 시나리오와, 끝나기 WARM_BEFORE_END_SECONDS 전에 검사 표본을 처음 채우는 시나리오(warm).
// 적재 글은 읽기 모델에 없으므로 warm의 상세 조회가 원본에서 채우고, 만료 시각은 teardown 뒤가 된다.
// 계단이 그보다 짧으면 warmAt은 0이고 setup이 채운다. 이전 실험이 이미 채운 항목은 늦출 수 없으므로 검사가 consistency_unverified로 센다
export function stairsWithWarm(name, spec) {
  const main = stairs(spec);
  const total = main.stages.reduce((sum, stage) => sum + seconds(stage.duration), 0);
  const warmAt = Math.max(0, total - WARM_BEFORE_END_SECONDS);
  const scenarios = { [name]: main };
  if (warmAt > 0) {
    scenarios.warm = {
      executor: 'shared-iterations', vus: 1, iterations: 1, startTime: `${warmAt}s`, maxDuration: '5m', exec: 'warm',
    };
  }
  // 초기화 코드는 VU마다 돈다. 옵션을 읽는 첫 실행(__VU 0)에서만 남긴다
  if (__VU === 0) {
    console.log(`${name}: ${total}s, 검사 표본 채우기 ${warmAt > 0 ? `${warmAt}s` : 'setup'}, 논리 TTL ${LOGICAL_TTL_SECONDS}s`);
  }
  return { scenarios, warmAt };
}

// "초당건수:기간,..." → 계단. 단계마다 5초에 걸쳐 오른 뒤 그 기간 동안 유지한다
export function stairs(spec) {
  const steps = spec.split(',').map((step) => {
    const [rate, duration] = step.split(':');
    return { rate: Number(rate), duration };
  });
  const stages = [];
  for (const step of steps) {
    stages.push({ target: step.rate, duration: '5s' });
    stages.push({ target: step.rate, duration: step.duration });
  }
  return {
    executor: 'ramping-arrival-rate',
    startRate: steps[0].rate,
    timeUnit: '1s',
    preAllocatedVUs: Number(__ENV.PRE_VUS || 50),
    maxVUs: Number(__ENV.MAX_VUS || 400),
    stages,
  };
}

// 목록 응답에서 적재한 글(제목 "seed title …")의 ID만 문자열로 꺼낸다.
// 실험이 쓴 새 글이 최신 자리를 차지해도 표본은 댓글·좋아요를 몰아 준 적재 글로 남는다.
// 응답 필드 순서는 ArticleResponse 레코드 순서(articleId, boardId, writerId, title, …)를 따른다
export function seededArticleIds(body) {
  const ids = [];
  const pattern = /"articleId":(\d+),"boardId":\d+,"writerId":\d+,"title":"seed title /g;
  let match;
  while ((match = pattern.exec(body)) !== null) {
    ids.push(match[1]);
  }
  return ids;
}

// 인기 글 표본: 적재에서 댓글·좋아요를 몰아 준 가장 최근 적재 글들. 게시판마다 첫 페이지부터 고르게 모은다
export function hotSample(size) {
  const perBoard = Math.ceil(size / BOARDS);
  const sample = [];
  for (let board = 1; board <= BOARDS; board++) {
    let page = 1;
    let collected = 0;
    while (collected < perBoard) {
      const pageSize = 50;
      const res = http.get(`${BASE.article}/v1/boards/${board}/articles?page=${page}&pageSize=${pageSize}`,
        { tags: { name: 'setup_list' } });
      const body = res.body || '';
      if (res.status !== 200 || body.indexOf('"articleId"') < 0) {
        break;
      }
      const ids = seededArticleIds(body).slice(0, perBoard - collected);
      ids.forEach((id) => sample.push({ id, board }));
      collected += ids.length;
      page++;
    }
  }
  if (sample.length === 0) {
    throw new Error('인기 글 표본이 비었다 (적재를 먼저 한다)');
  }
  return sample.slice(0, size);
}

// 실행마다 다른 값. setup에서 한 번 정해 모든 VU가 함께 쓴다 (다시 돌려도 좋아요 중복·조회 잠금에 걸리지 않게)
export function runSalt() {
  return Math.floor(Date.now() / 1000) % 50000;
}

// 실행·VU·반복마다 겹치지 않는 사용자 ID (적재한 사용자 1~1000과도 겹치지 않는다). 2^53 안이다
export function uniqueUser(offset, salt) {
  return offset + salt * 100000000000 + __VU * 10000000 + __ITER;
}

function counts(article) {
  const [like, comment, read] = http.batch([
    ['GET', `${BASE.like}/v1/articles/${article.id}/likes/count`, null, { tags: { name: 'verify_like' } }],
    ['GET', `${BASE.comment}/v1/articles/${article.id}/comments/count`, null, { tags: { name: 'verify_comment' } }],
    ['GET', `${BASE.read}/v1/boards/${article.board}/articles/${article.id}`, null, { tags: { name: 'verify_read' } }],
  ]);
  if (like.status !== 200 || comment.status !== 200 || read.status !== 200) {
    return null;
  }
  const detail = read.json();
  return {
    likeOrigin: like.json('likeCount'),
    commentOrigin: comment.json('commentCount'),
    likeRead: detail.articleLikeCount,
    commentRead: detail.articleCommentCount,
  };
}

// article-read가 지금까지 원본으로 간 상세 조회 수 (Prometheus). 읽을 수 없으면 null
function originReads() {
  const res = http.get(`${PROMETHEUS}/api/v1/query?query=${encodeURIComponent(
    'sum(article_read_source_total{source!="read_model"})')}`, { tags: { name: 'verify_prometheus' } });
  if (res.status !== 200) {
    return null;
  }
  const result = res.json('data.result');
  return result && result.length ? Number(result[0].value[1]) : 0;
}

// Prometheus가 article-read를 긁는 간격(5초)보다 넉넉히 기다려 검사 중 조회까지 반영된 값을 읽는다
function originReadsSettled() {
  sleep(12);
  return originReads();
}

// 원본(like·comment 서비스)과 읽기 모델(article-read)의 수가 같아질 때까지 기다린 뒤, 끝내 다른 글 수를 센다.
// 읽기 모델은 warm(또는 setup)에서 원본으로 채웠고, 그 뒤의 변화는 이벤트로만 들어온다.
// 검사 조회가 읽기 모델 대신 원본으로 갔다면(논리 만료 뒤 다시 채우기 등) 그 수를 consistency_unverified로 센다.
export function verifyConsistency(sample) {
  const originBefore = originReadsSettled();
  const deadline = Date.now() + Math.min(Number(__ENV.CONSISTENCY_WAIT_SECONDS || 60), 90) * 1000;
  let mismatched = sample;
  while (true) {
    mismatched = mismatched.filter((article) => {
      const c = counts(article);
      return c === null || c.likeOrigin !== c.likeRead || c.commentOrigin !== c.commentRead;
    });
    if (mismatched.length === 0 || Date.now() > deadline) {
      break;
    }
    sleep(2);
  }
  consistencyMismatch.add(mismatched.length);
  const originAfter = originReadsSettled();
  const unverified = originBefore === null || originAfter === null ? sample.length : Math.max(0, originAfter - originBefore);
  consistencyUnverified.add(unverified);
  console.log(`consistency_mismatch=${mismatched.length} / ${sample.length}, consistency_unverified=${unverified}`
    + (mismatched.length ? ` 예: ${JSON.stringify(mismatched.slice(0, 3))}` : ''));
}

// 실험 전에 표본의 상세를 한 번씩 읽어 읽기 모델을 원본에서 채워 둔다
export function warmReadModel(sample) {
  for (let i = 0; i < sample.length; i += 20) {
    http.batch(sample.slice(i, i + 20).map((article) =>
      ['GET', `${BASE.read}/v1/boards/${article.board}/articles/${article.id}`, null, { tags: { name: 'setup_warm' } }]));
  }
}

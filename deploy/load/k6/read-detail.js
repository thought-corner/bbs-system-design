// 실험 ②: 상세 조회 90%·목록 10%. 상세는 인기 글 표본의 앞쪽에 몰린다(표본 크기 × 난수³).
// 적재 직후 읽기 모델이 빈 상태에서 시작해, 원본 채우기(차가운 캐시)에서 읽기 모델 적중(따뜻한 캐시)으로 넘어가는 모습을 본다.
// 질문: 계단마다 TPS·p95·p99, 읽기 모델 적중률, 갱신 락 경합, 원본 서비스로 새는 부하.
import http from 'k6/http';
import { check } from 'k6';
import {
  BASE, BOARDS, HOT_SAMPLE, SYSTEM_TAGS, THRESHOLDS, hotSample, stairs, verifyConsistency,
} from './lib.js';

export const options = {
  scenarios: { read_detail: stairs(__ENV.STAGES || '500:3m,1000:3m,2000:3m,4000:3m') },
  thresholds: THRESHOLDS,
  systemTags: SYSTEM_TAGS,
  setupTimeout: '10m',
  teardownTimeout: '10m',
};

// 읽기 모델을 채우지 않고 시작한다(적재 직후의 차가운 캐시).
// 쓰기가 없는 실험이라 teardown 검사는 읽기 경로가 수를 흐트러뜨리지 않는지만 보고, 원본 경유 검사 조회(consistency_unverified)는 참고로 남긴다
export function setup() {
  return { sample: hotSample(HOT_SAMPLE, 0) };
}

export default function (data) {
  let res;
  if (Math.random() < 0.9) {
    const article = data.sample[Math.floor(Math.pow(Math.random(), 3) * data.sample.length)];
    res = http.get(`${BASE.read}/v1/boards/${article.board}/articles/${article.id}`, { tags: { name: 'read_detail' } });
  } else {
    const board = 1 + Math.floor(Math.random() * BOARDS);
    const page = 1 + Math.floor(Math.random() * 10);
    res = http.get(`${BASE.read}/v1/boards/${board}/articles?page=${page}&pageSize=20`, { tags: { name: 'read_list' } });
  }
  check(res, { '2xx': (r) => r.status >= 200 && r.status < 300 });
}

export function teardown(data) {
  verifyConsistency(data.sample);
}

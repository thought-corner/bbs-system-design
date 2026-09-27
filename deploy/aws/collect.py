#!/usr/bin/env python3
"""k6 실험 하나(testid)의 계단별 수치를 Prometheus에서 뽑아 표와 JSON으로 낸다.

    deploy/aws/collect.py v1.1.0-write-mix --stages 100,200,400,800
    deploy/aws/collect.py v1.1.0-hot-contention            # 계단이 없으면 실험 전체 한 구간

Prometheus는 부하 노드 Grafana의 데이터 소스 프록시로 읽는다(Prometheus 포트는 밖에 열지 않는다).
주소·비밀번호는 Terraform 출력과 SSM 파라미터에서 읽고, GRAFANA_URL·GRAFANA_PASSWORD로 바꿀 수 있다.
각 계단은 첫 반복부터 센 k6 계단(단계마다 5초 램프 + 유지)의 유지 구간 끝 --tail초를 평균·최댓값으로 요약한다.
k6 응답 시간 백분위는 native histogram에서 구간마다 계산한다(트렌드 통계는 실험 시작부터의 누적값이라 쓰지 않는다).
v1.0.0 수집과 같은 이름·정의를 쓴다. 바뀐 것은 컨슈머 랙(kafka-exporter 컨슈머 그룹 랙 → 클라이언트 records-lag-max)과 k6 백분위뿐이다.
"""
import argparse
import base64
import json
import os
import subprocess
import sys
import urllib.parse
import urllib.request

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), '..', '..'))
BAD = ('NaN', '+Inf', '-Inf')


def grafana_access():
    url = os.environ.get('GRAFANA_URL') or subprocess.check_output(
        ['terraform', f'-chdir={ROOT}/infra/aws', 'output', '-raw', 'grafana_url'], text=True).strip()
    password = os.environ.get('GRAFANA_PASSWORD')
    if not password:
        names = json.loads(subprocess.check_output(
            ['terraform', f'-chdir={ROOT}/infra/aws', 'output', '-json', 'secret_parameter_names'], text=True))
        password = subprocess.check_output(
            ['aws', 'ssm', 'get-parameter', '--name', names['grafana_admin_password'], '--with-decryption',
             '--query', 'Parameter.Value', '--output', 'text'], text=True).strip()
    return url.rstrip('/'), 'Basic ' + base64.b64encode(f'admin:{password}'.encode()).decode()


class Prometheus:

    def __init__(self, url, auth):
        self.base = f'{url}/api/datasources/proxy/uid/prometheus/api/v1/'
        self.auth = auth

    def api(self, path, params):
        request = urllib.request.Request(self.base + path + '?' + urllib.parse.urlencode(params),
                                         headers={'Authorization': self.auth})
        return json.load(urllib.request.urlopen(request, timeout=30))['data']

    def series(self, expr, start, end, step=5):
        return self.api('query_range', {'query': expr, 'start': start, 'end': end, 'step': step})['result']

    def summary(self, expr, start, end):
        values = [float(v[1]) for r in self.series(expr, start, end) for v in r['values'] if v[1] not in BAD]
        return [sum(values) / len(values), max(values)] if values else [None, None]

    def by_label(self, expr, label, start, end):
        out = {}
        for r in self.series(expr, start, end):
            values = [float(v[1]) for v in r['values'] if v[1] not in BAD]
            if values:
                out[r['metric'].get(label, '?')] = [sum(values) / len(values), max(values)]
        return out


def queries(testid, rate):
    t = f'testid="{testid}"'
    r = f'[{rate}]'
    values = [
        ('k6 TPS', f'sum(rate(k6_http_reqs_total{{{t}}}{r}))'),
        ('k6 실패율', f'max(k6_http_req_failed_rate{{{t}}})'),
        ('k6 버린 반복/s', f'sum(rate(k6_dropped_iterations_total{{{t}}}{r}))'),
        ('k6 VU', f'max(k6_vus{{{t}}})'),
        ('앱 CPU', f'1 - avg(rate(node_cpu_seconds_total{{node="app",mode="idle"}}{r}))'),
        ('데이터 CPU', f'1 - avg(rate(node_cpu_seconds_total{{node="data",mode="idle"}}{r}))'),
        ('데이터 iowait', f'avg(rate(node_cpu_seconds_total{{node="data",mode="iowait"}}{r}))'),
        ('부하 CPU', f'1 - avg(rate(node_cpu_seconds_total{{node="load",mode="idle"}}{r}))'),
        ('데이터 가용 메모리 GiB', 'node_memory_MemAvailable_bytes{node="data"} / 2^30'),
        ('앱 가용 메모리 GiB', 'node_memory_MemAvailable_bytes{node="app"} / 2^30'),
        ('읽기 모델 적중률', f'sum(rate(article_read_source_total{{source="read_model"}}{r})) / clamp_min(sum(rate(article_read_source_total{r})), 1e-9)'),
        ('원본 채우기/s', f'sum(rate(article_read_source_total{{source="origin_refresh"}}{r}))'),
        ('원본 통과/s', f'sum(rate(article_read_source_total{{source="origin_passthrough"}}{r}))'),
        ('갱신 락 실패율', f'sum(rate(article_read_refresh_lock_total{{acquired="false"}}{r})) / clamp_min(sum(rate(article_read_refresh_lock_total{r})), 1e-9)'),
        ('MySQL 조회/s', f'sum(rate(mysql_global_status_queries{r}))'),
        ('MySQL 행 락 대기/s', f'sum(rate(mysql_global_status_innodb_row_lock_waits{r}))'),
        ('MySQL 버퍼 풀 적중률', f'1 - sum(rate(mysql_global_status_innodb_buffer_pool_reads{r})) / clamp_min(sum(rate(mysql_global_status_innodb_buffer_pool_read_requests{r})), 1e-9)'),
        ('Redis 명령/s', f'sum(rate(redis_commands_processed_total{r}))'),
        ('Redis 메모리 MiB', 'sum(redis_memory_used_bytes) / 2^20'),
        ('outbox 남은 행(합)', 'sum(outbox_pending)'),
        ('outbox 발행/s', f'sum(rate(outbox_publish_total{{result="success"}}{r}))'),
        ('컨슈머 랙(최대)', 'max(kafka_consumer_fetch_manager_records_lag_max)'),
    ]
    per_app = [
        ('서버 p95 s', f'histogram_quantile(0.95, sum by (application, le) (rate(http_server_requests_seconds_bucket{{uri!~"/actuator.*"}}{r})))'),
        ('서버 p99 s', f'histogram_quantile(0.99, sum by (application, le) (rate(http_server_requests_seconds_bucket{{uri!~"/actuator.*"}}{r})))'),
        ('서버 요청/s', f'sum by (application) (rate(http_server_requests_seconds_count{{uri!~"/actuator.*"}}{r}))'),
        ('서버 5xx/s', f'sum by (application) (rate(http_server_requests_seconds_count{{uri!~"/actuator.*",status=~"5.."}}{r}))'),
        ('Tomcat 사용 스레드(최대)', 'max by (application) (tomcat_threads_busy_threads)'),
        ('Hikari 대기(최대)', 'max by (application) (hikaricp_connections_pending)'),
        ('반영 지연 p95 s', f'histogram_quantile(0.95, sum by (application, le) (rate(event_consume_lag_seconds_bucket{r})))'),
        ('GC 멈춤 s/s', f'sum by (application) (rate(jvm_gc_pause_seconds_sum{r}))'),
        ('outbox 발행/s', f'sum by (application) (rate(outbox_publish_total{{result="success"}}{r}))'),
        ('컨슈머 랙', 'max by (application) (kafka_consumer_fetch_manager_records_lag_max)'),
    ]
    per_name = [
        ('k6 p95 s', f'histogram_quantile(0.95, sum by (name) (rate(k6_http_req_duration_seconds{{{t}}}{r})))'),
        ('k6 p99 s', f'histogram_quantile(0.99, sum by (name) (rate(k6_http_req_duration_seconds{{{t}}}{r})))'),
        ('k6 요청/s', f'sum by (name) (rate(k6_http_reqs_total{{{t}}}{r}))'),
    ]
    return values, per_app, per_name


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('testid')
    parser.add_argument('--stages', default='', help='계단 목표 초당건수 (예: 100,200,400,800). 없으면 실험 전체 한 구간')
    parser.add_argument('--step', type=int, default=185, help='계단 하나의 길이(초): 램프 5초 + 유지 (기본 185)')
    parser.add_argument('--tail', type=int, default=120, help='계단 유지 구간 끝에서 요약할 길이(초) (기본 120)')
    parser.add_argument('--rate', default='1m', help='rate 창 (기본 1m)')
    parser.add_argument('--out', default=None, help='JSON 경로 (기본 build/perf/<testid>.json)')
    args = parser.parse_args()

    prometheus = Prometheus(*grafana_access())
    now = int(float(prometheus.api('query', {'query': 'time()'})['result'][1]))
    # 실험 구간을 넓은 간격으로 찾은 뒤(질의 한 번에 점 1만 개 한도), 그 안에서 첫 반복을 1초 간격으로 찾는다.
    # 계단은 첫 반복부터 센다. setup(표본 수집·읽기 모델 채우기)은 반복이 아니라서 k6_vus보다 늦게 시작한다
    vus = prometheus.series(f'max(k6_vus{{testid="{args.testid}"}})', now - 6 * 3600, now, 15)
    if not vus:
        sys.exit(f'{args.testid}: k6 지표가 없다')
    coarse = [int(float(t)) for t, _ in vus[0]['values']]
    iterations = prometheus.series(f'sum(k6_iterations_total{{testid="{args.testid}"}})',
                                   coarse[0] - 15, coarse[-1] + 15, 1)
    if not iterations:
        sys.exit(f'{args.testid}: k6 반복 지표가 없다')
    points = [int(float(t)) for t, _ in iterations[0]['values']]
    start, end = points[0], points[-1]

    targets = [int(x) for x in args.stages.split(',') if x]
    windows = [(f'{rps}/s', start + (i + 1) * args.step - args.tail, start + (i + 1) * args.step)
               for i, rps in enumerate(targets)] or [('전체', start, end)]

    values, per_app, per_name = queries(args.testid, args.rate)
    result = {'testid': args.testid, 'start': start, 'end': end, 'windows': []}
    for label, s, e in windows:
        result['windows'].append({
            'window': label, 'start': s, 'end': e,
            'values': {name: prometheus.summary(expr, s, e) for name, expr in values},
            'per_app': {name: prometheus.by_label(expr, 'application', s, e) for name, expr in per_app},
            'per_name': {name: prometheus.by_label(expr, 'name', s, e) for name, expr in per_name},
        })

    def fmt(value):
        return '-' if value is None else f'{value:.3g}'

    print(f'# {args.testid}: {start} ~ {end} ({end - start}s)')
    for w in result['windows']:
        print(f"\n## {w['window']} ({w['start']}~{w['end']})")
        for name, (avg, peak) in w['values'].items():
            print(f'  {name}: 평균 {fmt(avg)} / 최대 {fmt(peak)}')
        for name, groups in list(w['per_name'].items()) + list(w['per_app'].items()):
            if groups:
                print(f'  {name}: ' + ', '.join(f'{g}={fmt(a)}(최대 {fmt(m)})' for g, (a, m) in sorted(groups.items())))

    out = args.out or os.path.join(ROOT, 'build', 'perf', f'{args.testid}.json')
    os.makedirs(os.path.dirname(os.path.abspath(out)), exist_ok=True)
    with open(out, 'w', encoding='utf-8') as f:
        json.dump(result, f, ensure_ascii=False, indent=1)


if __name__ == '__main__':
    main()

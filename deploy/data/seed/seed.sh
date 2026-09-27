#!/bin/sh
# 실측용 데이터를 MySQL 안에서 SQL로 만든다 (데이터 노드에서 실행: deploy/aws/seed.sh 또는 게이트의 docker exec).
# 이벤트를 거치지 않으므로 읽기 모델(Redis)은 비어 있고, 첫 상세 조회가 원본 채우기로 간다.
#
# 규모 (환경 변수):
#   SEED_BOARDS               게시판 수 (기본 10)
#   SEED_ARTICLES_PER_BOARD   게시판당 게시글 (기본 1,000,000)
#   SEED_HOT_ARTICLES         댓글·좋아요를 몰아 줄 최근 게시글 수 (기본 100,000)
#   SEED_COMMENTS_PER_HOT     인기 글당 댓글 (기본 100, 80%는 루트·20%는 루트 아래 답글)
#   SEED_LIKES_PER_HOT        인기 글당 좋아요 (기본 100)
#
# ID는 Snowflake 배치를 따른다: (시각 - 2026-01-01T00:00:00Z) << 22 | node-id << 12 | 순번.
# 적재 전용 node-id는 게시글 1000·댓글 1001·좋아요 1002 (서비스 100~499와 겹치지 않는다).
# 게시글 시각은 어제까지 최대 365일(Snowflake 시작 시각 이후)에 고르게 퍼지고, 처음 정한 시작 시각을 seed_util.meta에 남겨 다시 실행해도 같은 ID가 나온다.
# 그래서 중간에 끊겨도 다시 실행하면 INSERT IGNORE로 빠진 행만 채운다.
set -eu
cd "$(dirname "$0")"
: "${MYSQL_ROOT_PASSWORD:?MYSQL_ROOT_PASSWORD가 없다}"
# docker exec -e MYSQL_PWD 는 값 없이 이름만 넘겨 이 환경에서 읽게 한다 (프로세스 인자에 비밀번호가 보이지 않게)
MYSQL_PWD="$MYSQL_ROOT_PASSWORD"
export MYSQL_PWD
BOARDS="${SEED_BOARDS:-10}"
PER_BOARD="${SEED_ARTICLES_PER_BOARD:-1000000}"
HOT="${SEED_HOT_ARTICLES:-100000}"
COMMENTS="${SEED_COMMENTS_PER_HOT:-100}"
LIKES="${SEED_LIKES_PER_HOT:-100}"
MYSQL_CONTAINER="${MYSQL_CONTAINER:-board-data-mysql-1}"
ARTICLES=$((BOARDS * PER_BOARD))
CHUNK=100000
EPOCH_MILLIS=1767225600000
[ "$HOT" -le "$ARTICLES" ] || { echo "SEED_HOT_ARTICLES(${HOT})가 게시글 수(${ARTICLES})보다 크다" >&2; exit 1; }
[ "$COMMENTS" -le 1000 ] && [ "$LIKES" -le 1000 ] || { echo "인기 글당 댓글·좋아요는 1,000개까지다 (게시글 시각 간격 안에 ID 시각을 둔다)" >&2; exit 1; }

mysql_run() { docker exec -i -e MYSQL_PWD "$MYSQL_CONTAINER" mysql -uroot -N -B "$@"; }
scalar() { mysql_run -e "$1"; }
started=$(date +%s)
log() { echo "[$(( $(date +%s) - started ))s] $*"; }

log "서비스 스키마 적용"
for schema in article comment article_like article_view; do
  mysql_run "$schema" < "schema/${schema}.sql"
done

# 시작 시각·간격을 한 번 정해 남긴다. 규모가 바뀌면 같은 ID를 만들 수 없으니 멈춘다
mysql_run <<SQL
CREATE DATABASE IF NOT EXISTS seed_util;
CREATE TABLE IF NOT EXISTS seed_util.meta (k VARCHAR(32) PRIMARY KEY, v BIGINT NOT NULL);
CREATE TABLE IF NOT EXISTS seed_util.seq (n INT PRIMARY KEY);
SET SESSION cte_max_recursion_depth = ${CHUNK};
INSERT IGNORE INTO seed_util.seq (n)
  WITH RECURSIVE s (n) AS (SELECT 0 UNION ALL SELECT n + 1 FROM s WHERE n < ${CHUNK} - 1) SELECT n FROM s;
SET time_zone = '+00:00';
SET @last = UNIX_TIMESTAMP(UTC_DATE()) * 1000 - 1000;
-- Snowflake 시작 시각보다 이른 시각은 ID로 만들 수 없다 (음수가 된다)
SET @step = FLOOR(LEAST(365 * 86400000, @last - ${EPOCH_MILLIS}) / ${ARTICLES});
INSERT IGNORE INTO seed_util.meta (k, v) VALUES
  ('articles', ${ARTICLES}), ('boards', ${BOARDS}), ('step', @step), ('start', @last - (${ARTICLES} - 1) * @step);
SQL
[ "$(scalar "SELECT v FROM seed_util.meta WHERE k = 'articles'")" = "$ARTICLES" ] \
  && [ "$(scalar "SELECT v FROM seed_util.meta WHERE k = 'boards'")" = "$BOARDS" ] \
  || { echo "이전 적재와 규모가 다르다 (seed_util.meta). 데이터 볼륨을 비우고 다시 적재한다" >&2; exit 1; }
STEP="$(scalar "SELECT v FROM seed_util.meta WHERE k = 'step'")"
START="$(scalar "SELECT v FROM seed_util.meta WHERE k = 'start'")"
[ "$STEP" -gt "$COMMENTS" ] || { echo "게시글 시각 간격(${STEP}ms)이 댓글 수보다 작다" >&2; exit 1; }

# 게시글 i의 시각(ms)과 ID. i는 0부터, 게시판은 i % BOARDS + 1
article_ms() { echo "(${START} + ($1) * ${STEP})"; }
snowflake() { echo "((($1) - ${EPOCH_MILLIS}) << 22 | ($2 << 12))"; }
# 0 <= x < 62^5 을 Base62 5자로 (CommentPath와 같은 문자 순서)
b62() {
  echo "CONCAT(SUBSTRING(@cs, FLOOR(($1) / 14776336) % 62 + 1, 1), SUBSTRING(@cs, FLOOR(($1) / 238328) % 62 + 1, 1), SUBSTRING(@cs, FLOOR(($1) / 3844) % 62 + 1, 1), SUBSTRING(@cs, FLOOR(($1) / 62) % 62 + 1, 1), SUBSTRING(@cs, ($1) % 62 + 1, 1))"
}
seeded() { scalar "SELECT COUNT(*) FROM $1 WHERE (($2 >> 12) & 1023) = $3"; }
# 세션 공통: UTC, binlog 끄기(적재는 복제·복구 대상이 아니다)
SESSION="SET time_zone = '+00:00'; SET sql_log_bin = 0; SET @cs = '0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz';"

if [ "$(seeded article.article article_id 1000)" -ge "$ARTICLES" ]; then
  log "게시글: 이미 ${ARTICLES}건"
else
  log "게시글 ${ARTICLES}건 적재"
  {
    echo "$SESSION"
    offset=0
    while [ "$offset" -lt "$ARTICLES" ]; do
      i="(${offset} + s.n)"
      cat <<SQL
INSERT IGNORE INTO article.article (article_id, board_id, writer_id, title, content, created_at, modified_at)
SELECT $(snowflake "$(article_ms "$i")" 1000), ${i} % ${BOARDS} + 1, ${i} % 100000 + 1,
       CONCAT('seed title ', ${i}), CONCAT('seed content ', ${i}, ' ', REPEAT('x', 200)),
       FROM_UNIXTIME(FLOOR($(article_ms "$i") / 1000)), FROM_UNIXTIME(FLOOR($(article_ms "$i") / 1000))
FROM seed_util.seq s WHERE s.n < LEAST(${CHUNK}, ${ARTICLES} - ${offset});
SQL
      offset=$((offset + CHUNK))
    done
  } | mysql_run
fi

# 인기 글 k(0..HOT-1)는 게시글 i = ARTICLES - HOT + k. 댓글·좋아요 j의 시각은 게시글 시각 + 1 + j (ms)
HOT_PER_CHUNK=$((CHUNK / (COMMENTS > LIKES ? COMMENTS : LIKES)))
[ "$HOT_PER_CHUNK" -ge 1 ] || HOT_PER_CHUNK=1
ROOTS=$((COMMENTS - COMMENTS / 5))
if [ "$(seeded comment.comment comment_id 1001)" -ge $((HOT * COMMENTS)) ]; then
  log "댓글: 이미 $((HOT * COMMENTS))건"
else
  log "댓글 $((HOT * COMMENTS))건 적재 (루트 ${ROOTS}·답글 $((COMMENTS - ROOTS)) / 글)"
  {
    echo "$SESSION"
    k0=0
    while [ "$k0" -lt "$HOT" ]; do
      i="(${ARTICLES} - ${HOT} + ${k0} + a.n)"
      ms="($(article_ms "$i") + 1 + b.n)"
      cat <<SQL
INSERT IGNORE INTO comment.comment (comment_id, article_id, writer_id, content, path, deleted, created_at)
SELECT $(snowflake "$ms" 1001), $(snowflake "$(article_ms "$i")" 1000), b.n + 1, CONCAT('seed comment ', b.n),
       IF(b.n < ${ROOTS}, $(b62 "b.n"), CONCAT($(b62 "b.n - ${ROOTS}"), '00000')),
       FALSE, FROM_UNIXTIME(FLOOR(${ms} / 1000))
FROM seed_util.seq a JOIN seed_util.seq b ON b.n < ${COMMENTS}
WHERE a.n < LEAST(${HOT_PER_CHUNK}, ${HOT} - ${k0});
SQL
      k0=$((k0 + HOT_PER_CHUNK))
    done
  } | mysql_run
fi

if [ "$(seeded article_like.article_like article_like_id 1002)" -ge $((HOT * LIKES)) ]; then
  log "좋아요: 이미 $((HOT * LIKES))건"
else
  log "좋아요 $((HOT * LIKES))건 적재"
  {
    echo "$SESSION"
    k0=0
    while [ "$k0" -lt "$HOT" ]; do
      i="(${ARTICLES} - ${HOT} + ${k0} + a.n)"
      ms="($(article_ms "$i") + 1 + b.n)"
      cat <<SQL
INSERT IGNORE INTO article_like.article_like (article_like_id, article_id, user_id, created_at)
SELECT $(snowflake "$ms" 1002), $(snowflake "$(article_ms "$i")" 1000), b.n + 1, FROM_UNIXTIME(FLOOR(${ms} / 1000))
FROM seed_util.seq a JOIN seed_util.seq b ON b.n < ${LIKES}
WHERE a.n < LEAST(${HOT_PER_CHUNK}, ${HOT} - ${k0});
SQL
      k0=$((k0 + HOT_PER_CHUNK))
    done
  } | mysql_run
fi

# 카운트 테이블을 실제 행 수로 맞춘다 (다시 실행해도 같은 값). 게시판 수는 전체를, 댓글·좋아요 수는 적재한 글만 센다
log "카운트 테이블 맞추기"
mysql_run <<SQL
SET sql_log_bin = 0;
INSERT INTO article.board_article_count (board_id, article_count)
SELECT * FROM (SELECT board_id, COUNT(*) AS n FROM article.article GROUP BY board_id) AS actual
ON DUPLICATE KEY UPDATE article_count = actual.n;
INSERT INTO comment.article_comment_count (article_id, comment_count)
SELECT * FROM (SELECT article_id, COUNT(*) AS n FROM comment.comment
               WHERE ((article_id >> 12) & 1023) = 1000 GROUP BY article_id) AS actual
ON DUPLICATE KEY UPDATE comment_count = actual.n;
INSERT INTO article_like.article_like_count (article_id, like_count)
SELECT * FROM (SELECT article_id, COUNT(*) AS n FROM article_like.article_like
               WHERE ((article_id >> 12) & 1023) = 1000 GROUP BY article_id) AS actual
ON DUPLICATE KEY UPDATE like_count = actual.n;
SQL

log "완료: 게시글 $(seeded article.article article_id 1000) · 댓글 $(seeded comment.comment comment_id 1001) · 좋아요 $(seeded article_like.article_like article_like_id 1002)"

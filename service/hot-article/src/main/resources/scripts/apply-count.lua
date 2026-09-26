-- 게시글 하나의 지표 카운터를 반영하고 점수를 다시 계산해 랭킹에 넣는다.
-- 반영과 재계산을 한 스크립트로 묶어야 다른 토픽을 처리하는 스레드끼리 옛 점수로 덮어쓰지 않는다.
-- KEYS: 1 반영할 카운터, 2 그 카운터의 last-event-id, 3 좋아요 수, 4 댓글 수, 5 조회수, 6 랭킹, 7 생성일
-- ARGV: 1 누적값, 2 eventId, 3 순서 규칙(EVENT_ORDER|MAX), 4 TTL(초), 5~7 가중치(좋아요·댓글·조회), 8 articleId, 9 남길 순위 수

local function is_newer(event_id, last_event_id)
  -- eventId는 2^53을 넘어 Lua 숫자로 비교하면 정밀도를 잃는다. 앞자리 0이 없는 10진 문자열이라 길이 → 사전순으로 비교한다
  if #event_id ~= #last_event_id then
    return #event_id > #last_event_id
  end
  return event_id > last_event_id
end

if redis.call('EXISTS', KEYS[7]) == 0 then
  return 0
end

local count = tonumber(ARGV[1])
local ttl = tonumber(ARGV[4])

if ARGV[3] == 'EVENT_ORDER' then
  local last_event_id = redis.call('GET', KEYS[2])
  if last_event_id and not is_newer(ARGV[2], last_event_id) then
    return 0
  end
  redis.call('SET', KEYS[2], ARGV[2], 'EX', ttl)
else
  local applied_count = tonumber(redis.call('GET', KEYS[1]) or '-1')
  if count <= applied_count then
    return 0
  end
end
redis.call('SET', KEYS[1], count, 'EX', ttl)

local like_count = tonumber(redis.call('GET', KEYS[3]) or '0')
local comment_count = tonumber(redis.call('GET', KEYS[4]) or '0')
local view_count = tonumber(redis.call('GET', KEYS[5]) or '0')
local score = like_count * tonumber(ARGV[5]) + comment_count * tonumber(ARGV[6]) + view_count * tonumber(ARGV[7])

redis.call('ZADD', KEYS[6], score, ARGV[8])
redis.call('ZREMRANGEBYRANK', KEYS[6], 0, -(tonumber(ARGV[9]) + 1))
redis.call('EXPIRE', KEYS[6], ttl)
return 1

-- 생성 이벤트의 본문을 반영하고, 반영됐을 때만 게시판 최신 목록에 넣는다.
-- 삭제 표시를 같은 스크립트에서 확인하므로 삭제 뒤 늦게 온 생성이 목록에 다시 들어가지 않는다.
-- KEYS: 1 상세 Hash, 2 게시판 최신 목록 ZSET
-- ARGV: 1 필드, 2 값, 3 그 필드의 eventId 필드, 4 eventId, 5 TTL(초), 6 articleId, 7 목록에 남길 수
local function is_newer(event_id, last_event_id)
  -- eventId는 2^53을 넘어 Lua 숫자로 비교하면 정밀도를 잃는다. 앞자리 0이 없는 10진 문자열이라 길이→사전순으로 비교한다
  if #event_id ~= #last_event_id then
    return #event_id > #last_event_id
  end
  return event_id > last_event_id
end

if redis.call('HGET', KEYS[1], 'deleted') == '1' then
  return 0
end
local last_event_id = redis.call('HGET', KEYS[1], ARGV[3])
if last_event_id and not is_newer(ARGV[4], last_event_id) then
  return 0
end
redis.call('HSET', KEYS[1], ARGV[1], ARGV[2], ARGV[3], ARGV[4])
redis.call('EXPIRE', KEYS[1], tonumber(ARGV[5]))
redis.call('ZADD', KEYS[2], ARGV[6], ARGV[6])
redis.call('ZREMRANGEBYRANK', KEYS[2], 0, -(tonumber(ARGV[7]) + 1))
return 1

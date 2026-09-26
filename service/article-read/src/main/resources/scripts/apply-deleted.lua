-- 삭제된 게시글은 본문·카운트를 지우고 삭제 표시만 남겨, 늦게 온 이벤트가 읽기 모델을 되살리지 못하게 한다.
-- KEYS: 1 상세 Hash, 2 게시판 최신 목록 ZSET
-- ARGV: 1 eventId, 2 TTL(초), 3 articleId
local function is_newer(event_id, last_event_id)
  -- eventId는 2^53을 넘어 Lua 숫자로 비교하면 정밀도를 잃는다. 앞자리 0이 없는 10진 문자열이라 길이→사전순으로 비교한다
  if #event_id ~= #last_event_id then
    return #event_id > #last_event_id
  end
  return event_id > last_event_id
end

local last_event_id = redis.call('HGET', KEYS[1], 'article-event-id')
if last_event_id and not is_newer(ARGV[1], last_event_id) then
  return 0
end
redis.call('DEL', KEYS[1])
redis.call('HSET', KEYS[1], 'deleted', '1', 'article-event-id', ARGV[1])
redis.call('EXPIRE', KEYS[1], tonumber(ARGV[2]))
redis.call('ZREM', KEYS[2], ARGV[3])
return 1

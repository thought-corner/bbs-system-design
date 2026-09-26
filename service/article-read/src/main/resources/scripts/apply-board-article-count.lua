-- 게시판 게시글 수를 eventId가 더 클 때만 바꾼다. eventId는 게시판 카운트 행 락 아래에서 만들어져 카운트 순서와 같다.
-- KEYS: 1 게시글 수, 2 그 last-event-id
-- ARGV: 1 누적값, 2 eventId
local function is_newer(event_id, last_event_id)
  -- eventId는 2^53을 넘어 Lua 숫자로 비교하면 정밀도를 잃는다. 앞자리 0이 없는 10진 문자열이라 길이→사전순으로 비교한다
  if #event_id ~= #last_event_id then
    return #event_id > #last_event_id
  end
  return event_id > last_event_id
end

local last_event_id = redis.call('GET', KEYS[2])
if last_event_id and not is_newer(ARGV[2], last_event_id) then
  return 0
end
redis.call('SET', KEYS[1], ARGV[1])
redis.call('SET', KEYS[2], ARGV[2])
return 1

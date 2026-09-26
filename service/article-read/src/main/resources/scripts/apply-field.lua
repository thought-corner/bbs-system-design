-- 상세 읽기 모델 Hash의 필드 하나를 eventId가 더 클 때만 바꾼다 (D12·D15).
-- 필드마다 따로 비교하므로 다른 토픽의 이벤트가 서로의 필드를 덮어쓰지 않는다.
-- KEYS: 1 상세 Hash
-- ARGV: 1 필드, 2 값, 3 그 필드의 eventId 필드, 4 eventId, 5 TTL(초)
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
return 1

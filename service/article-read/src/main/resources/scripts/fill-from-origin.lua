-- 원본에서 읽은 값을 상세 Hash에 쓴다.
-- 원본을 읽는 사이 이벤트로 더 새 값이 들어온 필드는 건드리지 않는다: 읽기 전에 기억한 eventId와 지금 eventId가 같은 필드만 쓴다.
-- KEYS: 1 상세 Hash
-- ARGV: 1 본문 JSON, 2 기억한 article-event-id, 3 댓글 수, 4 기억한 comment-event-id,
--       5 좋아요 수, 6 기억한 like-event-id, 7 논리 만료 시각(epoch ms), 8 TTL(초)
-- 기억한 eventId가 없었으면 빈 문자열이다

if redis.call('HGET', KEYS[1], 'deleted') == '1' then
  return 0
end

local function unchanged(event_id_field, remembered_event_id)
  local current_event_id = redis.call('HGET', KEYS[1], event_id_field)
  if not current_event_id then
    current_event_id = ''
  end
  return current_event_id == remembered_event_id
end

if unchanged('article-event-id', ARGV[2]) then
  redis.call('HSET', KEYS[1], 'article', ARGV[1])
end
if unchanged('comment-event-id', ARGV[4]) then
  redis.call('HSET', KEYS[1], 'comment-count', ARGV[3])
end
if unchanged('like-event-id', ARGV[6]) then
  redis.call('HSET', KEYS[1], 'like-count', ARGV[5])
end
redis.call('HSET', KEYS[1], 'logical-expires-at', ARGV[7])
redis.call('EXPIRE', KEYS[1], tonumber(ARGV[8]))
return 1

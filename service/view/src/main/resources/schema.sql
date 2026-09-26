CREATE TABLE IF NOT EXISTS article_view_count (
  article_id BIGINT PRIMARY KEY,
  view_count BIGINT NOT NULL
);

CREATE TABLE IF NOT EXISTS outbox (
  outbox_id BIGINT PRIMARY KEY,
  event_type VARCHAR(100) NOT NULL,
  payload JSON NOT NULL,
  partition_key BIGINT NOT NULL,
  created_at DATETIME NOT NULL,
  INDEX idx_created_at (created_at)
);

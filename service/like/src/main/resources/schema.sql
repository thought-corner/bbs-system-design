CREATE TABLE IF NOT EXISTS article_like (
  article_like_id BIGINT PRIMARY KEY,
  article_id BIGINT NOT NULL,
  user_id BIGINT NOT NULL,
  created_at DATETIME NOT NULL,
  UNIQUE INDEX idx_article_id_user_id (article_id, user_id)
);

CREATE TABLE IF NOT EXISTS article_like_count (
  article_id BIGINT PRIMARY KEY,
  like_count BIGINT NOT NULL
);

CREATE TABLE IF NOT EXISTS outbox (
  outbox_id BIGINT PRIMARY KEY,
  event_type VARCHAR(100) NOT NULL,
  payload JSON NOT NULL,
  partition_key BIGINT NOT NULL,
  created_at DATETIME NOT NULL,
  INDEX idx_created_at (created_at)
);

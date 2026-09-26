CREATE TABLE IF NOT EXISTS article (
  article_id BIGINT PRIMARY KEY,
  board_id BIGINT NOT NULL,
  writer_id BIGINT NOT NULL,
  title VARCHAR(100) NOT NULL,
  content TEXT NOT NULL,
  created_at DATETIME NOT NULL,
  modified_at DATETIME NOT NULL,
  INDEX idx_board_id_article_id (board_id, article_id DESC)
);

CREATE TABLE IF NOT EXISTS board_article_count (
  board_id BIGINT PRIMARY KEY,
  article_count BIGINT NOT NULL
);

CREATE TABLE IF NOT EXISTS outbox (
  outbox_id BIGINT PRIMARY KEY,
  event_type VARCHAR(100) NOT NULL,
  payload JSON NOT NULL,
  partition_key BIGINT NOT NULL,
  created_at DATETIME NOT NULL,
  INDEX idx_created_at (created_at)
);

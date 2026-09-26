CREATE TABLE IF NOT EXISTS comment (
  comment_id BIGINT PRIMARY KEY,
  article_id BIGINT NOT NULL,
  writer_id BIGINT NOT NULL,
  content VARCHAR(3000) NOT NULL,
  path VARCHAR(3000) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  deleted BOOLEAN NOT NULL,
  created_at DATETIME NOT NULL,
  UNIQUE INDEX idx_article_id_path (article_id, path)
);

CREATE TABLE IF NOT EXISTS article_comment_count (
  article_id BIGINT PRIMARY KEY,
  comment_count BIGINT NOT NULL
);

CREATE TABLE IF NOT EXISTS outbox (
  outbox_id BIGINT PRIMARY KEY,
  event_type VARCHAR(100) NOT NULL,
  payload JSON NOT NULL,
  partition_key BIGINT NOT NULL,
  created_at DATETIME NOT NULL,
  INDEX idx_created_at (created_at)
);

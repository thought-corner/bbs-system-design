-- 서비스마다 스키마를 나눈다 (D1). 테이블은 각 서비스가 schema.sql로 만든다.
CREATE DATABASE IF NOT EXISTS article;
CREATE DATABASE IF NOT EXISTS comment;
CREATE DATABASE IF NOT EXISTS article_like;
CREATE DATABASE IF NOT EXISTS article_view;

-- mysqld-exporter 전용 계정. 상태·프로세스 목록을 읽는 데 필요한 권한만 준다 (root를 쓰지 않는다).
CREATE USER IF NOT EXISTS 'exporter'@'%' IDENTIFIED BY 'exporter' WITH MAX_USER_CONNECTIONS 3;
GRANT PROCESS, REPLICATION CLIENT, SELECT ON *.* TO 'exporter'@'%';

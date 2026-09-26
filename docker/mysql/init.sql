-- 서비스마다 스키마를 나눈다 (D1). 테이블은 각 서비스가 schema.sql로 만든다.
CREATE DATABASE IF NOT EXISTS article;
CREATE DATABASE IF NOT EXISTS comment;
CREATE DATABASE IF NOT EXISTS article_like;
CREATE DATABASE IF NOT EXISTS article_view;

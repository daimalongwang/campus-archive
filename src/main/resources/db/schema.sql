-- 大学生个人学业档案与证书日程管理系统 · SQLite 建表脚本
-- 与《第 4 周详细设计文档》第 8 章表结构一一对应。

CREATE TABLE IF NOT EXISTS category (
  id        VARCHAR(36) PRIMARY KEY,
  name      VARCHAR(64) NOT NULL,
  parent_id VARCHAR(36),
  sort      INT         NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS material (
  id            VARCHAR(36)  PRIMARY KEY,
  file_name     VARCHAR(255) NOT NULL,
  stored_path   VARCHAR(512) NOT NULL,
  file_type     VARCHAR(16)  NOT NULL,
  file_size     BIGINT       NOT NULL,
  content_hash  CHAR(64)     NOT NULL,
  category_id   VARCHAR(36)  NOT NULL,
  is_private    TINYINT      NOT NULL DEFAULT 0,
  used_at       DATETIME,
  created_at    DATETIME     NOT NULL,
  deleted_at    DATETIME
);

CREATE TABLE IF NOT EXISTS security_setting (
  id         VARCHAR(36) PRIMARY KEY,
  key_hash   CHAR(64)    NOT NULL,
  salt       CHAR(32)    NOT NULL,
  kdf_rounds INT         NOT NULL,
  enabled    TINYINT     NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS vault_entry (
  id             VARCHAR(36)  PRIMARY KEY,
  material_id    VARCHAR(36)  NOT NULL UNIQUE,
  encrypted_path VARCHAR(512) NOT NULL,
  iv             CHAR(24)     NOT NULL
);

CREATE TABLE IF NOT EXISTS trash_record (
  id                 VARCHAR(36) PRIMARY KEY,
  material_id        VARCHAR(36) NOT NULL UNIQUE,
  deleted_at         DATETIME    NOT NULL,
  origin_category_id VARCHAR(36)
);

CREATE TABLE IF NOT EXISTS app_config (
  key   VARCHAR(64) PRIMARY KEY,
  value VARCHAR(512)
);

CREATE INDEX IF NOT EXISTS idx_material_category ON material(category_id);
CREATE INDEX IF NOT EXISTS idx_material_created  ON material(created_at DESC);
CREATE INDEX IF NOT EXISTS idx_material_used     ON material(used_at DESC);
CREATE INDEX IF NOT EXISTS idx_material_hash     ON material(content_hash);
CREATE INDEX IF NOT EXISTS idx_material_deleted  ON material(deleted_at);
CREATE INDEX IF NOT EXISTS idx_material_name     ON material(file_name);

-- 内置一级分类（分类服务于查找，不增加用户负担）
INSERT OR IGNORE INTO category(id, name, sort) VALUES
  ('cat-1', '证书', 1),
  ('cat-2', '获奖', 2),
  ('cat-3', '竞赛', 3),
  ('cat-4', '科研', 4),
  ('cat-5', '实践', 5),
  ('cat-6', '实习', 6),
  ('cat-7', '其他', 7);

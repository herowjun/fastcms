-- ----------------------------
-- 0.2.0 incremental DDL (run on existing DB; fresh install imports fastcms.sql directly)
-- MySQL 5.7 has no ADD COLUMN IF NOT EXISTS; verify the column does not exist before running
-- ----------------------------

-- api_key nullable + comment (column itself is already nullable, keep comment in sync)
ALTER TABLE ai_model_config MODIFY COLUMN api_key varchar(255) DEFAULT NULL COMMENT 'API Key (nullable for local providers such as Ollama)';

-- new column: extra request headers, JSON object text
ALTER TABLE ai_model_config ADD COLUMN extra_headers text DEFAULT NULL COMMENT 'Custom request headers (JSON, e.g. {"X-Tenant":"abc"}; OLLAMA_API_KEY etc.)' AFTER remark;

-- ----------------------------
-- AI template generator integrated into the template edit page
-- ----------------------------

-- ai_template_session: bound formal template (non-null = adjust session, AI output written directly to the formal template dir with per-round backup)
ALTER TABLE ai_template_session ADD COLUMN template_id varchar(64) DEFAULT NULL COMMENT '绑定的正式模板ID（非空表示调整型会话，AI 输出直写正式模板目录）' AFTER work_dir;

-- per-round file backup written before AI modifies formal template files (rollback granularity = message_id)
CREATE TABLE ai_template_file_backup (
  id bigint NOT NULL AUTO_INCREMENT,
  session_id varchar(64) NOT NULL COMMENT '会话ID',
  message_id bigint NOT NULL COMMENT '触发本次变更的AI消息ID（回滚粒度）',
  file_path varchar(255) NOT NULL COMMENT '相对路径',
  content longtext COMMENT '修改前内容（修改前文件不存在时为 NULL）',
  existed tinyint DEFAULT '1' COMMENT '修改前文件是否存在（AI 新建的文件为 0）',
  created datetime DEFAULT NULL,
  PRIMARY KEY (id),
  KEY idx_session_id (session_id),
  KEY idx_message_id (message_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI 模板文件修改前备份表';

-- standalone "AI > 模板生成器" menu retired (feature merged into 模板编辑 page); aiModel menu already lives under 设置
-- ids 42 (AI menu) and 44 (模板生成器) were introduced outside fastcms.sql; remove them if present
DELETE FROM permission WHERE id IN (42, 44);

-- ----------------------------
-- AI 治理：调用审计日志（配额统计同样基于本表按日聚合，不单独建配额表）
-- ----------------------------
CREATE TABLE ai_usage_log (
  id bigint NOT NULL AUTO_INCREMENT,
  user_id bigint NOT NULL COMMENT '触发用户',
  scene varchar(32) NOT NULL COMMENT '场景: TEMPLATE_GEN/TEMPLATE_ADJUST/ARTICLE_GEN/ARTICLE_REWRITE/ARTICLE_FIELD',
  session_id varchar(64) DEFAULT NULL COMMENT '关联会话ID（无状态场景为空）',
  model varchar(128) DEFAULT NULL COMMENT '使用的模型名',
  prompt_tokens int DEFAULT 0 COMMENT '提示词token数',
  completion_tokens int DEFAULT 0 COMMENT '补全token数',
  total_tokens int DEFAULT 0 COMMENT '总token数',
  duration_ms bigint DEFAULT 0 COMMENT '耗时毫秒',
  success tinyint DEFAULT 1 COMMENT '是否成功',
  error_msg varchar(1024) DEFAULT NULL COMMENT '失败原因',
  created datetime DEFAULT NULL,
  PRIMARY KEY (id),
  KEY idx_user_created (user_id, created),
  KEY idx_scene (scene)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI 调用审计日志';

CREATE TABLE ai_article_op_log (
  id bigint NOT NULL AUTO_INCREMENT,
  user_id bigint NOT NULL COMMENT '触发用户',
  article_id bigint DEFAULT NULL COMMENT '关联文章ID（新建文章保存前为空，保存后由前端触发绑定）',
  operation varchar(32) NOT NULL COMMENT '操作类型: rewrite/expand/polish/translate/generate/field_title/field_summary/field_seoKeywords/field_seoDescription',
  original_text mediumtext COMMENT '原选中文本',
  rewritten_text mediumtext COMMENT 'AI 改写结果',
  reasoning mediumtext COMMENT '思考过程',
  model varchar(128) DEFAULT NULL COMMENT '使用的模型名',
  duration_ms bigint DEFAULT 0 COMMENT '耗时毫秒',
  created datetime DEFAULT NULL,
  PRIMARY KEY (id),
  KEY idx_article (article_id),
  KEY idx_user_created (user_id, created)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI 文章划词操作记录';

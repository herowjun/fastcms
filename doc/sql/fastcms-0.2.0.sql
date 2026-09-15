-- ----------------------------
-- 0.2.0 incremental DDL (run on existing DB; fresh install imports fastcms.sql directly)
-- MySQL 5.7 has no ADD COLUMN IF NOT EXISTS; verify the column does not exist before running
-- ----------------------------

-- ----------------------------
-- 一键安装向导：安装锁表 + 默认账号首次登录强制改密
-- ----------------------------

-- 安装锁：存在记录即视为已完成安装（阻止重复安装/重放安装接口）
CREATE TABLE fastcms_install (
  id bigint NOT NULL AUTO_INCREMENT,
  install_time datetime DEFAULT NULL COMMENT '安装完成时间（安装向导执行时更新）',
  version varchar(32) DEFAULT NULL COMMENT '安装时的系统版本',
  created datetime DEFAULT NULL,
  updated datetime DEFAULT NULL,
  PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='系统安装锁：存在记录即视为已完成安装，阻止重复安装与重放安装接口';

-- 已有环境视同已安装，写入锁记录（幂等）
INSERT INTO fastcms_install (id, install_time, version, created, updated)
SELECT 1, NOW(), '0.2.0', NOW(), NOW() FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM fastcms_install WHERE id = 1);

-- 默认账号首次登录须强制修改密码（默认密码公开于README，不强制不安全）
ALTER TABLE `user` ADD COLUMN must_change_pwd tinyint(1) DEFAULT '0' COMMENT '是否必须修改密码：1=首次登录须强制设置新密码，0=正常' AFTER login_time;

UPDATE `user` SET must_change_pwd = 1 WHERE id = 1;

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

-- ----------------------------
-- 附件分类树 + AI 生图/修图 + 模板生成图片来源适配
-- ----------------------------

-- 附件目录表（附件分类树：parent_id 父目录，0 为根）
CREATE TABLE attachment_directory (
  id bigint NOT NULL AUTO_INCREMENT,
  parent_id bigint NOT NULL DEFAULT 0 COMMENT '父目录ID，0为根目录',
  name varchar(50) NOT NULL COMMENT '目录名称',
  sort_num int DEFAULT 0 COMMENT '排序（越小越靠前）',
  created datetime DEFAULT NULL,
  updated datetime DEFAULT NULL,
  PRIMARY KEY (id),
  KEY idx_parent_id (parent_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='附件目录表';

-- 附件表增加目录归属字段
ALTER TABLE attachment ADD COLUMN directory_id bigint NOT NULL DEFAULT 0 COMMENT '所属目录ID，0为未分类' AFTER file_type;

-- AI 模型配置表增加用途场景（chat=对话，image=生图；同场景内仅一条激活）
ALTER TABLE ai_model_config ADD COLUMN scene varchar(20) NOT NULL DEFAULT 'chat' COMMENT '用途场景: chat-对话 image-生图' AFTER model;

-- AI 模板生成会话表增加图片来源选项字段
ALTER TABLE ai_template_session ADD COLUMN prefer_attachment tinyint(1) DEFAULT 1 COMMENT '优先复用附件库图片（1=media 槽位先搜附件库，无匹配走演示图兜底；0=直接用演示图）' AFTER mobile_adaptive;

-- AI 生图任务表（文生图/修图异步任务，前端轮询状态；含模板图片修图回写字段）
CREATE TABLE ai_image_task (
  id bigint NOT NULL AUTO_INCREMENT,
  session_id varchar(64) DEFAULT NULL COMMENT '关联模板会话ID，媒体库生图为 NULL',
  user_id bigint NOT NULL COMMENT '发起用户ID',
  task_type varchar(10) NOT NULL COMMENT '任务类型: t2i-文生图 edit-修图',
  model varchar(64) NOT NULL COMMENT '生图模型名称',
  prompt varchar(2000) NOT NULL COMMENT '提示词（业务语义描述）',
  source_attachment_id bigint DEFAULT NULL COMMENT '修图原图附件ID',
  template_id varchar(64) DEFAULT NULL COMMENT '修图源/回写目标模板ID（模板 static 图片修图场景）',
  template_file_path varchar(500) DEFAULT NULL COMMENT '修图源/回写目标模板内文件路径（回写前原图自动备份为 .bak）',
  size varchar(20) NOT NULL DEFAULT '1664*928' COMMENT '生成尺寸 宽*高',
  num int NOT NULL DEFAULT 1 COMMENT '生成张数 1-4',
  status varchar(10) NOT NULL DEFAULT 'pending' COMMENT '状态: pending/running/success/failed',
  result_paths text DEFAULT NULL COMMENT '结果 JSON: [{filePath,attachmentId}]',
  error varchar(500) DEFAULT NULL COMMENT '失败原因',
  created datetime DEFAULT NULL,
  updated datetime DEFAULT NULL,
  PRIMARY KEY (id),
  KEY idx_user_id (user_id),
  KEY idx_session_id (session_id),
  KEY idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI 生图任务表';


-- ----------------------------
-- Menu display scope: template-exclusive menus + global menu exclusions
-- ----------------------------

-- template-exclusive menus: NULL = global (all templates), non-null = only that template (overrides all global menus)
ALTER TABLE menu ADD COLUMN template_id varchar(64) DEFAULT NULL COMMENT '专属模板ID（NULL=全局菜单）' AFTER status;

-- global menus may be excluded from specific templates (comma-separated template ids)
ALTER TABLE menu ADD COLUMN exclude_template_ids varchar(500) DEFAULT NULL COMMENT '排除显示的模板ID列表，逗号分隔（仅全局菜单生效）' AFTER template_id;

-- global menus may be excluded from specific sites (comma-separated site keys: domain or path)
ALTER TABLE menu ADD COLUMN exclude_site_keys varchar(1000) DEFAULT NULL COMMENT '排除显示的站点key列表（域名或路径），逗号分隔（仅全局菜单生效）' AFTER exclude_template_ids;

-- ----------------------------
-- AI 对话消息 token 用量（每轮 assistant 消息回写，前端 hover 展示；跨轮次聚合，含思考/工具调用轮）
-- ----------------------------
ALTER TABLE ai_template_message ADD COLUMN prompt_tokens int DEFAULT NULL COMMENT '本轮输入token（仅assistant，跨轮次聚合）' AFTER reasoning;
ALTER TABLE ai_template_message ADD COLUMN completion_tokens int DEFAULT NULL COMMENT '本轮输出token（仅assistant）' AFTER prompt_tokens;
ALTER TABLE ai_template_message ADD COLUMN total_tokens int DEFAULT NULL COMMENT '本轮总token（仅assistant）' AFTER completion_tokens;

-- ----------------------------
-- 0.3.1: AI 智能体（多智能体架构管理）
-- ----------------------------

-- AI 智能体配置表（自定义智能体落库，内置智能体由代码注册不落库）
CREATE TABLE ai_agent (
  id bigint NOT NULL AUTO_INCREMENT,
  agent_id varchar(64) NOT NULL COMMENT '智能体业务ID（自定义智能体为 custom-xxx）',
  name varchar(64) NOT NULL COMMENT '智能体名称',
  description varchar(255) DEFAULT NULL COMMENT '智能体描述',
  execution_mode varchar(16) NOT NULL DEFAULT 'chat' COMMENT '执行模式: chat-对话循环 / pipeline-管线驱动（自定义智能体仅允许 chat）',
  system_prompt text COMMENT '系统提示词（支持 {{site.name}} 等站点变量占位，运行时替换）',
  model_config_id bigint DEFAULT NULL COMMENT '绑定的模型配置ID（NULL=继承当前激活的对话模型）',
  temperature double DEFAULT NULL COMMENT '温度（NULL=继承模型配置默认值）',
  max_tokens int DEFAULT NULL COMMENT 'MaxTokens（NULL=继承模型配置默认值）',
  skills text COMMENT '绑定的 skill ID JSON 数组（能力白名单）',
  tools text COMMENT '绑定的工具名 JSON 数组（工具白名单）',
  daily_token_quota bigint DEFAULT 0 COMMENT '日 token 配额（0=不限）',
  sort_num int DEFAULT 0 COMMENT '排序（越小越靠前）',
  status tinyint DEFAULT 1 COMMENT '状态: 1启用 0停用',
  base_agent_id varchar(64) DEFAULT NULL COMMENT '复制来源智能体ID（内置升级时不动副本，仅提示）',
  created datetime DEFAULT NULL,
  updated datetime DEFAULT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_agent_id (agent_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI 智能体配置表';

-- 智能体不设独立菜单：功能已合并到「设置/模型」页的 tab 页签中（后端 API 权限 ai:agent:* 保留在 resource 表）
-- 已有环境若曾安装过智能体菜单（permission.id=45），执行以下语句移除（含角色关联）
DELETE FROM role_permission WHERE permission_id = 45;
DELETE FROM permission WHERE id = 45;

-- AI 调用审计日志增加智能体归属列（走智能体的调用记录 agent_id，支撑智能体级配额与用量统计）
ALTER TABLE ai_usage_log ADD COLUMN agent_id varchar(64) DEFAULT NULL COMMENT '归属智能体ID（builtin.*/custom-*，未走智能体为空）' AFTER session_id;
ALTER TABLE ai_usage_log ADD INDEX idx_agent_created (agent_id, created);

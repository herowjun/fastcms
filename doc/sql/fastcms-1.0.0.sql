-- ----------------------------
-- 1.0.0 incremental DDL (run on existing DB; fresh install imports fastcms.sql directly)
-- MySQL 5.7 has no ADD COLUMN IF NOT EXISTS; verify the column does not exist before running
-- 发布顺序红线：先执行本脚本再上线对应代码（实体加字段后 MyBatis-Plus 的查询会带上新列，
-- 列缺失时 ai_template_session 的全部读写都会失败，含管线模式会话，不只是 design 会话）
-- ----------------------------

-- ----------------------------
-- AI 模板生成双模式：会话增加创建模式字段
-- design=设计稿先行模式（AI 自主设计 HTML 设计稿 → 机器审计 → 确定性转化为组件化模板）
-- 存量数据 NULL 视为 pipeline（组件管线模式），行为不变
-- 注意：AFTER 锚点统一用 mobile_adaptive（三份全量 DDL 均存在；
-- prefer_attachment 列在两份全量 DDL 中缺失，不用它做锚点以规避该既有不一致）
-- ----------------------------
ALTER TABLE ai_template_session ADD COLUMN create_mode varchar(16) DEFAULT NULL
  COMMENT '创建模式: NULL/pipeline=组件管线(默认) design=设计稿先行' AFTER mobile_adaptive;

ALTER TABLE ai_template_session ADD COLUMN design_direction varchar(64) DEFAULT NULL
  COMMENT '设计模式方向资产 key（如 modern-business / feedback-brighten，命中 DesignDirectionLibrary）' AFTER create_mode;

ALTER TABLE ai_template_session ADD COLUMN confirm_auto tinyint(1) DEFAULT 1
  COMMENT '设计模式：机器审计通过后是否自动转化（1=自动，0=等用户确认；null 视为 1）' AFTER design_direction;

-- ----------------------------
-- 统一工作对象选择器：会话增加应用后正式模板指针字段
-- 生成型会话应用成功时由 applyTemplate 回写（前端"去正式模板/编辑此模板"据此直达）；
-- 存量数据 NULL = 无指针，前端回退按目录名（template_name）匹配，行为与升级前一致
-- ----------------------------
ALTER TABLE ai_template_session ADD COLUMN applied_template_id varchar(64) DEFAULT NULL
  COMMENT '应用后生成的正式模板ID（应用成功时回写；NULL=存量数据或未应用，前端回退按目录名匹配）' AFTER template_id;

-- ----------------------------
-- AI 模型配置增加思考档位
-- 背景：推理端点的思考开关没有统一标准——Qwen 系端点不传参数时默认 xhigh（全力思考），
-- 而思考 token 与正文 token 同价（实测 48 tok/s），导致"简单问题"也要等数分钟。
-- 取值：NULL/auto=跟随场景默认（生成类低档、分类/映射/修复类关思考）、
--       off=关闭思考、low/medium/xhigh=映射为请求体 reasoning_effort。
-- 发布顺序红线：先执行本脚本再上线对应代码（实体加字段后 MyBatis-Plus 的查询会带上新列，
-- 列缺失时 ai_model_config 的全部读写都会失败）
-- ----------------------------
ALTER TABLE ai_model_config ADD COLUMN reasoning_effort varchar(16) DEFAULT NULL
  COMMENT '思考档位: auto(跟随场景默认)/off(关闭思考)/low/medium/xhigh（NULL=auto）' AFTER scene;

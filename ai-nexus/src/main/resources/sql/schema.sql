-- =====================================================================
-- AI Nexus MySQL 建表脚本（幂等，可重复执行）
-- =====================================================================
CREATE DATABASE IF NOT EXISTS ai_nexus DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE ai_nexus;

-- 知识库元数据
CREATE TABLE IF NOT EXISTS knowledge_base (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    name            VARCHAR(64)  NOT NULL COMMENT '知识库名称，业务唯一',
    description     VARCHAR(512) NULL COMMENT '描述',
    collection_name VARCHAR(128) NOT NULL COMMENT 'Milvus Collection 名称',
    status          INT          NOT NULL DEFAULT 1 COMMENT '状态：1-启用 0-禁用',
    user_id         BIGINT       NULL COMMENT '所属用户（鉴权开启时隔离维度）',
    tenant_id       BIGINT       NULL COMMENT '所属租户（多租户开启时隔离维度）',
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME     NULL ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_name (name),
    KEY idx_user_id (user_id)
) ENGINE = InnoDB COMMENT '知识库';

-- 知识库文档
CREATE TABLE IF NOT EXISTS knowledge_document (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    kb_id        BIGINT       NOT NULL COMMENT '所属知识库',
    file_name    VARCHAR(256) NOT NULL COMMENT '文件名',
    doc_uuid     VARCHAR(64)  NOT NULL COMMENT '向量库 Chunk ID 前缀',
    char_count   BIGINT       NOT NULL COMMENT '原始字符数',
    chunk_count  INT          NOT NULL COMMENT '切分 Chunk 数',
    status       INT          NOT NULL DEFAULT 0 COMMENT '0-处理中 1-已入库 2-失败',
    error_message VARCHAR(512) NULL,
    created_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_kb_id (kb_id)
) ENGINE = InnoDB COMMENT '知识库文档';

-- 对话历史
CREATE TABLE IF NOT EXISTS chat_message (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    session_id VARCHAR(64) NOT NULL COMMENT '会话 ID',
    role       VARCHAR(16) NOT NULL COMMENT 'user/assistant/system',
    content    TEXT        NOT NULL,
    model_name VARCHAR(64) NULL COMMENT '实际路由模型',
    user_id    BIGINT      NULL COMMENT '所属用户（鉴权开启时隔离维度）',
    created_at DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_session_created (session_id, created_at),
    KEY idx_session_user (session_id, user_id)
) ENGINE = InnoDB COMMENT '对话历史';

-- AI 调用流水（统计）
CREATE TABLE IF NOT EXISTS ai_call_log (
    id                BIGINT AUTO_INCREMENT PRIMARY KEY,
    scene             VARCHAR(32)  NOT NULL COMMENT 'CHAT/RAG/AGENT/MULTIMODAL',
    model_name        VARCHAR(64)  NOT NULL,
    session_id        VARCHAR(64)  NULL,
    success           INT          NOT NULL,
    cost_ms           BIGINT       NOT NULL,
    prompt_tokens     BIGINT       NULL,
    completion_tokens BIGINT       NULL,
    fallback          INT          NOT NULL DEFAULT 0,
    user_id           BIGINT       NULL COMMENT '所属用户（鉴权开启时隔离维度）',
    tenant_id         BIGINT       NULL COMMENT '所属租户（多租户开启时隔离维度）',
    error_message     VARCHAR(512) NULL,
    call_date         VARCHAR(10)  NOT NULL COMMENT '调用日期 yyyy-MM-dd',
    created_at        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_scene_day (scene, call_date),
    KEY idx_user_id (user_id)
) ENGINE = InnoDB COMMENT 'AI 调用流水';

-- 系统用户（auth.enabled=true 时启用）
CREATE TABLE IF NOT EXISTS sys_user (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    username      VARCHAR(64)  NOT NULL COMMENT '用户名，唯一',
    password_hash VARCHAR(72)  NOT NULL COMMENT 'BCrypt 摘要',
    role          VARCHAR(20)  NOT NULL DEFAULT 'USER' COMMENT '角色：ADMIN/USER（首个注册用户自动成为管理员）',
    tenant_id     BIGINT       NULL COMMENT '所属租户（多租户开启时生效）',
    created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_username (username)
) ENGINE = InnoDB COMMENT '系统用户';

-- 存量库升级（MySQL 8.0 无 ADD COLUMN IF NOT EXISTS，手动执行一次即可，重复执行报 Duplicate column 可忽略）：
-- ALTER TABLE knowledge_base ADD COLUMN user_id BIGINT NULL COMMENT '所属用户';
-- ALTER TABLE chat_message    ADD COLUMN user_id BIGINT NULL COMMENT '所属用户';
-- ALTER TABLE ai_call_log     ADD COLUMN user_id BIGINT NULL COMMENT '所属用户';

-- 业务审计日志（阶段5）
CREATE TABLE IF NOT EXISTS audit_log (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    operator   VARCHAR(64)  NOT NULL COMMENT '操作人',
    user_id    BIGINT       NULL,
    action     VARCHAR(32)  NOT NULL COMMENT '操作类型',
    target     VARCHAR(256) NULL COMMENT '操作对象',
    detail     VARCHAR(1024) NULL,
    client_ip  VARCHAR(64)  NULL,
    success    INT          NOT NULL,
    tenant_id  BIGINT       NULL COMMENT '所属租户（多租户开启时隔离维度）',
    created_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_action_time (action, created_at)
) ENGINE = InnoDB COMMENT '业务审计日志';

-- 租户表（tenant.enabled=true 时启用多租户）
CREATE TABLE IF NOT EXISTS sys_tenant (
    id                 BIGINT AUTO_INCREMENT PRIMARY KEY,
    name               VARCHAR(64)  NOT NULL COMMENT '租户名称',
    code               VARCHAR(32)  NOT NULL COMMENT '租户编码，唯一',
    status             INT          NOT NULL DEFAULT 1 COMMENT '1-启用 0-禁用',
    kb_quota           INT          NOT NULL DEFAULT 5 COMMENT '知识库数量上限',
    concurrent_quota   INT          NOT NULL DEFAULT 2 COMMENT '单租户并发调用上限',
    daily_model_quota  INT          NOT NULL DEFAULT 1000 COMMENT '每日模型调用限额',
    model_routes       TEXT         NULL COMMENT '租户专属路由 JSON {"CHAT":"model"}',
    created_at         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_code (code)
) ENGINE = InnoDB COMMENT '租户';

-- 租户隔离列（tenant.enabled=true 时生效；NULL 表示关闭态/历史数据，不受租户过滤）
-- ALTER TABLE sys_user        ADD COLUMN tenant_id BIGINT NULL COMMENT '所属租户';
-- ALTER TABLE knowledge_base  ADD COLUMN tenant_id BIGINT NULL COMMENT '所属租户';
-- ALTER TABLE chat_message    ADD COLUMN tenant_id BIGINT NULL COMMENT '所属租户';
-- ALTER TABLE ai_call_log     ADD COLUMN tenant_id BIGINT NULL COMMENT '所属租户';
-- ALTER TABLE audit_log       ADD COLUMN tenant_id BIGINT NULL COMMENT '所属租户';

-- 角色列（管理面鉴权；存量库手动执行一次，重复执行报 Duplicate column 可忽略。首个注册用户已自动 ADMIN，无需手工提升）
-- ALTER TABLE sys_user ADD COLUMN role VARCHAR(20) NOT NULL DEFAULT 'USER' COMMENT '角色：ADMIN/USER';

-- 动态配置表（运行时配置管控；dynamic.enabled=true 时启用）
CREATE TABLE IF NOT EXISTS sys_config (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    config_key   VARCHAR(64)  NOT NULL COMMENT '配置键（如 resilience.circuit-failure-rate）',
    config_value VARCHAR(512) NOT NULL COMMENT '配置值',
    scope        VARCHAR(16)  NOT NULL DEFAULT 'global' COMMENT 'global-全局 | gray-灰度（仅 gray_tenants 生效）',
    tenant_id    BIGINT       NULL COMMENT '租户专属配置（优先级最高）',
    gray_tenants VARCHAR(256) NULL COMMENT '灰度租户 ID 列表（逗号分隔，scope=gray 时生效）',
    updated_by   VARCHAR(64)  NULL COMMENT '最后修改人',
    updated_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    KEY idx_key_tenant (config_key, tenant_id)
) ENGINE = InnoDB COMMENT '动态配置';

-- 知识库 Chunk 备份表（上传时写入原始片段，支撑备份导出/导入与关键词检索扩展）
CREATE TABLE IF NOT EXISTS knowledge_chunk (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    kb_id      BIGINT       NOT NULL COMMENT '所属知识库',
    doc_uuid   VARCHAR(64)  NOT NULL COMMENT '所属文档',
    chunk_idx  INT          NOT NULL COMMENT '片段序号',
    content    TEXT         NOT NULL COMMENT '片段原文',
    created_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_kb_doc (kb_id, doc_uuid)
) ENGINE = InnoDB COMMENT '知识库 Chunk 备份';

-- RAG 效果评估 Bad Case 库（阶段4：评估与迭代闭环）
CREATE TABLE IF NOT EXISTS rag_bad_case (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    question       VARCHAR(512) NOT NULL COMMENT '用户问题',
    answer         TEXT         NOT NULL COMMENT '模型回答',
    references_json TEXT        NULL COMMENT '引用片段 JSON',
    consistency    INT          NULL COMMENT '事实一致性得分 1-5',
    relevance      INT          NULL COMMENT '相关性得分 1-5',
    completeness   INT          NULL COMMENT '完整度得分 1-5',
    reviewed       INT          NOT NULL DEFAULT 0 COMMENT '人工标注：0-未处理 1-已标注',
    feedback       VARCHAR(1024) NULL COMMENT '人工反馈/优化备注',
    created_at     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_reviewed (reviewed, created_at)
) ENGINE = InnoDB COMMENT 'RAG Bad Case';

-- 建表脚本。配上 spring.sql.init.mode=always 后每次启动都会执行，
-- 所以必须写成幂等的（IF NOT EXISTS），否则重启就炸。

CREATE TABLE IF NOT EXISTS review_task
(
    id                VARCHAR(32)  NOT NULL COMMENT '任务ID，uuid 前 8 位',
    repo              VARCHAR(200) NOT NULL COMMENT '仓库 owner/repo',
    commit_sha        VARCHAR(64)  NOT NULL,
    status            VARCHAR(16)  NOT NULL COMMENT 'PENDING/RUNNING/SUCCESS/FAILED',
    stage             VARCHAR(100) COMMENT '当前阶段文案',
    prompt_version    VARCHAR(16)  NOT NULL DEFAULT '' COMMENT '提示词版本，提示词一改旧结果就不能再当缓存用',
    rag_signature     VARCHAR(32)  NOT NULL DEFAULT '' COMMENT '召回参数签名，参数不同就是另一次评审',
    summary           VARCHAR(2000) COMMENT '评审总结',
    error             VARCHAR(2000) COMMENT '失败原因',
    issue_count       INT          NOT NULL DEFAULT 0 COMMENT '问题条数，冗余一列是为了列表页不用去 count',
    prompt_tokens     INT COMMENT '输入 token',
    completion_tokens INT COMMENT '输出 token',
    total_tokens      INT,
    cached_tokens     INT COMMENT '命中上下文缓存的输入 token',
    cost              DECIMAL(12, 6) COMMENT '预估费用（元）',
    elapsed_ms        BIGINT COMMENT '模型调用耗时',
    created_at        DATETIME     NOT NULL,
    started_at        DATETIME,
    finished_at       DATETIME,
    PRIMARY KEY (id),
    KEY idx_created_at (created_at),
    KEY idx_cache (repo, commit_sha, prompt_version, status)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT '评审任务';

CREATE TABLE IF NOT EXISTS review_task_context
(
    id             BIGINT AUTO_INCREMENT,
    task_id        VARCHAR(32)  NOT NULL,
    path           VARCHAR(300) NOT NULL COMMENT '被召回的文件路径',
    skeleton_chars INT          NOT NULL COMMENT '骨架字符数，真正进提示词的部分',
    raw_chars      INT          NOT NULL COMMENT '文件原始字符数',
    PRIMARY KEY (id),
    KEY idx_task_id (task_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT '评审召回的相关代码';

CREATE TABLE IF NOT EXISTS review_agent_step
(
    id                BIGINT AUTO_INCREMENT,
    task_id           VARCHAR(32)   NOT NULL,
    round_no          INT           NOT NULL COMMENT '第几轮模型调用',
    thought           TEXT COMMENT '模型这一轮说的话，是它为什么去读那个文件的唯一线索',
    tool_name         VARCHAR(32) COMMENT '调用的工具；为空表示这一轮模型没调工具、直接给出了结论',
    target            VARCHAR(300) COMMENT '操作对象：查符号时是符号名，读文件时是文件路径',
    arguments         TEXT COMMENT '模型给工具填的参数原文',
    result_summary    VARCHAR(1000) COMMENT '工具返回值摘要',
    prompt_tokens     INT           NOT NULL DEFAULT 0,
    completion_tokens INT           NOT NULL DEFAULT 0,
    cached_tokens     INT           NOT NULL DEFAULT 0,
    elapsed_ms        BIGINT        NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_task_id (task_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT 'agent 式评审的执行轨迹';

CREATE TABLE IF NOT EXISTS review_issue
(
    id         BIGINT AUTO_INCREMENT,
    task_id    VARCHAR(32)   NOT NULL,
    severity   VARCHAR(16)   NOT NULL,
    category   VARCHAR(32),
    file       VARCHAR(300)  NOT NULL,
    line_no    INT           NOT NULL COMMENT '新文件行号',
    issue      VARCHAR(1000) NOT NULL,
    suggestion VARCHAR(1000),
    evidence   VARCHAR(1000),
    PRIMARY KEY (id),
    KEY idx_task_id (task_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT '评审问题明细';

-- 加列不能用 IF NOT EXISTS（那是 MariaDB 的语法），所以自己查一遍元数据。
-- 这是轻量项目里最省事的幂等 DDL 写法，正经项目应该上 Flyway / Liquibase。
SET @col_exists := (SELECT COUNT(*)
                      FROM information_schema.COLUMNS
                     WHERE TABLE_SCHEMA = DATABASE()
                       AND TABLE_NAME = 'review_task'
                       AND COLUMN_NAME = 'prompt_version');
SET @ddl := IF(@col_exists = 0,
               'ALTER TABLE review_task ADD COLUMN prompt_version VARCHAR(16) NOT NULL DEFAULT "" COMMENT "提示词版本"',
               'DO 0');
PREPARE add_prompt_version FROM @ddl;
EXECUTE add_prompt_version;
DEALLOCATE PREPARE add_prompt_version;

SET @col_exists := (SELECT COUNT(*)
                      FROM information_schema.COLUMNS
                     WHERE TABLE_SCHEMA = DATABASE()
                       AND TABLE_NAME = 'review_task'
                       AND COLUMN_NAME = 'rag_signature');
SET @ddl := IF(@col_exists = 0,
               'ALTER TABLE review_task ADD COLUMN rag_signature VARCHAR(32) NOT NULL DEFAULT "" COMMENT "召回参数签名" AFTER prompt_version',
               'DO 0');
PREPARE add_rag_signature FROM @ddl;
EXECUTE add_rag_signature;
DEALLOCATE PREPARE add_rag_signature;

SET @idx_exists := (SELECT COUNT(*)
                      FROM information_schema.STATISTICS
                     WHERE TABLE_SCHEMA = DATABASE()
                       AND TABLE_NAME = 'review_task'
                       AND INDEX_NAME = 'idx_cache');
SET @ddl := IF(@idx_exists = 0,
               'ALTER TABLE review_task ADD INDEX idx_cache (repo, commit_sha, prompt_version, status)',
               'DO 0');
PREPARE add_idx_cache FROM @ddl;
EXECUTE add_idx_cache;
DEALLOCATE PREPARE add_idx_cache;

-- 建表脚本。配上 spring.sql.init.mode=always 后每次启动都会执行，
-- 所以必须写成幂等的（IF NOT EXISTS），否则重启就炸。

CREATE TABLE IF NOT EXISTS review_task
(
    id                VARCHAR(32)  NOT NULL COMMENT '任务ID，uuid 前 8 位',
    repo              VARCHAR(200) NOT NULL COMMENT '仓库 owner/repo',
    repo_name         VARCHAR(200) COMMENT '仓库显示名，如 若依/RuoYi；只是给人看的，接口调用一律用 repo',
    commit_sha        VARCHAR(64)  NOT NULL,
    status            VARCHAR(16)  NOT NULL COMMENT 'PENDING/RUNNING/SUCCESS/FAILED',
    stage             VARCHAR(100) COMMENT '当前阶段文案',
    prompt_version    VARCHAR(16)  NOT NULL DEFAULT '' COMMENT '提示词版本，记录这份结果是用哪版提示词跑出来的',
    rag_signature     VARCHAR(32)  NOT NULL DEFAULT '' COMMENT '历史字段：上下文召回参数签名，召回链路已移除，新记录为空',
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

CREATE TABLE IF NOT EXISTS review_repo_catalog
(
    id          BIGINT AUTO_INCREMENT,
    full_name   VARCHAR(200) NOT NULL COMMENT '仓库 owner/repo，正是提交评审时要的写法',
    name        VARCHAR(200) NOT NULL COMMENT '仓库名',
    description VARCHAR(500) COMMENT '仓库描述，下拉框里显示',
    stars       INT          NOT NULL DEFAULT 0 COMMENT 'star 数，只用于排序和展示',
    created_at  DATETIME     NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_full_name (full_name),
    KEY idx_stars (stars)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT '预置的 Gitee Java 项目清单';

SET @col_exists := (SELECT COUNT(*)
                      FROM information_schema.COLUMNS
                     WHERE TABLE_SCHEMA = DATABASE()
                       AND TABLE_NAME = 'review_task'
                       AND COLUMN_NAME = 'repo_name');
SET @ddl := IF(@col_exists = 0,
               'ALTER TABLE review_task ADD COLUMN repo_name VARCHAR(200) COMMENT "仓库显示名，如 若依/RuoYi" AFTER repo',
               'DO 0');
PREPARE add_repo_name FROM @ddl;
EXECUTE add_repo_name;
DEALLOCATE PREPARE add_repo_name;

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
               'ALTER TABLE review_task ADD COLUMN rag_signature VARCHAR(32) NOT NULL DEFAULT "" COMMENT "历史字段：上下文召回参数签名" AFTER prompt_version',
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

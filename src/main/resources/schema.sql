-- ReadCodeAI 核心表结构（第 1 步：符号表 / 调用边 / 类型关系）
--
-- 约定：
--   * 所有表用 CREATE TABLE IF NOT EXISTS，重复启动安全（由 Spring 的 sql.init 执行，不引 Flyway）
--   * 主键统一 BIGINT，不用业务键
--   * 需要索引的字符串列注意长度：utf8mb4 下 InnoDB 单列索引上限 3072 字节 = 768 字符，
--     所以长文本列（path / qualified_name）用前缀索引，否则建表直接失败

-- 1. 被索引的仓库
CREATE TABLE IF NOT EXISTS repo
(
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    name                VARCHAR(128) NOT NULL COMMENT '仓库名，取目录名',
    root_path           VARCHAR(512) NOT NULL COMMENT '索引时的绝对路径',
    git_url             VARCHAR(512) NULL,
    -- 记录索引时的提交号：源码后续变更时能识别出「这份索引基于哪个版本」
    commit_hash         CHAR(40)     NULL,
    file_count          INT          NOT NULL DEFAULT 0,
    parsed_ok_count     INT          NOT NULL DEFAULT 0,
    total_loc           INT          NOT NULL DEFAULT 0,
    symbol_count        INT          NOT NULL DEFAULT 0,
    call_edge_count     INT          NOT NULL DEFAULT 0,
    call_resolved_count INT          NOT NULL DEFAULT 0,
    status              VARCHAR(16)  NOT NULL COMMENT 'INDEXING / READY / FAILED',
    error_msg           TEXT         NULL,
    indexed_at          DATETIME     NULL,
    created_at          DATETIME     NOT NULL,
    -- 同一个路径重复索引 = 覆盖，不做多版本共存
    UNIQUE KEY uk_repo_root (root_path),
    KEY idx_repo_status (status)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT '被索引的仓库';

-- 2. 源文件清单与解析结果
CREATE TABLE IF NOT EXISTS source_file
(
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    repo_id      BIGINT        NOT NULL,
    path         VARCHAR(1024) NOT NULL COMMENT '相对仓库根的路径，统一用 / 分隔',
    -- 内容哈希是「行号漂移」的唯一防线：证据核验时必须比对它，而不是盲信库里的行号
    kind          VARCHAR(8)  NOT NULL DEFAULT 'JAVA' COMMENT 'JAVA = 参与解析统计；TEXT = 文本文件，只做检索',
    content_hash CHAR(64)      NOT NULL COMMENT 'SHA-256',
    loc          INT           NOT NULL DEFAULT 0,
    parsed_ok    TINYINT       NOT NULL DEFAULT 0,
    parse_error  TEXT          NULL,
    indexed_at   DATETIME      NOT NULL,
    UNIQUE KEY uk_file_repo_path (repo_id, path(255)),
    KEY idx_file_hash (content_hash),
    CONSTRAINT fk_file_repo FOREIGN KEY (repo_id) REFERENCES repo (id) ON DELETE CASCADE
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT '源文件与解析状态';

-- 3. 符号表：类/接口/枚举/记录/方法/构造器/字段
CREATE TABLE IF NOT EXISTS symbol
(
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    repo_id        BIGINT        NOT NULL,
    file_id        BIGINT        NOT NULL,
    kind           VARCHAR(16)   NOT NULL COMMENT 'CLASS/INTERFACE/ENUM/RECORD/ANNOTATION/METHOD/CONSTRUCTOR/FIELD',
    name           VARCHAR(256)  NOT NULL COMMENT '简单名',
    qualified_name VARCHAR(768)  NOT NULL COMMENT '全仓库唯一的检索键：类型=包名.类名；方法=类型#方法名/参数个数；字段=类型.字段名',
    signature      VARCHAR(1024) NULL COMMENT '人类可读签名，用于展示与检索',
    parent_id      BIGINT        NULL COMMENT '所属类型的 symbol.id；顶层类型为 NULL',
    start_line     INT           NOT NULL,
    end_line       INT           NOT NULL,
    modifiers      VARCHAR(128)  NULL,
    return_type    VARCHAR(256)  NULL,
    javadoc        TEXT          NULL,
    KEY idx_symbol_repo_name (repo_id, name),
    KEY idx_symbol_qname (repo_id, qualified_name(191)),
    KEY idx_symbol_parent (parent_id),
    KEY idx_symbol_file (file_id),
    KEY idx_symbol_kind (repo_id, kind),
    CONSTRAINT fk_symbol_repo FOREIGN KEY (repo_id) REFERENCES repo (id) ON DELETE CASCADE,
    CONSTRAINT fk_symbol_file FOREIGN KEY (file_id) REFERENCES source_file (id) ON DELETE CASCADE
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT '符号表';

-- 4. 调用边：调用者 -> 被调用者
CREATE TABLE IF NOT EXISTS call_edge
(
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,
    repo_id          BIGINT       NOT NULL,
    caller_symbol_id BIGINT       NOT NULL,
    callee_symbol_id BIGINT       NULL COMMENT '解析成功时指向仓库内的符号；解析不出为 NULL',
    -- 解析不出来时保留原文：这是「诚实记录盲区」的关键 —— 让「没解析出来」与「不存在」严格区分
    callee_raw       VARCHAR(512) NOT NULL COMMENT '未解析时的调用原文，如 uploadService#uploadMusic/1',
    call_line        INT          NOT NULL,
    call_kind        VARCHAR(16)  NOT NULL COMMENT 'METHOD/CONSTRUCTOR/STATIC/SUPER',
    resolved         TINYINT      NOT NULL DEFAULT 0,
    reason           VARCHAR(32)  NULL COMMENT 'EXTERNAL/UNSOLVED/DYNAMIC/AMBIGUOUS',
    KEY idx_edge_caller (caller_symbol_id),
    KEY idx_edge_callee (callee_symbol_id),
    KEY idx_edge_repo_resolved (repo_id, resolved),
    CONSTRAINT fk_edge_repo FOREIGN KEY (repo_id) REFERENCES repo (id) ON DELETE CASCADE,
    CONSTRAINT fk_edge_caller FOREIGN KEY (caller_symbol_id) REFERENCES symbol (id) ON DELETE CASCADE,
    CONSTRAINT fk_edge_callee FOREIGN KEY (callee_symbol_id) REFERENCES symbol (id) ON DELETE SET NULL
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT '调用图';

-- 5. 类型关系：继承 / 实现
CREATE TABLE IF NOT EXISTS type_relation
(
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    repo_id         BIGINT       NOT NULL,
    sub_symbol_id   BIGINT       NOT NULL,
    super_raw       VARCHAR(512) NOT NULL,
    super_symbol_id BIGINT       NULL,
    kind            VARCHAR(16)  NOT NULL COMMENT 'EXTENDS/IMPLEMENTS',
    resolved        TINYINT      NOT NULL DEFAULT 0,
    external        TINYINT      NOT NULL DEFAULT 0 COMMENT '父类型不在本仓库内（如框架基类）',
    KEY idx_type_sub (sub_symbol_id),
    KEY idx_type_super (super_symbol_id),
    KEY idx_type_repo_kind (repo_id, kind),
    CONSTRAINT fk_type_repo FOREIGN KEY (repo_id) REFERENCES repo (id) ON DELETE CASCADE,
    CONSTRAINT fk_type_sub FOREIGN KEY (sub_symbol_id) REFERENCES symbol (id) ON DELETE CASCADE,
    CONSTRAINT fk_type_super FOREIGN KEY (super_symbol_id) REFERENCES symbol (id) ON DELETE SET NULL
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT '类型继承与实现关系';

-- 6. 按符号切分的检索单元（第 2 步：全文检索层）
--
-- 关键设计：**按符号切，不按行切**。按固定行数切会把一个方法劈成两半，
-- 检索到上半段时模型看不到返回逻辑，于是自信地给出错误结论。
CREATE TABLE IF NOT EXISTS chunk
(
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    repo_id        BIGINT      NOT NULL,
    file_id        BIGINT      NOT NULL,
    symbol_id      BIGINT      NULL COMMENT 'SYMBOL 类 chunk 指向所属符号；FILE_HEADER/IMPORT_BLOCK 为 NULL',
    kind           VARCHAR(16) NOT NULL COMMENT 'SYMBOL / FILE_HEADER / IMPORT_BLOCK',
    start_line     INT         NOT NULL,
    end_line       INT         NOT NULL,
    content_hash   CHAR(64)    NOT NULL,
    content        MEDIUMTEXT  NOT NULL,
    token_estimate INT         NOT NULL DEFAULT 0 COMMENT '粗估，用于上下文预算',
    KEY idx_chunk_file (file_id),
    KEY idx_chunk_symbol (symbol_id),
    KEY idx_chunk_repo_kind (repo_id, kind),
    -- 中文注释也要能搜到，所以必须用 ngram 解析器（默认解析器按空格切词，中文会整段失效）
    FULLTEXT KEY ft_chunk_content (content) WITH PARSER ngram,
    CONSTRAINT fk_chunk_repo FOREIGN KEY (repo_id) REFERENCES repo (id) ON DELETE CASCADE,
    CONSTRAINT fk_chunk_file FOREIGN KEY (file_id) REFERENCES source_file (id) ON DELETE CASCADE,
    CONSTRAINT fk_chunk_symbol FOREIGN KEY (symbol_id) REFERENCES symbol (id) ON DELETE SET NULL
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT '按符号切分的检索单元';

-- 7. 自动生成的评估题（第 6 步：分水岭二）
--
-- 关键：**ground_truth 来自静态分析，不来自 LLM** ——
-- 所以不存在「用模型判模型」的循环依赖，这是整套评估可信的前提。
CREATE TABLE IF NOT EXISTS question
(
    id                BIGINT AUTO_INCREMENT PRIMARY KEY,
    repo_id           BIGINT       NOT NULL,
    qtype             VARCHAR(16)  NOT NULL COMMENT 'LOCATE/CALLERS/CALLEES/STRUCTURE/IMPLEMENTS/IMPACT',
    question_text     VARCHAR(1024) NOT NULL,
    payload_json      JSON         NOT NULL COMMENT '题目参数（目标符号等）',
    -- 标准答案由静态分析算出；判卷时与它比对，不需要人工标注
    ground_truth_json JSON         NOT NULL,
    generator_version VARCHAR(32)  NOT NULL COMMENT '生成规则一变就要升版本，否则旧题不可比',
    seed              BIGINT       NOT NULL COMMENT '固定种子 → 同一 seed 出同一批题，保证可复现',
    created_at        DATETIME     NOT NULL,
    KEY idx_question_repo_type (repo_id, qtype),
    KEY idx_question_seed (repo_id, seed),
    CONSTRAINT fk_question_repo FOREIGN KEY (repo_id) REFERENCES repo (id) ON DELETE CASCADE
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT '自动生成的评估题';

-- 8. 每次评估运行的汇总指标
CREATE TABLE IF NOT EXISTS eval_run
(
    id                BIGINT AUTO_INCREMENT PRIMARY KEY,
    repo_id           BIGINT      NOT NULL,
    seed              BIGINT      NOT NULL,
    generator_version VARCHAR(32) NOT NULL,
    mode              VARCHAR(16) NOT NULL COMMENT 'STATIC_ONLY / WITH_LLM',
    total_questions   INT         NOT NULL,
    answered          INT         NOT NULL,
    refused           INT         NOT NULL,
    failed            INT         NOT NULL,
    hit_count         INT         NOT NULL COMMENT '精确命中的题数（按题型细分见 metrics_json）',
    metrics_json      JSON        NOT NULL COMMENT '分题型命中率、证据有效率、平均延迟与 token',
    ran_at            DATETIME    NOT NULL,
    KEY idx_eval_repo (repo_id, ran_at),
    CONSTRAINT fk_eval_repo FOREIGN KEY (repo_id) REFERENCES repo (id) ON DELETE CASCADE
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT '评估运行汇总';

-- 10. 摘要缓存：结构每次现算（便宜且要新鲜），模型补的那句语义按「索引版本」缓存
CREATE TABLE IF NOT EXISTS repo_summary
(
    id                BIGINT AUTO_INCREMENT PRIMARY KEY,
    repo_id           BIGINT       NOT NULL,
    -- 索引版本：索引时间戳变了，缓存即失效。重新索引会删掉 repo 行（外键级联），所以这里是双保险
    indexed_at        DATETIME     NOT NULL,
    model             VARCHAR(128) NOT NULL COMMENT '模型名：换模型等于换一份生成结果',
    notes             JSON         NOT NULL COMMENT '每个模块的一句话 + 核对结果',
    prompt_tokens     INT          NOT NULL DEFAULT 0,
    completion_tokens INT          NOT NULL DEFAULT 0,
    generated_at      DATETIME     NOT NULL,
    UNIQUE KEY uk_summary_version (repo_id, indexed_at, model),
    CONSTRAINT fk_summary_repo FOREIGN KEY (repo_id) REFERENCES repo (id) ON DELETE CASCADE
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT '摘要的语义部分缓存（可重新生成）';

-- 11. 索引任务：异步索引的"任务单"（接单 → 后台跑 → 随时可查进度）
--
-- 为什么是一张表而不是内存里的队列：**进程重启后要能看出"上次那个任务没跑完"**。
-- 内存队列重启即丢，表现就是"仓库卡在 INDEXING 再也回不来" —— 这张表让这种状态可见。
-- （真要做到"重启后自动接着跑"，那是消息队列的活，见 docs/design-outline.md 的选型表。）
CREATE TABLE IF NOT EXISTS index_job
(
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    repo_id    BIGINT       NULL COMMENT '仓库行创建后回填；拉取/解压阶段还没有仓库',
    kind       VARCHAR(16)  NOT NULL COMMENT 'LOCAL / GIT / ARCHIVE',
    source     VARCHAR(512) NOT NULL COMMENT '本地路径、GitHub 链接或原压缩包名',
    status     VARCHAR(16)  NOT NULL COMMENT 'QUEUED / RUNNING / READY / FAILED',
    stage      VARCHAR(16)  NULL COMMENT 'QUEUED/FETCHING/EXTRACTING/SCANNING/PARSING/STORING/DONE/FAILED',
    done       INT          NOT NULL DEFAULT 0 COMMENT '当前阶段已完成的量（例如已解析文件数）',
    total      INT          NOT NULL DEFAULT 0 COMMENT '当前阶段的总量',
    message    VARCHAR(255) NULL,
    created_at DATETIME     NOT NULL,
    updated_at DATETIME     NOT NULL,
    KEY idx_job_repo (repo_id),
    KEY idx_job_status (status),
    CONSTRAINT fk_job_repo FOREIGN KEY (repo_id) REFERENCES repo (id) ON DELETE CASCADE
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT '索引任务与进度';

-- 12. chunk 向量：第 3 层检索（向量相似度）的存储。
--
-- 为什么是 BLOB 而不是向量库：语料是万级 chunk × 1024 维 float ≈ 几 MB，
-- 进程内算余弦绰绰有余；真到了几十万 chunk 或多实例共享，再考虑 pgvector（判据在 design-outline.md）。
-- 为什么键里带 model 与 content_hash：换模型 = 换向量空间，旧向量不可比；
-- chunk 内容变了（重新索引）旧向量就是错的 —— 用 hash 比对，不一致就重算。
CREATE TABLE IF NOT EXISTS chunk_embedding
(
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    repo_id      BIGINT      NOT NULL,
    chunk_id     BIGINT      NOT NULL,
    model        VARCHAR(64) NOT NULL,
    dimensions   INT         NOT NULL,
    content_hash CHAR(64)    NOT NULL,
    vector       BLOB        NOT NULL COMMENT 'float[] 按小端序列化',
    created_at   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_chunk_vector (chunk_id, model),
    KEY idx_chunk_vector_repo (repo_id, model),
    CONSTRAINT fk_chunk_vector_repo FOREIGN KEY (repo_id) REFERENCES repo (id) ON DELETE CASCADE,
    CONSTRAINT fk_chunk_vector_chunk FOREIGN KEY (chunk_id) REFERENCES chunk (id) ON DELETE CASCADE
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT 'chunk 向量缓存（第 3 层检索基线）';

-- 13. answer_log：每次问答的流水 —— **质量与成本的账**。
--
-- 为什么值得单独一张表：评估集（question / eval_run）记的是「自动出题的判卷结果」，
-- 那是机器给自己打的卷；**「这个服务被问了多少次、花了多少 token、拒答了多少」**
-- 从任何现有表都推不出来。有了它，「④ 指标」页才能把两者分开说。
--
-- 为什么 repo_id 级联删除：问答流水是**某个仓库的数据**，删仓库就该连它一起清
-- （测试造的临时仓库也因此不会把统计数字撑起来）。
--
-- 为什么 question_id 没有外键：评估集跑题也走同一条问答流水线，那些行关联到 question.id；
-- 但流水是**账本**，不该因为题目被重新生成/清理而被改写或导致写入失败 —— 关联关系记在值里即可。
--
-- 为什么还要一列 source：题目目前**不落库**（评估集现场出题、现场判卷），拿不到 question.id，
-- 于是用 source 直接说明"这次是谁问的"。它的用处很实际：跑一次评估就是 200+ 行流水，
-- 不标出来，页面上的"累计问答"就被评估跑题撑起来了（那不是用户提问）。
--
-- 口径（页面与文档都要照这个说）：**cache_hit=1 的行不代表这次花了 token**，
-- 它记的是「这份答案当初生成花了多少、这次省下了」；所以
-- 「实际花费」= SUM(...) WHERE cache_hit = 0，「省下的」= SUM(...) WHERE cache_hit = 1。
CREATE TABLE IF NOT EXISTS answer_log
(
    id                BIGINT AUTO_INCREMENT PRIMARY KEY,
    repo_id           BIGINT         NULL COMMENT '归属仓库；删仓库级联删除',
    question_id       BIGINT         NULL COMMENT '评估集跑题时关联 question.id；用户自己提问为空',
    source            VARCHAR(8)     NOT NULL DEFAULT 'USER' COMMENT 'USER = 用户提问；EVAL = 评估集/对比实验跑题（不计入用户问答统计）',
    question          VARCHAR(512)   NOT NULL,
    mode              VARCHAR(16)    NOT NULL COMMENT 'STATIC / SINGLE_HOP / MULTI_HOP：实际走的路线',
    answered_by       VARCHAR(8)     NOT NULL COMMENT 'STATIC / LLM / NONE：答案由谁给出',
    route_json        VARCHAR(1024)  NULL COMMENT '确定性路由的路线与命中的目标符号',
    hops              INT            NOT NULL DEFAULT 0 COMMENT '多跳轮次；单跳与静态路线为 0',
    prompt_tokens     INT            NOT NULL DEFAULT 0,
    completion_tokens INT            NOT NULL DEFAULT 0,
    -- ③ 层核验是**另一笔账**（它自己也是一次模型调用）：
    -- 生成答案花了多少与核验这条答案花了多少，混在一列里就两个都说不清（见 SupportCheck 的注释）
    support_prompt_tokens     INT NOT NULL DEFAULT 0,
    support_completion_tokens INT NOT NULL DEFAULT 0,
    cost              DECIMAL(12, 6) NOT NULL DEFAULT 0 COMMENT '估算成本 = 生成 + 核验（同一单价；免费档恒为 0）',
    latency_ms        BIGINT         NOT NULL DEFAULT 0,
    evidence_verified INT            NOT NULL DEFAULT 0 COMMENT '最终采纳的证据条数',
    evidence_rejected INT            NOT NULL DEFAULT 0 COMMENT '①② 层拦下的条数（首轮未过；可能被定向修正救回）',
    support_status    VARCHAR(16)    NULL COMMENT '③ 层判定：SUPPORTED / UNSUPPORTED / UNAVAILABLE / NOT_CHECKED',
    refused           TINYINT(1)     NOT NULL DEFAULT 0,
    refusal_reason    VARCHAR(512)   NULL,
    cache_hit         TINYINT(1)     NOT NULL DEFAULT 0 COMMENT '1 = 本次取缓存，没有重新生成',
    answer_json       MEDIUMTEXT     NULL COMMENT '答案 + 证据 + 轨迹摘要（事后回溯用）',
    created_at        DATETIME       NOT NULL,
    KEY idx_answer_log_repo (repo_id, created_at),
    KEY idx_answer_log_created (created_at),
    CONSTRAINT fk_answer_log_repo FOREIGN KEY (repo_id) REFERENCES repo (id) ON DELETE CASCADE
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT '问答流水：质量与成本（每次问答一行）';

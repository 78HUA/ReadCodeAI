# ReadCodeAI

给一个 GitHub 仓库或本地代码库，它先告诉你**这个库是干什么的**，然后你可以接着追问代码里的问题。
每条结论都带「文件 + 行号 + 原文片段」的证据，**点开就是磁盘上的真实代码** ——
编造的路径、行号或片段过不了核验，会被挡下来，而不是原样递给你。

用一句话概括它和"把代码丢给 AI 让它读"的区别：

> **能算准的，绝不叫模型猜。**
> 「谁调用了这个方法」「这个类有哪些实现」这类问题，答案是查符号表和图算出来的；
> 模型在这套流程里只负责两件事：决定"还要再查什么"，以及把查到的东西讲清楚。

## 它能做什么

| 能力 | 说明 | 需要模型吗 |
|---|---|---|
| **索引仓库** | 三个入口：GitHub 链接 / 服务器本地路径 / 上传 zip。**异步执行**：提交立刻返回任务，进度按阶段可查（已解析 N/M 个文件）；产出符号表、调用图、类型关系、可检索的代码块 | 否 |
| **定位符号** | 「X 定义在哪」，精确到文件与起止行 | 否 |
| **调用关系** | 谁调用了它 / 它调用了谁（未解析的调用如实计入缺口，不假装没有） | 否 |
| **实现关系** | 某个接口有哪些实现类 / 某个类有哪些子类 | 否 |
| **全文检索** | 按标识符或注释里的原词搜代码（中文注释也能搜） | 否 |
| **结构化摘要** | 模块划分、规模、入口、调用枢纽、实现关系、没有调用者的类 —— **全部查库算出来**；每个模块"大致负责什么"由模型补一句话，且它提到的符号名会回索引核对 | 可选 |
| **追问（单跳）** | 一次检索 + 模型组织答案，答案必须带证据 | 是 |
| **追问（多跳）** | 模型自己决定"再查什么、跳几跳、什么时候停"，配套环检测与四维预算（轮次/时长/token/成本） | 是 |
| **代码审查** | 规则先算出能算的（超长方法、空 catch、没有人调用、调用盲区占比），模型再补它读出来的问题（每条带证据） | 可选 |
| **自动评估** | 自动出题 + 自动判卷，答案由静态分析算出，不花 token、可复现 | 否 |

## 快速开始

### 1. 环境要求

- **JDK 21**
- **Maven 3.8+**
- **MySQL 8**（用到 ngram 全文解析器，MySQL 8 自带）
- **Node 20+**（只有改前端时才需要）

### 2. 建库

```sql
CREATE DATABASE readcodeai DEFAULT CHARACTER SET utf8mb4;
```

表结构不用手工建：启动时 `schema.sql` 会自动执行，且全部是 `CREATE TABLE IF NOT EXISTS`，重复启动安全。

### 3. 配置（只从环境变量读，不落配置文件）

```bash
# 必需：数据库口令
export READCODEAI_DB_PASSWORD=你的口令
# 可选（不配也能跑，见下一节）
export READCODEAI_DB_HOST=127.0.0.1
export READCODEAI_DB_USERNAME=root
export READCODEAI_LLM_BASE_URL=https://open.bigmodel.cn/api/paas/v4
export READCODEAI_LLM_API_KEY=你的Key
export READCODEAI_LLM_MODEL=glm-4-flash
# 可选：Redis 只用来做"答案缓存"（同一个问题第二次问，52 秒 → 0.1 秒）
# 不起 Redis 也能跑，只是每次都真算
export READCODEAI_REDIS_HOST=127.0.0.1
export READCODEAI_REDIS_PORT=6379
```

任何 OpenAI 兼容端点都可以（`/chat/completions`），不限某一家。

### 4. 启动

```bash
# 后端（前端产物已经构建过的话，一条命令就够）
mvn spring-boot:run
# 或者
mvn package -DskipTests && java -jar target/readcodeai-0.1.0-SNAPSHOT.jar
```

```bash
# 前端（首次、或改过 frontend/ 下的源码）
cd frontend
npm install            # 全局 npm 缓存无写权限时：npm install --cache .npm-cache
npm run build          # 产物直接写进 src/main/resources/static/
```

然后打开 <http://localhost:8080>。

开发前端时用 `npm run dev`（Vite 起在 5173，把 `/api` 代理到 8080）；改完记得 `npm run build` 一次 ——
**构建产物不进仓库**，就是为了避免"改了源码忘了重建"。

### 5. 不配模型会怎样

**剥掉模型，它仍然是个能用的工具**，而不是一个空壳：

| 情况 | 表现 |
|---|---|
| 没配 `READCODEAI_LLM_*` | 索引、定位、调用关系、实现关系、全文检索、结构化摘要的**结构部分**、自动评估 —— 全部照常可用 |
| 这时问语义问题 | 明确报错说"未配置 LLM，语义问答不可用"，并说明还剩哪些能力（HTTP 503），**不会静默返回空答案** |
| 摘要的语义部分 | 返回 `semantics.available=false` 与原因，结构部分完整 |
| 代码审查 | 规则部分照常，模型部分标记为不可用 |
| 没起 Redis | **只是没有缓存**：问答照常，每次真算；日志里一条 warn 说明"取缓存失败（按未命中处理）" |

## 用法

四个页面就是完整流程：**① 添加仓库 → ② 看概览 → ③ 追问 → ④ 看指标**。

### 追问：答案分三块

**结论 / 证据 / 过程**。证据卡片能点开，显示的是**磁盘上此刻的真实内容**，
并告诉你"这个文件在索引之后有没有被改过"（改过则行号可能已漂移）。

![追问：证据卡片与多跳轨迹](docs/images/ask-multihop.png)

### 证据核对：点开就是真实代码

![证据抽屉：来自磁盘的真实内容](docs/images/evidence-drawer.png)

### 指标：一键跑评估

题目自动生成、答案由静态分析算出、判卷也是程序做的 —— 几秒跑完、不花 token、同一 seed 完全可复现。

![指标页：自动评估结果](docs/images/metrics.png)

> 这个高命中率**必须打星号**：题从索引出、答案也从索引答，同源，所以它是**按构造**的，
> 衡量的是"确定性管线有没有丢信息、有没有走错路"，**不是**开放问答的准确率。
> 界面上也把这句写在数字旁边。

## 接口一览

统一返回 `{code, message, data}`（`code=0` 成功，HTTP 状态码同时体现）。

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/repos` | 已索引仓库列表（含解析成功率、调用解析率） |
| POST | `/api/repos` | **提交索引任务**：`{"path"}` 或 `{"gitUrl"}` → 立刻返回 `{id, repoId, status, stage}` |
| GET | `/api/repos/{id}` | 单个仓库：仓库记录 + **最近一次索引任务的进度** |
| POST | `/api/repos/upload` | 上传 zip 建索引（multipart，字段名 `file`），同样异步 |
| DELETE | `/api/repos/{id}` | 删除索引（子表随外键级联） |
| GET | `/api/index-jobs/{id}` | 索引任务的进度：`stage / done / total / percent` |
| GET | `/api/index-jobs/queue` | 队列状态：正在跑几个、排队几个 |
| GET | `/api/symbols/locate?repoId=&name=` | 定位符号 |
| GET | `/api/symbols/{id}` | 符号详情 |
| GET | `/api/symbols/{id}/callers` · `/callees` · `/implementations` | 调用关系与实现关系 |
| GET | `/api/search?repoId=&q=` | 全文检索 |
| POST | `/api/ask` | 单跳问答（`{repoId, question, scopePath?, topK?}`） |
| POST | `/api/agent` | 多跳问答（`{repoId, question, mode: single\|multi}`） |
| GET·POST | `/api/summary` | 结构化摘要（`semantics=false` 只要结构；`refresh=true` 强制重新生成语义） |
| POST | `/api/review` | 代码审查（`{repoId, target, focus?}`，`target` 是类名或符号 id） |
| GET | `/api/files/content?repoId=&path=&startLine=&endLine=` | 读磁盘上的真实文件内容（证据核对用） |
| POST | `/api/eval/run` | 跑评估集（`{repoId, seed?, perType?}`） |

## 配置项

都在 `application.yml` 的 `readcodeai.*` 下，可用环境变量或启动参数覆盖。

| 配置 | 默认 | 说明 |
|---|---|---|
| `llm.enabled` | `true` | 关掉即降级为无模型模式 |
| `llm.base-url` / `api-key` / `model` | 空 | 三样都给齐才启用真实客户端 |
| `llm.timeout-seconds` | 60 | 单次调用超时 |
| `llm.max-rounds` | 8 | 多跳轮次上限。**最后一轮留给结论**，所以实际最多查 6 跳 |
| `llm.max-duration-ms` | 60000 | 多跳时长预算（决定"不再发起下一轮"，不中断进行中的调用） |
| `llm.max-estimated-tokens` | 60000 | 多跳 token 预算 |
| `llm.max-estimated-cost` | 0.5 | 成本预算（按单价估算；免费档单价为 0 时这一维不起作用） |
| `index.workspace` | `~/.readcodeai/repos` | 远程拉取与上传解压的存放目录 |
| `index.max-file-size-kb` | 2048 | 单文件超过就跳过并记录 |
| `cache.enabled` | `true` | 答案缓存开关；关掉或 Redis 连不上都只是"每次真算" |
| `cache.answer-ttl-minutes` | 1440 | 答案缓存的 TTL（键里含索引版本，重新索引即自动失效） |
| `verify.support-check` | `mark` | ③ 层核验（代码是否支持结论）：`off` 不判 / `mark` 判定并把"不支持"标出来 / `reject` 判成不支持就拒答 |
| `embedding.enabled` / `model` / `dimensions` | `true` / `embedding-3` / `1024` | 向量检索**基线**（对比实验用，未接问答路由）；与 LLM 共用连接配置，关掉不影响问答 |
| `spring.data.redis.*` | 127.0.0.1:6379 | Redis 连接（走环境变量 `READCODEAI_REDIS_*`） |
| `retrieve.top-k` | 8 | 一次给模型的代码块上限 |
| `retrieve.max-context-tokens` | 30000 | 上下文预算 |
| `retrieve.max-chunks-per-file` | 3 | 防止热门文件霸占上下文 |

## 架构

```
api/          REST 接口（前端与脚本都走它）
agent/        多跳循环：AgentLoop + 工具集 + 环检测 + 四维预算 + 模型输出契约
agent/cache/  答案缓存（Redis；连不上自动降级为不缓存）
retrieve/     检索：符号查询（确定性）· 全文检索（ngram）· 向量相似度（基线，只做对比实验不接路由）
evidence/     证据核验（① 文件行号有效 ② 片段与磁盘一致 ③ 代码是否**支持**结论：模型判定，默认只标记不丢弃）
index/        索引：扫描 → JavaParser 解析 → 符号/调用图/类型关系落库；含异步任务与进度
summary/      结构化摘要（结构查库 + 模型补一句话 + 回索引核对）
review/       代码审查（规则算的 + 模型读的，两份分开返回）
eval/         自动出题与自动判卷
frontend/     Vue 3 + Vite（构建产物写进 src/main/resources/static/）
```

数据库 9 张表：`repo` `source_file` `symbol` `call_edge` `type_relation` `chunk` `question` `eval_run` `repo_summary`。
（设计文档里的第 10 张 `answer_log` —— 用于记录每次问答的 token 与耗时 —— **还没建**，见「已知限制」。）
调用图**保留解析不出来的调用**（`resolved=0` + 原文 + 原因），把"没解析出来"和"不存在"严格区分开。

## 设计上的几个取舍

- **按符号切分，不按行切**：按固定行数切会把方法拦腰截断，检索出来的证据天然不完整。
- **问题先路由，再决定用不用模型**：确定性问题（定位、调用、实现、结构）不叫模型 ——
  把确定性换成不确定性是净亏。
- **证据分三层核验**：① 文件与行号是否有效 → ② 片段是否与磁盘一致（真读文件）→ ③ 这段代码是否支持结论。
  **①② 是程序做的**（零成本、结论确定）；**③ 要额外叫一次模型**，所以默认行为是**把"不支持"标出来、
  不直接丢答案**（`verify.support-check=mark`，可改 `reject` 或 `off`）。实测：程序构造的张冠李戴与
  过度断言 8/8 全被判出、正确样本 0 误报，代价约 +2 秒 / +500 token —— 数字与边界见 `docs/verification-log.md`。
- **不引 AI 框架**：需要的只是一个 HTTP 接口和一段手写的工具调用循环，自己写才讲得清、测得动。
- **不引向量库**：代码检索的主要形态是"精确标识符"和"图查询"，语义相似度只对少数模糊问题有用。
  向量基线已实现（存 MySQL BLOB、进程内算余弦、按需补算缓存），但**不接问答路由**——
  对比实验实测它在这套评估题上召回上限 20%（见"实测与已知限制"），这个判断现在是实测结论不是偏好；
  真到了几十万 chunk 或多实例共享，再考虑 pgvector。
- **缓存要可见**：摘要的语义部分按「仓库 + 索引版本 + 模型名」缓存，接口把
  `cached / generatedAt / token 用量` 一起返回，页面标出"来自缓存"并提供「重新生成」——
  悄悄给你一份上次的说明是不行的。

更完整的设计说明（洞察、数据模型、阶段计划）见 [docs/design-outline.md](docs/design-outline.md)。

## 测试

```bash
# 需要本机 MySQL；语料目录用 -Dreadcodeai.verify.repo 指定（默认 sample-repos/）
mvn test -Dreadcodeai.verify.repo=/path/to/a/java/repo

# 需要真实模型的用例（连通性、单跳问答、多跳实验）在没配 Key 或接口不可达时自动跳过并写明理由
export READCODEAI_LLM_API_KEY=...
mvn test -Dreadcodeai.verify.repo=/path/to/a/java/repo
```

当前：**157 个测试 · 0 失败 · 4 跳过**（跳过的是网络用例、与语料相关的可选断言、以及模型接口不可达时的实测用例）。

## 实测与已知限制

关键数字与实验记录都在 [docs/verification-log.md](docs/verification-log.md)，包括：

- 调用图准确率的人工抽查（用 IDEA 的 Find Usages 当基准，含差异归因）
- 证据核验拦下了什么（逐条构造编造，看能不能过）
- 自动评估集在 gson 上 211 题的结果，以及**为什么这个数字要打星号**
- 单跳 vs 多跳的对比实验（真值由调用图反向 BFS 算出）：单跳上限 24.4% → 多跳 86.7% 平均召回；
  以及**结论率只有 3/15** 这个短板的真实数据
- 摘要缓存的效果（首次 14.4 秒 → 之后 0.097 秒）
- 异步索引：提交返回 16 秒 → **196 ms**，索引期间其它接口仍 **12 ms** 可用，进度按阶段可查
- 答案缓存（Redis）：同一个问题第二次 **52.9 秒 → 0.113 秒**（约 468 倍），且一个 token 不花；
  杀掉 Redis 后问答照常（只是每次真算）
- 代码审查的实测：规则稳定可用、模型意见多数不可用，以及"证据全对、结论全错"的具体例子
- ③ 层核验（这段代码是否**支持**结论）：程序构造的张冠李戴 + 过度断言 **8/8 全被判出**、
  正确样本 **0 条误报**（4 条里 3 条判支持、1 条判"判不了"），代价约 **+2 秒 / +500 token**；
  同一批样本第一版的数字更差 —— 因为**样本本身是错的**，那段过程也记在验证记录里
- **第一组对比实验（纯向量 vs 三层检索）**：同一批 131 道评估题，纯向量 RAG 的召回@8 上限 **20.0%**，
  确定性路由 100%（量尺自检）；分题型 LOCATE 40% / CALLERS 29% / CALLEES 5% / IMPLEMENTS 0% ——
  向量相似度找的是"文本长像"，不是"图上的关系"，这就是检索不交给向量的实测理由。
  向量存 MySQL 不引向量库，首次补算 1515 chunk 花 13.2 万 token（几分钱），之后重跑零成本

**已知限制**（每条在验证记录里都有数据）：

1. **③ 层核验是模型判的，不是程序判的**：它能抓出"证据真实、但与结论不是同一件事"
   （构造样本 8/8 检出），但**判错是可能的** —— 所以默认只标记、不拒答。
   判定失败会如实记为"这次没核验成"（`UNAVAILABLE`），**不会被当成通过**；
   样本量也还小：真实问答那一组只测到个位数条。
2. **静态分析的盲区**：反射、动态代理、Lombok 生成代码、泛型擦除 —— 未解析的调用如实计入缺口，不补猜。
3. **多跳的结论率偏低**：模型常常查得很起劲但不肯收尾；此时退回"没有结论，但把查到的链路与证据一并交出"。
4. **代码审查只看单个类**：跨文件的一致性问题（同一个状态码在两个文件里含义不同）看不到。
5. **摘要不进自动评估**：它没有唯一正确答案，所以只能标"名字在不在索引里"，不能判对错。
6. **向量检索只是基线，且"中文鸿沟"仍未量化**：向量层已实现（存 MySQL、不引向量库、按需补算缓存），
   但**没有接进问答路由**——对比实验实测它在五类可算准的题上召回上限只有 20%（路由 100%），
   这就是不接的理由。而立项动机"中文提问 ↔ 英文标识符"的鸿沟也还没量化：让小模型把题面改写成
   纯中文的路线失败了（12 条改写全部仍含标识符），后续要换路子。
7. **重新索引会重建仓库记录**：同一个路径重新索引是"删了再插"，所以 `repoId` 会变 ——
   脚本里别把它当长期稳定标识，每次先 `GET /api/repos` 查一遍。
8. **索引任务不持久化**：进程内队列的代价 —— 重启后排队与运行中的任务会丢，
   启动时会把它们如实标成失败并提示"请重新提交"。要做到重启自动接着跑，就需要消息队列了
   （这也是这个项目"什么时候该上 MQ"的具体判据）。
9. **问答的用量没有落库**：设计文档里的 `answer_log` 表（记录每次问答的 token、耗时、是否拒答）还没建，
   所以现在看不到"累计花了多少、拒答率多少"这类指标 —— 指标页展示的是自动评估的结果，不是运行统计。

## 目录

```
src/main/java/com/readcodeai/   后端（Java 21 / Spring Boot 4）
src/main/resources/schema.sql   建表脚本（启动时自动执行）
frontend/                       前端源码（Vue 3 + Vite，产物不进仓库）
docs/design-outline.md          设计文档
docs/verification-log.md        验证记录（每个阶段的实测数字与边界）
```

# Spring AI 引擎迁移实验（分支 `feat/spring-ai-engine`）

> **这是一次"最小迁移"的实验记录**：只把**多跳引擎**换成 Spring AI 实现，其余（解析 / 索引 / 检索 /
> 三层核验 / 评估 / 中间件 / REST / 前端）**一行都没动** —— 框架碰不到它们。
> **默认引擎已切换为 Spring AI**（`readcodeai.agent.engine`，默认 `spring-ai`）；
> 手写版保留为可切换的退路与 A/B 对照（两者共用同一个 `AgentEngine` 契约）。

## 第二步：切默认 + 实测（2026-09-26 完成）

### 为"切默认"补的三件事

1. **引擎进答案缓存的键**（`AnswerCache.get/put` 多一维 `engine`）——两个引擎共存时，
   不带这一维就会出现"用 A 引擎问过、换 B 引擎直接拿到 A 的答案"，与当年"键漏了模型名"是同一类漏洞。
   已加回归断言：`AgentAnswerCacheTest` 里"换引擎 → 键必须变"。
2. **属性收进 `ReadCodeAiProperties`**（`readcodeai.agent.engine`，枚举 `Engine`，非法值启动即报错）。
3. **可用性判断下沉到引擎**（`AgentEngine.available()` / `unavailableReason()`）——
   这是写离线覆盖测试时真撞出来的：两个引擎依赖不同（手写版要 `readcodeai.llm.*`，
   Spring AI 版要 `spring.ai.openai.*` 的模型 Bean），调用方自己判断会把"另一个引擎能用"误判成"都不能用"。

### 新增的离线覆盖（CI 重新覆盖默认路径）

切换默认引擎之后，原有 11 个假模型用例走的都是**手写引擎**（它们注入 `ScriptedLlmClient`）。
所以补了 `SpringAiEngineOfflineTest`：用 Spring AI 版的脚本化假模型（`ChatModel` 桩，`@Primary`）
把默认引擎整条链路跑起来，离线验两件事：

- **证据留得住**：工具侧收集 → 结论引用 → 过磁盘核验（`完成：1 轮 · 1 跳 · 证据 1/1 条通过核验`）；
- **预算闸门有效**：模型不肯收尾时被中断并说得出原因
  （`终止：stop=BUDGET_ROUNDS · 轮次 7 · 跳数 1 · 原因：最后一轮模型仍然在调用工具…`）。

离线套件：**206 个测试，0 失败 0 错误，22 跳过**。

### A/B 实测：同一批题、只换引擎（`MultiHopLiveTest`，gson 语料，3 道链式题）

```bash
source notes/llm-env.sh
mvn -B -o test -Dtest=MultiHopLiveTest -Dreadcodeai.verify.repo=C:/Users/HUA/.readcodeai/repos/gson
# 换引擎只改一个环境变量：export READCODEAI_AGENT_ENGINE=handwritten
```

| 指标 | Spring AI 引擎 | 手写引擎 |
|---|---|---|
| 多跳平均召回 | **86.7%** | **86.7%** |
| 单跳（最强）基线 | 24.4% | 24.4% |
| 结论率 | 1/3（1 FINAL · 1 NO_EVIDENCE · 1 证据片段对不上） | 0/3（三次都撞满轮次预算） |
| 平均跳数 · 轮次 | 3.67 · 3.67 | 4.00 · 7.00 |
| 累计 token | 17,580 | 29,269 |
| 平均耗时 | 53.9 s/题 | 29.7 s/题 |

**怎么读这张表（别过度解读）**：
- **召回一样**：两条引擎都把该查的链路查出来了 —— 换引擎没有削弱这个项目的核心能力；
- **两边拒答都多**：这条链路的瓶颈**不是引擎，而是模型什么时候收尾**（与项目早先记录的结论一致）；
- **结论率 1/3 vs 0/3、token 少 40%、耗时高 80%**：样本只有 3 题，且耗时受服务端波动影响，
  **不足以当结论**，只作为观察记录下来。

### 已知遗留（合并前值得再看一眼）

1. `AgentService` 仍保留一个**给测试用的兼容构造器**（9 参），固定走手写引擎；
2. 原生工具调用协议**没有 `thought` 字段**，轨迹里的"推理"退化成模型调工具前顺带输出的一句话
   （已在系统提示词里明确要求它先说一句，但**不能像手写版那样强制**）；
3. 框架的中止语义只到"工具调用次数"，四维里其余三维的原因由 `BudgetToolCallingManager` 自己记；
4. 那次全量离线套件里 `ToolsTest` **偶发红过一次**（证据指向 `src/Real.java` —— 别的测试留下的临时语料，
   属测试基建的顺序脆弱性），重跑即绿；与本次迁移无关，但值得记一笔。

## 第一步（历史）：只加引擎、不切默认

> 下面这一段是切换默认之前的状态，保留下来是为了说明"增量做、可回退"的过程。


## 怎么开

```bash
export JAVA_HOME=/e/Java/JDK21
source notes/llm-env.sh
export READCODEAI_AGENT_ENGINE=spring-ai          # 换成 Spring AI 引擎
mvn -B -o test -Dtest=SpringAiEngineLiveTest      # 真模型验证（无 Key 时自动跳过）
```

不动开关时行为与之前**完全一致**（204 个测试全绿，其中新增的真模型用例在无 Key 时跳过）。

## 改了什么（都在 `agent/` 一层）

| 项 | 文件 | 说明 |
|---|---|---|
| 引擎契约 | `agent/AgentEngine.java` | 新增：手写 `AgentLoop` 与 Spring AI 版实现同一个 `run(...)` |
| 手写引擎 | `agent/AgentLoop.java` | 只加了 `implements AgentEngine` + `@Override`，**一行逻辑没改** |
| Spring AI 引擎 | `agent/springai/SpringAiAgentLoop.java` | 框架跑工具循环；核验/重发/③层在循环外 |
| 工具适配 + 证据收集 | `agent/springai/SpringAiToolAdapter.java`、`SpringAiLoopState.java` | 6 个 `@Tool` 方法代理到原有 `AgentTool`；**证据在这里收集** |
| 四维预算 | `agent/springai/BudgetToolCallingManager.java` | 注册 Bean 覆盖框架默认执行器；预算逻辑仍用现成的 `BudgetGuard` |
| 装配 | `AgentService`、`application.yml`、`pom.xml` | 按配置切引擎；启用即覆盖默认 Bean |

## 实测结果

**离线**：`mvn -o test` → **204 个测试，0 失败 0 错误，22 跳过**（跳过的是需要真模型的用例，含新增那条）。

**真模型**（仓库 = 本项目自身，id 669；问："AgentService.ask 的 question 参数是从哪里传进来的？"）：

```
[SpringAI] 第 1 跳：findCallers(AgentService#ask/5)      → 2 条证据
[SpringAI] 第 2 跳：findDefinition(AgentController#ask)  → 1 条证据
[SpringAI] 第 3 跳：readSymbol(AgentController#ask)      → 1 条证据
[SpringAI] 完成：3 轮 · 3 跳 · 证据 1/1 条通过核验
结论：question 参数是从 AgentController.ask 传进来的（AgentController.java 第 32 行）
证据：[src/main/java/com/readcodeai/api/AgentController.java:30-35]
轮次 3 · 跳数 3 · token in/out 4184/61 · 21.7 秒 · stop=FINAL · 拒答=false
```

**关键验证：证据留得住。** 框架只把工具的**字符串**回灌给模型，结构化证据（文件 + 行号）是在
**工具这一侧**收集的（`SpringAiLoopState.trail`），循环结束后再做磁盘核验。这比手写版更硬一点：
**证据不经模型，模型就没机会编造它**；模型能编的只有"引用了哪几条"，而那几条仍要过 ①② 层。

## ⭐ 最有价值的发现：提示词不是框架能替掉的东西

同一个问题、同一套工具，**只因为我压缩了系统提示词，结果完全不同**：

| | 提示词 | 模型行为 | 结局 |
|---|---|---|---|
| 第 1 次 | 4 条规则（我压缩过，**丢掉了"追这类问题就用 findCallers 一跳一跳往上查"那条**） | 跑了两跳无关的 `findDefinition`；**不读源码**，凭工具输出手打 snippet；给出自相矛盾的结论（"无法找到来源"却附了证据），重发时原样再发一遍 | 证据片段与磁盘对不上 → `EVIDENCE_REJECTED`，拒答 |
| 第 2 次 | 5 条规则（**把那一条补回来**，并明确"把查到的事实讲清楚并给出位置"） | `findCallers → findDefinition → readSymbol`；**读了源码再照抄** | 证据 1/1 过核验，结论正确 |

**结论**：换框架省掉的是"循环 + 协议 + 解析"那 800 多行；但**那些"实测逼出来的提示词规则"必须整条搬过去**——
它们不是样板文字，是这个项目最有价值的知识沉淀。压缩它们等于把调试成果丢掉。

## 合并前必须处理的已知问题

1. **答案缓存键里没有"引擎"维度** —— 两个引擎在同一问题上会互相命中对方的缓存（与当年"缓存键漏了模型名"
   是同一类漏洞）。本机 Redis 没起，所以这次实验没被它影响；**要合并就得把引擎名加进键**。
2. **`readcodeai.agent.engine` 没进 `ReadCodeAiProperties`** —— 现在用 `@Value` 直接读，没走项目的配置校验惯例。
3. **`AgentService` 多了一个兼容构造器** —— 为了这次迁移"零测试改动"（5 个测试文件直接 `new AgentService(...)`）。
   若合并，可以把 6 处调用点改过去、删掉它。
4. **原生工具调用协议里没有 `thought` 字段** —— 手写版的轨迹里每一跳都带模型的推理；
   框架版只能用"模型调工具前顺带输出的文字"近似，多数时候是空的。轨迹的信息量因此少一截。
5. **框架的中止语义只到"工具调用次数"** —— 四维预算里"时长/token/成本"三维要靠自己记
   （`BudgetToolCallingManager.stopDetail()`），框架的异常表达不了。

## 与 `spring-ai-spike` 的关系

`E:\GitHub\spring-ai-spike` 是**独立小工程**，用来先验证"框架能不能替掉循环"（四条验收 + 6 个坑）；
本分支是把结论落到真项目上。小工程里踩到的坑（手写 `ChatClient.builder` 不自动挂 ToolCallingAdvisor、
桩的 `getOptions()` 必须是 `ToolCallingOptions`、工具文本不在 `getText()` 里）在这里**都没再踩**——
因为这次用的是 Boot 自动装配的 Builder，且证据不从 `getText()` 取。

## 顺带记录的构建坑

Maven 本地仓库（`maven-lib`）被两套 settings 写过时，artifact 的 `_remote.repositories` 会记下"从哪个镜像 id 下载的"。
小工程那份 settings 里镜像 id 与全局不同名（`aliyun` vs `alimaven`），导致主项目 `-o` 离线构建直接拒绝解析 BOM。
**两套 settings 的 mirror id 必须同名**，或先联网跑一次让它重新登记。

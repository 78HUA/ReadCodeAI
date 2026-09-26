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

## 简历与技术栈最终怎么改（2026-09-26 已定稿并落地）

> **前提已满足**：Spring AI 已在 `main` 上（`147f1c0`，213 测试 + CI 全绿），默认引擎就是 `spring-ai`，
> 简历描述的就是主干真实的样子。

**第 1 条（最终口径：框架当主角，不提"手写引擎"）**

> 针对链式问题单次检索答不全，用 Spring AI 驱动 ReAct 多跳循环，让模型自主决定调哪个工具、跳几跳、
> 何时收尾，并在循环外自建环检测与四维预算闸门；实测链式题召回从单跳上限 24.4% 提升到 86.7%。

**项目简介**同步把"手写 ReAct 多跳"换成"**Spring AI 驱动的** ReAct 多跳"（顺带补上 `JavaParser`
后面的空格）。

> **为什么砍掉"手写"**：口径已定 —— **引入 Spring AI 就是为了替换掉手写引擎**。
> 手写版**没有废弃**（`readcodeai.agent.engine=handwritten` 一键切回，也是 A/B 对照），
> 但它从此只做**面试追问时的深度弹药**（"我先手写过一轮，所以知道框架省了哪 819 行、
> 四维预算/环检测/证据核验四条一条没省"），不再是简历上的一行。
> 早先的"方案 B（手写与框架两张牌都留）"**作废**。

**技术栈药丸**：加 `Spring AI`，**撤掉 `Vue`**（投后端 / Agent 岗时它最不值钱，MediaVault 已佐证）；
`LLM接入` **保留** —— 它是唯一带 "LLM" 字面词的药丸：

```
SpringBoot · JavaParser · MySQL · Redis · RabbitMQ · ReAct多跳 · Tool Calling · LLM接入 · Spring AI
```

**合并前建议先做**（已完成）：推送分支 → 看 CI 是否绿 → 再合并到 `main`；
本页的 A/B 数字已按项目惯例补进 `docs/verification-log.md`。

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

---

## 第三步：模型层并入 Spring AI（P1–P4）· 2026-09-26

**关键设计：保留 `LlmClient` 这个窄接口，只换实现。**
5 个业务调用点（单跳 / ③层判定 / 代码审查 / 项目总结 / 模块说明）与 11 个测试文件依赖的都是这个接口，
所以换实现不改调用方 —— 协议适配、供应商可换、**降级与"模型可用"状态源统一**这些收益全拿到，
改动面却小一个量级。

| # | 做了什么 | 结果 |
|---|---|---|
| P1 | 新增 `SpringAiLlmClient`（内部走 Spring AI 的 `ChatClient`）；**删掉手写 `OpenAiCompatClient`（87 行）** | 模型协议只剩框架一处；`timeout-seconds` 接到框架的 HTTP 客户端上（不接就成了"配了没用"的死配置） |
| P2 | `model()` 改取 `spring.ai.openai.chat.model` | 修掉**答案缓存键 model 维度可能失真**——缓存按"哪个模型生成的"隔离，与引擎实际用的模型名必须一致 |
| P3 | `AgentLoop.Seeds` → `agent/model/AgentSeeds` | 契约不再挂在某个实现上（手写引擎将来退役时不用动 `AgentEngine` 签名） |
| P4 | **补回 A-B 压缩 + 观察截断**（新增 `PromptCompactionAdvisor`，插在框架循环内层） | 这是迁移时**静默丢掉的能力**：`keep-full-observations` 一度成了死配置，长链会更快烧 token |
| P5 | 顺手给偶发红的 `ToolsTest` 加了自诊断（失败时打印搜的是谁、哪条对不上、为什么） | 那条 flake 还没复现到根因，下次一冒头就能定位 |

### P4 的实测：压缩到底省了多少

**不能拿真模型跑两遍比 token**：跳数会波动（实测 3.67 跳 vs 5.00 跳），两次轨迹根本不是同一条，
token 差多少说明不了压缩省了多少。所以用**假模型 + 固定轨迹**隔离出压缩本身的效果：

| 项 | 数字 |
|---|---|
| 轨迹 | 5 跳，每条观察 2,549 字（与真实 `readSymbol` 同量级） |
| 压缩前发给模型的正文 | **12,756 字** |
| 压缩后（保留近 2 跳原文，更早 3 跳各压成一行事实） | **5,319 字** |
| **省** | **58%** |

**压缩确实是在循环里生效的**（离线断言）：三跳 + 保留近两跳 ⇒ 恰好 1 条被压成一行事实，且循环照常收尾。
真模型那轮的观察：压缩开着时**召回仍是 86.7%**、链路照常跑完；跳数 3.67 → 5.00，
所以两轮 token 总数（17,580 → 27,134）**不能当作压缩的收益或代价，如实记为"不可比"**。

### 这一批的测试

| 项 | 数字 |
|---|---|
| 离线集 | **213 个 · 0 失败 · 0 错误 · 22 跳过** |
| 新增用例 | **6 个**：`SpringAiLlmClientTest` 3 个（内容/token/模型名/空响应）· `PromptCompactionAdvisorTest` 3 个（压缩语义、字数、options 不丢）· `SpringAiEngineOfflineTest` 加 1 个（压缩在循环里生效） |

### 仍未并入 Spring AI 的（按"值不值"排序）

1. **流式输出 / MCP**：框架现成、项目没有 —— 要做得单独排期（流式注意智谱的 `tool_stream` 与型号限制）；
2. **embedding**：仍是手写 HTTP，只服务评估基线 + 状态页、未接入问答路由（收益小）；
3. **多供应商**：配置层面仍只有 OpenAI 兼容一套（框架支持换，但暂时没有第二个供应商的需求）；
4. **观测/监听**：没接（`answer_log` 已经能回答"用了多少、花了多少"）。

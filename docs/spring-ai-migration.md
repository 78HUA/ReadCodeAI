# Spring AI 引擎迁移实验（分支 `feat/spring-ai-engine`）

> **这是一次"最小迁移"的实验记录**：只把**多跳引擎**换成 Spring AI 实现，其余（解析 / 索引 / 检索 /
> 三层核验 / 评估 / 中间件 / REST / 前端）**一行都没动** —— 框架碰不到它们。
> 默认引擎仍是手写版（`readcodeai.agent.engine=handwritten`），所以合不合、什么时候合，都由人决定。

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

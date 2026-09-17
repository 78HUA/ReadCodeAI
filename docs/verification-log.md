# 实测记录

> **本文档只放实测数字**，设计见 `design-outline.md`。
> 每条记录写清：什么时候、用什么命令、结果是什么。
>
> **纪律**：没跑过的数字不许写；失败、异常、没验证到的部分同样要留痕，不许只挑好看的写。

---

## 第 0 步：工程骨架与工具链验证（2026-09-17）

**环境**：Windows 11 · JDK 21.0.12.1 · Maven 3.8.8 · Git Bash
**命令**：`mvn -B test`（前置 `export JAVA_HOME=/e/Java/JDK21`）

### 验证 2：三个组件共存

| 结论 | 证据 |
|---|---|
| Spring Boot **4.1.1** 可用 | 打包成功，实跑 `Started ReadCodeAiApplication in 1.885 seconds` |
| JavaParser **3.28.2** 可用 | 解析 108 个文件（见验证 1） |
| mysql-connector-j **26.7.0** 共存 | 编译、启动均正常（这一步只放驱动、**不引 `spring-boot-starter-jdbc`**，避免还没有数据源配置时触发自动配置而启动失败） |
| 测试合计 | `Tests run: 9, Failures: 0, Errors: 0, Skipped: 0` · `BUILD SUCCESS` · 8.536 s |

**Boot 4 的两条发现（试过才知道，别再走一遍）**：

- Boot 4.x 新增了 `spring-boot-starter-webmvc` 这一层中间 starter：`spring-boot-starter-web` 仍然可用，内嵌容器是经它间接引入的
- **Boot 4.x 已不支持 Undertow** —— BOM 只管理 tomcat / jetty / netty，该 starter 在 Maven Central 上的版本止于 `4.0.0-M1`。**不要尝试更换内嵌容器**

### 验证 1：JavaParser 解析真实工程

**命令**：`mvn -B test -Dtest=ParserToolchainTest`
**样例仓库**：`E:\GitHub\yunshu-nas`（多模块 Maven 工程）

| 指标 | 实测值 |
|---|---|
| Java 文件数 | 108 |
| 代码行数 | 10,032 |
| 语言级别 | `JAVA_21` |
| 解析成功 | **108 / 108** |
| **成功率** | **100.00%**（判据 ≥ 95% ✅） |
| 失败原因分布 | 无失败 |
| 非 UTF-8 文件 | 0 |
| **耗时** | **932 ms** |
| 堆内存增量 | 约 **26 MB**（粗略值，未 GC） |

> ⚠️ **这次 100% 不代表以后都 100%。** 云舒NAS 是纯 UTF-8、标准 Maven 布局的仓库，
> 失败场景要靠别的素材才能暴露（Lombok 生成代码、GBK 编码、非标准目录布局）。
> 第 1 步起在多个仓库上复测，并把失败原因分类记录下来。

### 验证 3：无 LLM Key 时的降级

**命令**：`java -jar target/readcodeai-0.1.0-SNAPSHOT.jar`（**不设任何 `READCODEAI_LLM_*` 环境变量**）

| 检查项 | 结果 |
|---|---|
| 应用启动 | ✅ `Started ReadCodeAiApplication in 1.885 seconds`，Tomcat 监听 8080 |
| 降级日志 | ✅ `未配置 readcodeai.llm.api-key：降级为 Noop —— 静态分析能力不受影响，问答与多跳不可用` |
| 端口探活 | `GET / -> HTTP 404` —— 服务已在监听，只是还没有接口（符合预期） |
| 被调用时的行为 | ✅ 抛 `IllegalStateException` 并说明原因，**不静默返回空文本**（`LlmDegradationTest`） |
| 配置校验 | ✅ 预算参数为 0 或负数时**启动即失败**（`PropertiesValidationTest`，5 条） |
| 产物 | `target/readcodeai-0.1.0-SNAPSHOT.jar`，28.7 MB |

### 尚未验证的部分（诚实留痕）

- [ ] **配上真实 Key 的链路**：最小对话、延迟、token 用量 —— 见 `design-outline.md` 附录 B 待定项 2，第 2 步之前必须补上
- [ ] 在 **Lombok / 非 UTF-8 / 非标准布局**的仓库上复测解析成功率（第 1 步）
- [ ] **十万行级别**的解析耗时与内存拐点（第 1 步，素材用 `E:\Java\JDK21\lib\src.zip`）

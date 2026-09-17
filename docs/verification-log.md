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

---

## 第 1 步：符号表与调用图（2026-09-17）

**命令**：`mvn -B test -Dtest=SymbolIndexIntegrationTest`（前置 `READCODEAI_DB_PASSWORD`）
**样例仓库**：`E:\GitHub\yunshu-nas` @ `e7195f7`

| 指标 | 实测值 |
|---|---|
| 识别到的源码根 | 4 个（多模块 Maven） |
| Java 文件 | 102（**只扫 `src/main/java`**；第 0 步的 108 是连 `.baseline` 脚本一起数） |
| 解析成功 | **102 / 102（100%）** |
| 代码行数 | 9,567 |
| **符号数** | **967** |
| **调用边** | **3,263**，其中解析到仓库内符号 **512（15.69%）** |
| 悬挂边 | **0**（有调用点却没有宿主方法的情况不该出现，为 0 才放心） |
| 耗时 | 解析 927 ms · 解析调用 5,011 ms · 落库 7,540 ms · **合计 17,093 ms** |

**未解析原因分布**：

| 原因 | 条数 | 含义 |
|---|---|---|
| `EXTERNAL` | 1,507 | 确定是仓库外的（框架/JDK），这类本来就该没有边 |
| `UNSOLVED` | 1,204 | 求解器解不出来 |
| `ERROR:IllegalStateException` | 40 | 求解器自身抛异常（例如 `String#join/2` 这种 JDK 方法都解不出来） |

### ⚠️ 15.69% 不是「准确率 15.69%」

分母里混了大量**外部调用**（调 Spring / Jackson / JDK），它们本来就不该连到仓库内符号。
真正该问的是：**仓库内的方法，调用点找全了吗？** 用「同名同参数个数的未解析调用」做了一次粗略探测：

| 仓库内方法 | 解析到的调用点 | 同名未解析调用 |
|---|---|---|
| `XmlWriter#writeElement/3` | 53 | **0** |
| `RestModel#ok/1` | 31 | 1 |
| `XmlWriter#writeProperty/3` | 26 | 1 |
| `ApplicationConfig#getSetting/1` | 18 | 0 |
| `ApplicationConfig#getJdbcTemplate/0` | 12 | 0 |

→ **出现频率最高的那些仓库内方法，几乎没有被漏掉**。常规召回是好的。

### ❗ 一个确凿的漏报案例（待查，第 1 步收尾前必须弄清楚）

`NasRedisConfig#getRedisTemplate/0`（定义在 `NasRedisConfig.java:171`）的 **3 处调用全部未解析**：

```
nasRedisConfig#getRedisTemplate/0        UNSOLVED   ConfigBroadcaster.java:75
nasRedisConfig#getRedisTemplate/0        UNSOLVED   RedisDistributedLock.java:72, 94
nasRedisConfig#enabled/0                 已解析      ConfigBroadcaster.java:65, 67   ← 同一个字段！
```

**同一个接收者字段、同一个类，`enabled()` 能解析而 `getRedisTemplate()` 不能。**
推测是「方法的返回类型或参数类型落在仓库外（`StringRedisTemplate` 不在我们的类路径上），
导致求解器解析整个类型的方法表时失败」—— 但这解释不了为什么 `enabled()` 没事，
所以**这个推测尚未证实，要单独查**。

**可能的修复方向**（按成本排序）：
1. 给类型求解器加上目标仓库的**依赖 jar**（`JarTypeSolver`）—— 需要解析该仓库的 pom 依赖树，工作量中等
2. 解析失败时**回退到按「名字 + 参数个数 + 接收者字段类型」的启发式匹配**，并标注置信度
3. 接受现状并如实说明（但 1204 条 UNSOLVED 里到底藏着多少仓库内调用，必须先量化）

**目前的态度：先量化，再决定要不要修。** 不加区分地"修"会让准确率数字变好看而实际变差。

### 测试素材的取舍（记录一次口径变化）

第 0 步数出 108 个 Java 文件，第 1 步只有 102 个，**不是回归**：
第 0 步的验证脚本扫的是整个仓库目录，第 1 步的索引进程只扫识别出来的源码根（`src/main/java`），
把仓库里 `src/.../.baseline` 下的 5 个独立校验脚本排除在外了 —— 那本来就不是产品代码。

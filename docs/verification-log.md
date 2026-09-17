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

### ✅ 漏报案例：根因已查清（2026-09-17 当日结案）

`NasRedisConfig#getRedisTemplate/0`（定义在 `NasRedisConfig.java:171`）的 **3 处调用全部未解析**，
而同一个接收者字段上的 `enabled/0` 能正常解析。写了一个一次性探针复现，拿到真实的异常栈：

```
--- ConfigBroadcaster.java:65   nasRedisConfig.enabled()
    OK   declaring=...NasRedisConfig  name=enabled  params=0   returnType=boolean
--- ConfigBroadcaster.java:75   nasRedisConfig.getRedisTemplate()
    FAIL com.github.javaparser.resolution.UnsolvedSymbolException: Unsolved symbol : StringRedisTemplate
         at JavaParserMethodDeclaration.getReturnType(JavaParserMethodDeclaration.java:85)
         at MethodUsage.<init>(MethodUsage.java:54)
```

**根因**：JavaParser 解析一个方法调用时，会为候选方法构造 `MethodUsage`，
而 **`MethodUsage` 的构造函数会去求该方法的返回类型**。
`getRedisTemplate()` 返回 `StringRedisTemplate`，这个类**不在我们的类路径上**
（我们只有 `spring-boot-starter-jdbc`，没有 spring-data-redis），于是求值抛异常，
**整个调用点的解析就此失败 —— 哪怕我们需要的信息（哪个类的哪个方法）早就够了**。
`enabled()` 返回 `boolean`（基本类型永远可解），所以没事。

**旁证**：`ApplicationConfig#getJdbcTemplate/0` 有 12 处调用全部解析成功 ——
因为它返回的 `JdbcTemplate` 恰好在我们自己的类路径上（依赖 `spring-boot-starter-jdbc`）。
**同一个机制，两种结果，完全对得上。**

**能救回多少（已量化）**：未解析的调用中，有 **178 / 2751** 条的「名字 + 参数个数」能在仓库内找到对应方法。
但这里面有大量误报（`close/0`、`flush/0`、`setContentType/1` 是外部接口方法，只是名字撞了），
真正属于本机制、能救回的量级大概在 **几十条**（对应约 **+10%~15%** 的仓库内边）。

**决定：现在不修。** 理由：
① 修法（给求解器加目标仓库的依赖 jar / 受限启发式回退）成本不小且有引入**假边**的风险 ——
而对一个主张「证据可核验」的工具，**多一条错边比少一条边更糟**；
② 第 1 步的判据是「与 IDE Find Usages 比对、差异能解释」，而现在**根因已经能解释了**，
这本身就是判据要求的东西；
③ 先让抽查数据说话，再决定值不值得为召回率动手。

**留档的修复方向**（按性价比排序，将来要用直接照做）：
1. **给类型求解器加目标仓库的依赖 jar**（`JarTypeSolver`）—— 治本，但要解析该仓库的 pom 依赖树
2. **受限启发式回退**：解析失败且接收者是「本类字段 + 类型唯一指向仓库内某个类」时，
   按名字 + 参数个数在该类里找唯一匹配，命中则记为 `resolved` 但 `reason='HEURISTIC'`
   （**必须能和精确解析区分开统计**，否则数字会骗人）
3. 接受现状并如实说明（当前选择）

---

## 第 1 步（续）：四类确定性查询

**命令**：`mvn -B test`（15 个测试全绿）

| 查询 | 实测输出（节选） |
|---|---|
| **定位** | `NasRedisConfig#getRedisTemplate/0` → `NasRedisConfig.java:171-176` |
| **谁调用了它** | `NasRedisConfig#enabled/0` → 2 处：`ConfigBroadcaster.java:65`、`RedisDistributedLock.java:67` |
| **它调用了谁** | `ConfigBroadcaster#onLocalChange/1` → 17 条边，每条带 `[已解析]`/`[EXTERNAL]`/`[UNSOLVED]` 标记 |
| **有哪些实现** | `MusicDataSource`（接口）→ 3 个实现类，各带 `文件:起始行-结束行` |

### ⭐ 最值得记的一条：测试里做了「证据核验」

`SymbolQueryTest` 里有一条断言不是普通单元测试的写法：

> 查出来的符号位置，**去磁盘上把那一行读出来，断言内容里真的含有方法名**。

```java
String line = Files.readAllLines(Path.of(rootPath).resolve(symbol.filePath())).get(symbol.startLine() - 1);
assertThat(line).contains("getRedisTemplate");
```

**这才是这个项目的核心主张** —— 库里的行号不是「声称」，是能被程序核验的。
把这条做进测试，意味着以后任何让行号与磁盘脱节的改动都会立刻暴露。

### 尚未完成的部分

- [ ] **调用图准确率抽查**：清单已生成（`notes/accuracy-sampling.md`，10 个方法、含我们记录的每个调用点），
      **待用 IDEA 的 Find Usages 人工核对并填写差异归因** —— 这是第 1 步的正式判据，未完成前第 1 步不算收尾
- [ ] 十万行级别的解析耗时与内存拐点
- [ ] 在 Lombok / 非 UTF-8 / 非标准布局仓库上复测

### 测试素材的取舍（记录一次口径变化）

第 0 步数出 108 个 Java 文件，第 1 步只有 102 个，**不是回归**：
第 0 步的验证脚本扫的是整个仓库目录，第 1 步的索引进程只扫识别出来的源码根（`src/main/java`），
把仓库里 `src/.../.baseline` 下的 5 个独立校验脚本排除在外了 —— 那本来就不是产品代码。

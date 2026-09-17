# 实测记录

> **本文档只放实测数字**，设计见 `design-outline.md`。
> 每条记录写清：什么时候、用什么命令、结果是什么。
>
> **纪律**：没跑过的数字不许写；失败、异常、没验证到的部分同样要留痕，不许只挑好看的写。

---

## 测试语料（自始至终只用中性公开代码）

| 语料 | 规模 | 特点 |
|---|---|---|
| **reggie 外卖**（主素材） | 79 文件 / 4,070 行 | 公开教学代码；Maven 工程；**重度使用 Lombok**；Spring Boot + MyBatis-Plus |
| **Tomcat 自带 examples**（副素材） | 70 文件 / 7,282 行 | **没有 `src/main/java`**，用来测非标准布局的健壮性 |

> ⚠️ **一条硬规则：语料必须是中性的公开代码，不能拿作者自己的其它项目当素材。**
> 两个简历项目互相「分析」会产生很难解释的观感问题（看起来像互相配套、甚至像结果被安排过），
> 而被分析的那个仓库如果本身还是 fork，更容易说不清归属。
> **测试的便利不值得拿项目叙事的干净去换。**
>
> （2026-09-17 纠偏：最初用的是作者自己的另一个项目当语料，已全部替换为上面两个中性语料，
> 并删除了库里的旧索引。本轮所有数字均为替换后重跑所得。）

**两套语料的数字不可直接比较**：规模、技术栈、代码风格都不同。
它们的价值在于**互相暴露对方测不出的问题**（见第 1 步「换语料暴露的两个真实问题」）。

---

## 第 0 步：工程骨架与工具链验证（2026-09-17）

**环境**：Windows 11 · JDK 21.0.12.1 · Maven 3.8.8 · Git Bash
**命令**：`mvn -B test`（前置 `export JAVA_HOME=/e/Java/JDK21`）

### 验证 2：三个组件共存

| 结论 | 证据 |
|---|---|
| Spring Boot **4.1.1** 可用 | 打包成功，实跑 `Started ReadCodeAiApplication in 1.885 seconds` |
| JavaParser **3.28.2** 可用 | 两个语料均 100% 解析（见验证 1） |
| mysql-connector-j **26.7.0** 共存 | 编译、启动均正常 |
| 测试合计 | `Tests run: 15, Failures: 0, Errors: 0, Skipped: 0` · `BUILD SUCCESS` |

**Boot 4 的两条发现（试过才知道，别再走一遍）**：

- Boot 4.x 新增了 `spring-boot-starter-webmvc` 这一层中间 starter：`spring-boot-starter-web` 仍然可用，内嵌容器是经它间接引入的
- **Boot 4.x 已不支持 Undertow** —— BOM 只管理 tomcat / jetty / netty，该 starter 在 Maven Central 上的版本止于 `4.0.0-M1`。**不要尝试更换内嵌容器**

**JavaParser 3.28 的两处 API 差异（编译期就撞上，已记进代码注释）**：

- `ParserConfiguration.setSymbolSolver` 在这个版本叫 **`setSymbolResolver`**
- `NodeWithModifiers` 在 `ast.nodeTypes` 包下，**不在** `ast.nodeTypes.modifiers`

### 验证 1：JavaParser 解析真实工程

**命令**：`mvn -B test -Dtest=ParserToolchainTest`（可用 `-Dreadcodeai.verify.repo=<路径>` 换语料）

| 指标 | reggie 外卖（主） | Tomcat examples（副） |
|---|---|---|
| Java 文件数 | 79 | 70 |
| 代码行数 | 4,070 | 7,282 |
| **解析成功率** | **79 / 79（100%）** | **70 / 70（100%）** |
| 失败原因分布 | 无失败 | 无失败 |
| 非 UTF-8 文件 | 0 | 0 |
| 耗时 | **529 ms** | **647 ms** |
| 堆内存增量 | 约 22 MB（粗略值，未 GC） | — |

> ⚠️ **连续 100% 不代表以后都 100%。** 失败场景要靠更杂的语料才能暴露：
> GBK 编码、生成代码、旧版本语法。**换语料暴露问题的实例见第 1 步。**

### 验证 3：无 LLM Key 时的降级

**命令**：`java -jar target/readcodeai-0.1.0-SNAPSHOT.jar`（**不设任何 `READCODEAI_LLM_*` 环境变量**）

| 检查项 | 结果 |
|---|---|
| 应用启动 | ✅ `Started ReadCodeAiApplication`，Tomcat 监听 8080 |
| 降级日志 | ✅ `未配置 readcodeai.llm.api-key：降级为 Noop —— 静态分析能力不受影响，问答与多跳不可用` |
| 端口探活 | `GET / -> HTTP 404`（服务已监听，只是还没有接口，符合预期） |
| 被调用时的行为 | ✅ 抛 `IllegalStateException` 并说明原因，**不静默返回空文本**（`LlmDegradationTest`） |
| 配置校验 | ✅ 预算参数为 0 或负数时**启动即失败**（`PropertiesValidationTest`） |
| 产物 | `target/readcodeai-0.1.0-SNAPSHOT.jar`，约 28.7 MB |

### 尚未验证的部分（诚实留痕）

- [ ] **配上真实 Key 的链路**：最小对话、延迟、token 用量 —— 见 `design-outline.md` 附录 B 待定项 2，第 2 步之前必须补上
- [ ] **十万行级别**的解析耗时与内存拐点（素材用 `E:\Java\JDK21\lib\src.zip`，5,108,008 行）
- [ ] **非 UTF-8 编码**的仓库（现有两套语料都是 UTF-8）

---

## 第 1 步：符号表与调用图（2026-09-17）

**命令**：`mvn -B test -Dtest=SymbolIndexIntegrationTest`（前置 `READCODEAI_DB_PASSWORD`）

| 指标 | reggie 外卖（主） | Tomcat examples（副） |
|---|---|---|
| 识别到的源码根 | 1 个（`src/main/java`） | 1 个（**无 `src/main/java`，回退到仓库根**） |
| Java 文件 / 解析成功 | 79 / 79（100%） | 70 / 70（100%） |
| 代码行数 | 4,070 | 7,282 |
| **符号数** | **473** | **650** |
| **调用边** | **823**，解析到仓库内符号 **151（18.35%）** | **1,346**，解析到仓库内符号 **127（9.44%）** |
| 悬挂边 | **0** | **0** |
| 耗时 | 解析 464 ms · 解析调用 1,211 ms · 落库 2,485 ms · **合计 4,445 ms** | 解析 605 ms · 解析调用 1,035 ms · 落库 3,637 ms · **合计 5,558 ms** |

**未解析原因分布**：

| 原因 | reggie | examples | 含义 |
|---|---|---|---|
| `EXTERNAL` | 131 | 653 | 确定是仓库外的（框架/JDK），这类本来就不该有边 |
| `UNSOLVED` | 540 | 566 | 求解器解不出来 |
| `MISSING_CLASS` | 1 | 0 | 反射解析时撞上类路径里缺失的类（见下） |
| `ERROR:IllegalStateException` | 0 | 0 | — |

### ⚠️ 18.35% / 9.44% 都不是「准确率」

分母里混了大量**外部调用**（调 Spring / MyBatis-Plus / JDK），它们本来就不该连到仓库内符号。
真正该问的是：**仓库内的方法，调用点找全了吗？** 这个问题由**人工抽查**回答
（见文末「尚未完成的部分」）。当前能做的量化是「同名同参数个数的未解析调用」，
但它**误报很多**（`save/1`、`close/0` 这类名字在外部接口上大量存在），只能当上界看。

### ❗ 换语料暴露的两个真实问题（本轮最大收获）

**问题 1：`NoClassDefFoundError` 直接中断整次索引 —— 健壮性 bug，已修**

```
java.lang.NoClassDefFoundError: com/fasterxml/jackson/core/util/DefaultPrettyPrinter$Indenter
    at ReflectionClassDeclaration.solveMethod(...)        ← JavaParser 用反射解析类路径上的类
    at MethodCallExpr.resolve(...)
    at SourceAnalyzer.resolveCall(SourceAnalyzer.java:269) ← 我们的代码
```

reggie 用 Jackson，JavaParser 反射解析它时撞上**类路径里缺失的类** → 抛 `NoClassDefFoundError`。
**它是 `Error` 不是 `Exception`**，原来的 catch 覆盖不到，**整次索引直接失败**
（reggie 的第一次索引就是失败的，仓库行留在 `INDEXING` 状态没有落成 `FAILED`）。

修法：在调用点级别接住 `LinkageError` 并归类为 `MISSING_CLASS`；同时在索引编排层也接住，
保证任何意外都能把仓库行标成 `FAILED` 而不是卡在 `INDEXING`。修完后 reggie 里此类只剩 1 条。

**启示：一个语料测不出健壮性。** 旧语料从没触发过这个分支 —— 这就是多语料的价值。

**问题 2：方法的起始行会包含注解行 —— 我对行号语义的假设错了**

测试断言「符号的起始行应包含符号名」，在 reggie 上失败：

```
RiderController#save/1 记录的起始行 86 应包含符号名，实际内容：@PostMapping
```

带注解的方法，区间起点是注解行（`@PostMapping`）。**这是对的** —— 注解本就是声明的一部分。
正确的不变式是「**记录的行区间内包含符号名**」，不是「起始行包含符号名」。

→ **给第 8 步前端的提醒**：证据卡片点开 `文件:行号` 时，可能先看到的是注解行，不是方法签名。

### 漏报的根因：已复现、结论明确（用新语料复现）

reggie 上的实例：

```
EmployeeService.java:12       R<Employee> login(HttpServletRequest request, Employee employee);
EmployeeController.java:34    return employeeService.login(request, employee);   ← 我们记成 UNSOLVED
```

**方法确实在源码里，调用点也确实存在，但我们没连上边。**

**根因**（此前的探针打出了真实异常栈）：JavaParser 解析一个方法调用时，会为候选方法构造
`MethodUsage`，而 **`MethodUsage` 的构造函数会去求该方法的返回类型与参数类型**。
`HttpServletRequest`、`StringRedisTemplate` 这类**不在我们类路径上的类型**求值即抛
`UnsolvedSymbolException`，**整个调用点的解析就此失败 —— 哪怕我们需要的信息（哪个类的哪个方法）早就够了**。

**旁证（同一机制，两种结果）**：`ApplicationConfig#getJdbcTemplate/0` 的 12 处调用全部解析成功 ——
因为它返回的 `JdbcTemplate` 恰好在我们自己的类路径上（依赖 `spring-boot-starter-jdbc`）。

**决定：现在不修。** 理由：
1. 修法（给求解器加目标仓库的依赖 jar / 受限启发式回退）成本不小，且**有引入假边的风险** ——
   对一个主张「证据可核验」的工具，**多一条错边比少一条边更糟**；
2. 第 1 步的判据是「与 IDE Find Usages 比对、差异能解释」，而**根因已经能解释了**，
   这本身就是判据要求的东西；
3. 先让抽查数据说话，再决定值不值得为召回率动手。

**留档的修复方向**（按性价比排序，将来要用直接照做）：

1. **给类型求解器加目标仓库的依赖 jar**（`JarTypeSolver`）—— 治本，但要解析该仓库的 pom 依赖树
2. **受限启发式回退**：解析失败、且接收者能确定为「本类字段 + 类型唯一指向仓库内某个类」时，
   按名字 + 参数个数找唯一匹配，命中则记为 `resolved` 但 `reason='HEURISTIC'`
   （**必须能和精确解析分开统计**，否则数字会骗人）
3. 接受现状并如实说明（当前选择）

---

## 第 1 步（续）：四类确定性查询

**命令**：`mvn -B test`（15 个测试全绿）

| 查询 | 实测输出（reggie 上节选） |
|---|---|
| **定位** | `R#success/1` → `src/main/java/com/harmony/reggie/common/R.java:22-27` |
| **谁调用了它** | `R#error/1` → 30 处调用点，含 `GlobalExceptionHandler.java:31,33,44` 等 |
| **它调用了谁** | 每条边带 `[已解析]` / `[EXTERNAL]` / `[UNSOLVED]` 标记，未解析的保留原文 |
| **有哪些实现** | `AddressBookService` 等 5 个接口 → 各 1 个实现类，均带 `文件:起始行-结束行` |

### ⭐ 最值得记的一条：证据核验做进了测试

`SymbolQueryTest` 里有两条断言不是普通单元测试的写法：

1. 查出来的符号位置，**去磁盘上把那段行区间读出来，断言里面真的含有符号名**；
2. 查出来的**每一处调用点行号，也去磁盘上读那一行，断言里面真的含有被调用的方法名**。

```
[调用点核验] 逐条回磁盘核对了 10 处调用点的行号与内容
```

**这才是这个项目的核心主张** —— 库里的行号不是「声称」，是能被程序核验的。
把它做成测试，意味着以后任何让行号与磁盘脱节的改动都会立刻暴露。

### ⭐⭐ 调用图准确率人工抽查（第 1 步的正式判据 · 2026-09-17 完成）

**方法**：用 IDEA 的 Find Usages 作人工基准，抽查 10 个方法（避开 getter/setter）。
**执行人是项目作者本人** —— 工具的作者不能给自己的准确率下结论。
逐条比对调用点数量与位置，见 `notes/accuracy-sampling.md`。

| # | 方法 | 我们的记录 | IDEA 实测 | 结论 |
|---|---|---|---|---|
| 1 | `R#success/1` | 51 | **62** | ⚠️ 差 11 处 —— **已查清根因，见下** |
| 2 | `R#error/1` | 30 | 30 | ✅ 一致 |
| 3 | `OrdersService#acceptOrder/1` | 2 | 2 | ✅ 一致（位置也对得上） |
| 4 | `OrdersService#completeOrder/1` | 2 | 2 | ✅ 一致 |
| 5 | `RiderController#save/1` | 1 | 1 | ✅ 一致 |
| 6 | `LoginCheckFilter#check/2` | 1 | 1 | ✅ 一致 |
| 7 | `AddressBookService#deleteAddressBook/1` | 1 | 1 | ✅ 一致 |
| 8 | `AddressBookService#updateAddressBook/1` | 1 | 1 | ✅ 一致 |
| 9 | `AddressBookService#selectAddressBookList/1` | 1 | 1 | ✅ 一致 |
| 10 | `CategoryService#saveSortInfo/1` | 1 | 1 | ✅ 一致 |

> **结论：9 / 10 完全一致；唯一差异查到了根因；假边 0 处。**

#### 那 11 处差距是什么 —— **不是漏采集，是没连上**

```
我们的库：已连边 51 ＋ 未连边 11 ＝ 62 个调用点
IDEA    ：                          62 个调用点     ← 完全一致
```

**调用点的采集一处不漏（62 = 62）**；差的是「把调用点连到目标符号」这一步（51/62 = 82.3%）。
而且**未连上的 11 处没有消失** —— 它们在库里带着 `reason='UNSOLVED'` 完整保留。
这正是「未解析的调用必须保留原文与原因」这条设计决策的价值：
**没有它，这里就只能得出「工具漏了 11 处」这个错误结论。**

#### 根因（逐条核对过源码，11 处无一例外）

`R.success(T object)` 是**泛型方法** —— JavaParser 必须求出**实参类型**才能推断类型参数 `T`。
而这 11 处的实参类型**全部牵连到不在类路径上的外部类**：

| 实参写法 | 实参类型 | 结果 |
|---|---|---|
| `R.success(pageInfo)`（7 处） | `Page<Rider>` / `Page<Orders>` —— MyBatis-Plus 外部类 | ❌ 解析失败 |
| `R.success(addressBookMapper.selectList(qw))` | mapper 是 `BaseMapper` 派生接口，泛型牵连外部类型 | ❌ |
| `R.success(ordersService.pageOrders(...))` | 返回 `Page<Orders>` | ❌ |
| `R.success(dishDtoPage)` / `pageDto` / `mealDtoPage` / `stats` | 同上家族 | ❌ |
| **对照**：`R.success(addressBook)` | `AddressBook`（仓库内类型） | ✅ 解析成功 |
| **对照**：`R.success("注册成功")` | `String`（JDK 类型） | ✅ 解析成功 |

**与上一节那个漏报案例是同一个病根**：JavaParser 解析调用时要做类型推断/匹配，
一旦牵涉它解不开的类型（**返回类型或实参类型**），整个调用点就失败 ——
哪怕「这个调用打到了哪个方法」本身并不需要那个类型。

修复方向与前面留档一致：给类型求解器补上目标仓库的**依赖 jar**（治本），
或对泛型方法的实参做**容错推断**（解不开就当 `Object`，只用于选方法、不用于判定边的正确性）。

#### 抽查还暴露了生成器的一处瑕疵

清单里「我们找到 **51** 个调用点」那行有多余换行，是生成脚本的 SQL 输出带尾换行所致。
不影响阅读，下次生成清单时修掉。

### 尚未完成的部分

- [ ] 十万行级别的解析耗时与内存拐点（素材用 `E:\Java\JDK21\lib\src.zip`）
- [ ] 非 UTF-8 编码仓库的解析表现
- [ ] 中规模语料（3–6 万行）—— 计划用第 3 步的远程拉取功能下载

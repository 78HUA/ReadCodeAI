package com.readcodeai.eval;

import com.readcodeai.eval.model.GeneratedQuestion;
import com.readcodeai.eval.model.QType;
import com.readcodeai.index.store.SymbolQueryRepository;
import com.readcodeai.retrieve.QueryRouter;
import com.readcodeai.retrieve.SymbolLookups;
import com.readcodeai.retrieve.SymbolQueryService;
import com.readcodeai.retrieve.model.CallSiteView;
import com.readcodeai.retrieve.model.SymbolView;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * 自动出题：**从索引里随机取样，标准答案由静态分析算出**。
 *
 * <p>这是本项目最大的差异化所在。同类项目的评测集往往只有个位数条目、还要人工标注；
 * 而代码领域很多问题的答案**本来就能算出来** —— 于是可以：
 * ① 脚本批量出题（规模到几百上千条）；② 自动判卷；
 * ③ **答案不来自 LLM**，所以不存在「用模型判模型」的循环依赖。
 *
 * <p><b>可复现</b>：固定 seed → 同一批题。生成规则一改就要升 {@code GENERATOR_VERSION}，
 * 否则新旧题不可比。
 */
@Component
public class QuestionGenerator {

    /** 生成规则一变就升版本 —— 否则跨版本的命中率没有可比性。v2 = 加了「题面必须能被解析」与裸名字唯一两道判据。 */
    public static final String GENERATOR_VERSION = "v2";

    private final SymbolQueryRepository repository;
    private final QueryRouter queryRouter;
    private final SymbolQueryService queryService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public QuestionGenerator(SymbolQueryRepository repository, QueryRouter queryRouter,
                             SymbolQueryService queryService) {
        this.repository = repository;
        this.queryRouter = queryRouter;
        this.queryService = queryService;
    }

    /**
     * @param perType 每种题型生成多少道（总数 = perType × 题型数）
     */
    public List<GeneratedQuestion> generate(long repoId, long seed, int perType) {
        Random random = new Random(seed);
        List<SymbolView> methods = repository.mostCalledMethods(repoId, 2000);
        List<SymbolView> interfaces = repository.mostImplementedInterfaces(repoId, 500);

        List<GeneratedQuestion> questions = new ArrayList<>();
        questions.addAll(locate(repoId, random, methods, perType));
        questions.addAll(callers(repoId, random, methods, perType));
        questions.addAll(callees(repoId, random, methods, perType));
        questions.addAll(structure(repoId, random, methods, perType));
        questions.addAll(implementsOf(repoId, random, interfaces, perType));
        return questions;
    }

    // ------------------------------------------------------------------ 各题型

    private List<GeneratedQuestion> locate(long repoId, Random random, List<SymbolView> methods, int count) {
        List<GeneratedQuestion> out = new ArrayList<>();
        for (SymbolView method : shuffled(random, methods)) {
            if (out.size() >= count) {
                break;
            }
            // **裸名字必须唯一**：LOCATE 题的题面里只有方法名（"read 定义在哪？"）——
            // 这正是真实用户的问法，所以不能靠"给题面加类名前缀"绕开歧义，
            // 只能要求这个名字在索引里只有**一个**符号。实测踩到过：项目里多个类都有 of()，
            // 题面「of 定义在哪？」本身就没有唯一答案，工具答错却被算成它命中率下降。
            if (!uniquelyNamed(repoId, method.name())) {
                continue;
            }
            String questionText = method.name() + " 定义在哪？";
            if (!resolvable(repoId, questionText)) {
                continue;
            }
            String key = key(method.filePath(), method.startLine());
            out.add(new GeneratedQuestion(QType.LOCATE, questionText,
                    payload(repoId, method.qualifiedName()),
                    List.of(key),
                    json(Map.of("keys", List.of(key), "qualifiedName", method.qualifiedName()))));
        }
        return out;
    }

    private List<GeneratedQuestion> callers(long repoId, Random random, List<SymbolView> methods, int count) {
        List<GeneratedQuestion> out = new ArrayList<>();
        int examined = 0;
        for (SymbolView method : shuffled(random, methods)) {
            if (out.size() >= count || examined++ > methods.size()) {
                break;
            }
            // **歧义题不生成**：同名重载（参数个数不同）或同名类会让问题本身没有唯一答案 ——
            // 那种题连人都答不准，拿它测工具是自欺（评估集实测暴露过这一点）
            if (!uniquelyIdentifiable(repoId, method)) {
                continue;
            }
            String questionText = "谁调用了 " + ownerPrefix(method) + method.name() + " 方法？";
            if (!resolvable(repoId, questionText)) {
                continue;
            }
            List<CallSiteView> callers = repository.callers(method.id());
            if (callers.isEmpty()) {
                continue;
            }
            List<String> keys = callers.stream()
                    .map(call -> key(call.callSiteFile(), call.callLine()))
                    .distinct().sorted().toList();
            out.add(new GeneratedQuestion(QType.CALLERS, questionText,
                    payload(repoId, method.qualifiedName()),
                    keys,
                    json(Map.of("keys", keys, "qualifiedName", method.qualifiedName()))));
        }
        return out;
    }

    private List<GeneratedQuestion> callees(long repoId, Random random, List<SymbolView> methods, int count) {
        List<GeneratedQuestion> out = new ArrayList<>();
        for (SymbolView method : shuffled(random, methods)) {
            if (out.size() >= count) {
                break;
            }
            if (!uniquelyIdentifiable(repoId, method)) {
                continue;
            }
            String questionText = ownerPrefix(method) + method.name() + " 调用了哪些方法？";
            if (!resolvable(repoId, questionText)) {
                continue;
            }
            List<CallSiteView> callees = repository.callees(method.id()).stream()
                    .filter(CallSiteView::resolved)
                    .toList();
            if (callees.isEmpty()) {
                continue;
            }
            // 真值取「被调用者的定义位置」：与答案里引用的证据口径一致
            List<String> keys = new ArrayList<>();
            for (CallSiteView call : callees) {
                SymbolView target = repository.findSymbolById(call.symbolId());
                if (target != null) {
                    keys.add(key(target.filePath(), target.startLine()));
                }
            }
            keys = keys.stream().distinct().sorted().toList();
            if (keys.isEmpty()) {
                continue;
            }
            out.add(new GeneratedQuestion(QType.CALLEES,
                    questionText,
                    payload(repoId, method.qualifiedName()),
                    keys,
                    json(Map.of("keys", keys, "qualifiedName", method.qualifiedName()))));
        }
        return out;
    }

    private List<GeneratedQuestion> structure(long repoId, Random random, List<SymbolView> methods, int count) {
        List<GeneratedQuestion> out = new ArrayList<>();
        for (SymbolView method : shuffled(random, methods)) {
            if (out.size() >= count) {
                break;
            }
            SymbolView type = method.parentId() == null ? null : repository.findSymbolById(method.parentId());
            if (type == null) {
                continue;
            }
            // **同名类型也不出题**：项目的判据是"歧义题不出"（对方法做了，这里对类型补齐）。
            // 实测踩到过：`Material` 有两个（ReviewReport 与 ProjectMaterialBuilder 各一个），
            // 题目只写简单名 → 解析到哪一个都是合法的，但真值只算了一个 → 那题必然算错。
            if (sameNamedTypeCount(repoId, type.name()) != 1) {
                continue;
            }
            String questionText = type.name() + " 有哪些成员？";
            if (!resolvable(repoId, questionText)) {
                continue;
            }
            List<SymbolView> members = repository.children(type.id());
            if (members.isEmpty()) {
                continue;
            }
            List<String> keys = members.stream()
                    .map(member -> key(member.filePath(), member.startLine()))
                    .distinct().sorted().toList();
            out.add(new GeneratedQuestion(QType.STRUCTURE,
                    questionText,
                    payload(repoId, type.qualifiedName()),
                    keys,
                    json(Map.of("keys", keys, "qualifiedName", type.qualifiedName()))));
        }
        return out;
    }

    private List<GeneratedQuestion> implementsOf(long repoId, Random random, List<SymbolView> interfaces, int count) {
        List<GeneratedQuestion> out = new ArrayList<>();
        for (SymbolView itf : sample(random, interfaces, count)) {
            List<SymbolView> implementations = repository.directImplementations(itf.id());
            if (implementations.isEmpty()) {
                continue;
            }
            String questionText = itf.name() + " 有哪些实现类？";
            if (!resolvable(repoId, questionText)) {
                continue;
            }
            List<String> keys = implementations.stream()
                    .map(impl -> key(impl.filePath(), impl.startLine()))
                    .distinct().sorted().toList();
            out.add(new GeneratedQuestion(QType.IMPLEMENTS,
                    questionText,
                    payload(repoId, itf.qualifiedName()),
                    keys,
                    json(Map.of("keys", keys, "qualifiedName", itf.qualifiedName()))));
        }
        return out;
    }

    // ------------------------------------------------------------------ 工具

    /** 判卷用的规范位置键。**全项目统一这一种格式**，判卷就是集合比较。 */
    public static String key(String filePath, int line) {
        return filePath + ":" + line;
    }

    /**
     * 出题时带上归属类名：**裸方法名往往不唯一**（gson 里一堆 {@code read}），
     * 只写方法名连人都答不准，更别说工具。真实使用者也会说"XxxService 的 read 方法"。
     */
    private String ownerPrefix(SymbolView method) {
        if (method.parentId() == null) {
            return "";
        }
        SymbolView owner = repository.findSymbolById(method.parentId());
        return owner == null ? "" : owner.name() + " 的 ";
    }

    /**
     * 这个方法能不能被"归属类名 + 方法名"唯一确定？不能就不能出题。
     *
     * <p>两种歧义都实测踩过：**同名重载**（参数个数不同）和**同名类**（不同包）。
     * 歧义题的正确答案本来就不唯一，拿它测工具只会得到假的低分。
     */
    private boolean uniquelyIdentifiable(long repoId, SymbolView method) {
        if (method.parentId() == null) {
            return false;
        }
        SymbolView owner = repository.findSymbolById(method.parentId());
        if (owner == null) {
            return false;
        }
        if (sameNamedTypeCount(repoId, owner.name()) != 1) {
            return false;
        }
        return repository.findMembersInType(repoId, owner.qualifiedName(), method.name()).size() == 1;
    }

    /**
     * 这个名字在索引里是不是**唯一**的符号（不分种类）？
     *
     * <p>LOCATE 题的题面里只有裸名字，所以"唯一"是它可答的前提：
     * 只要还有一个同名的类或方法，题面「X 定义在哪？」就有多个合法答案。
     */
    private boolean uniquelyNamed(long repoId, String name) {
        return repository.findSymbols(repoId, name, 50).stream()
                .filter(symbol -> symbol.name().equals(name))
                .count() == 1;
    }

    /**
     * 题面能不能被**确定性路由**解析到符号？解析不到就不能出题。
     *
     * <p>实测踩到：方法名恰好是英文停用词（{@code of}、{@code is}…）时，路由会把它当普通词过滤掉，
     * 题面于是解析不到任何符号 → 题目退到语义检索（离线集里直接报"未配置 LLM"）。
     * 这不是路由的缺陷（{@code of} 本来就不该被当标识符），而是**这个题面没法问** ——
     * 出题时用路由自己验一遍，是唯一不会随路由演化而失真的判据。
     *
     * <p>它比"解析到题目指向的那个符号"宽：路由解析到别的符号也放行 ——
     * 那种题面是可答的，答错了该暴露成命中率下降，而不是被悄悄剔掉。
     */
    private boolean resolvable(long repoId, String questionText) {
        return !queryRouter.route(repoId, questionText, SymbolLookups.of(queryService, repoId))
                .targets().isEmpty();
    }

    /** 这个名字在索引里有几个**类型**（类/接口/枚举/记录）。>1 说明题目没有唯一答案。 */
    private long sameNamedTypeCount(long repoId, String name) {
        return repository.findSymbols(repoId, name, 50).stream()
                .filter(symbol -> symbol.name().equals(name))
                .filter(symbol -> isType(symbol.kind()))
                .count();
    }

    private static boolean isType(String kind) {
        return switch (kind) {
            case "CLASS", "INTERFACE", "ENUM", "RECORD", "ANNOTATION" -> true;
            default -> false;
        };
    }

    private static <T> List<T> sample(Random random, List<T> source, int count) {
        if (source.isEmpty()) {
            return List.of();
        }
        List<T> pool = new ArrayList<>(source);
        java.util.Collections.shuffle(pool, random);
        return pool.subList(0, Math.min(count, pool.size()));
    }

    private static <T> List<T> shuffled(Random random, List<T> source) {
        List<T> pool = new ArrayList<>(source);
        java.util.Collections.shuffle(pool, random);
        return pool;
    }

    private String payload(long repoId, String qualifiedName) {
        return json(Map.of("repoId", repoId, "target", qualifiedName));
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("序列化题目失败", e);
        }
    }
}

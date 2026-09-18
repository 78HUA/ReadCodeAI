package com.readcodeai.eval;

import com.readcodeai.eval.model.GeneratedQuestion;
import com.readcodeai.eval.model.QType;
import com.readcodeai.index.store.SymbolQueryRepository;
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

    /** 生成规则一变就升版本 —— 否则跨版本的命中率没有可比性。 */
    public static final String GENERATOR_VERSION = "v1";

    private final SymbolQueryRepository repository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public QuestionGenerator(SymbolQueryRepository repository) {
        this.repository = repository;
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
        for (SymbolView method : sample(random, methods, count)) {
            String key = key(method.filePath(), method.startLine());
            out.add(new GeneratedQuestion(QType.LOCATE,
                    method.name() + " 定义在哪？",
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
            List<CallSiteView> callers = repository.callers(method.id());
            if (callers.isEmpty()) {
                continue;
            }
            List<String> keys = callers.stream()
                    .map(call -> key(call.callSiteFile(), call.callLine()))
                    .distinct().sorted().toList();
            out.add(new GeneratedQuestion(QType.CALLERS,
                    "谁调用了 " + ownerPrefix(method) + method.name() + " 方法？",
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
                    ownerPrefix(method) + method.name() + " 调用了哪些方法？",
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
            List<SymbolView> members = repository.children(type.id());
            if (members.isEmpty()) {
                continue;
            }
            List<String> keys = members.stream()
                    .map(member -> key(member.filePath(), member.startLine()))
                    .distinct().sorted().toList();
            out.add(new GeneratedQuestion(QType.STRUCTURE,
                    type.name() + " 有哪些成员？",
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
            List<String> keys = implementations.stream()
                    .map(impl -> key(impl.filePath(), impl.startLine()))
                    .distinct().sorted().toList();
            out.add(new GeneratedQuestion(QType.IMPLEMENTS,
                    itf.name() + " 有哪些实现类？",
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
        long sameNamedTypes = repository.findSymbols(repoId, owner.name(), 50).stream()
                .filter(symbol -> symbol.name().equals(owner.name()))
                .filter(symbol -> isType(symbol.kind()))
                .count();
        if (sameNamedTypes != 1) {
            return false;
        }
        return repository.findMembersInType(repoId, owner.qualifiedName(), method.name()).size() == 1;
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

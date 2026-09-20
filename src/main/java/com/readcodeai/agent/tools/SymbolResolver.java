package com.readcodeai.agent.tools;

import com.readcodeai.retrieve.NotFoundException;
import com.readcodeai.retrieve.SymbolQueryService;
import com.readcodeai.retrieve.model.SymbolKinds;
import com.readcodeai.retrieve.model.SymbolView;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 把模型给的名字换成真实符号。**歧义时不猜** —— 这是这个类存在的全部理由。
 *
 * <p>为什么必须如此：裸方法名在真实仓库里普遍不唯一（gson 里一堆 {@code read}）。
 * 挑"第一个同名的"就是猜；猜错的代价是整条链指向了另一个方法，
 * 而模型自己不会察觉。所以这里宁可返回"歧义 + 候选清单"，让模型下一轮把范围限定死 ——
 * **这正好是 Agent 该干的活**：澄清歧义靠的是模型，不是我们的启发式。
 *
 * <p>支持的写法（都是实测里模型真会用出来的）：
 * <ul>
 *   <li>{@code com.a.B#m/1} —— 索引里的全限定名（含重载编号）</li>
 *   <li>{@code com.a.B} —— 类型全限定名</li>
 *   <li>{@code B.m} / {@code B#m} —— 简单类名 + 成员</li>
 *   <li>{@code m} —— 裸名字（唯一时可用，不唯一时给候选）</li>
 * </ul>
 */
public final class SymbolResolver {

    /** 一次取够候选：{@code read} 这种名字在真实仓库里有十几个重载，取少了会把目标截断掉。 */
    private static final int LOOKUP_LIMIT = 200;

    private static final int MAX_CANDIDATES = 6;

    /** 成员引用：{@code name} 或 {@code name/2}（后者的 2 是参数个数，用来挑重载）；{@code <init>} 是构造器的真名。 */
    private static final Pattern MEMBER_REF =
            Pattern.compile("^(<init>|[A-Za-z_$][A-Za-z0-9_$]*)(?:/(\\d+))?$");

    /**
     * @param symbols    解析成功的符号（同名重载会一并带上 —— 问"谁调用了 read"时，人指的是全部重载）
     * @param candidates 歧义时的候选（没解析成功才有）
     * @param note       给模型看的一句话：解析到了什么，或者为什么需要它限定范围
     */
    public record Resolution(List<SymbolView> symbols, List<SymbolView> candidates, String note) {

        public static Resolution none(String note) {
            return new Resolution(List.of(), List.of(), note);
        }

        public boolean found() {
            return !symbols.isEmpty();
        }
    }

    private SymbolResolver() {
    }

    public static Resolution resolve(SymbolQueryService queries, long repoId, String raw) {
        String name = clean(raw);
        Resolution resolved = resolveOnce(queries, repoId, name);
        // 嵌套类型有两种写法：源码里是 Outer.Inner，JVM/反射里是 Outer$Inner —— 模型两种都会写。
        // 不认 $ 的话，凡是涉及嵌套类型的查询都会落空（实测撞到过：模型自己把 . 换成了 $，
        // 那个符号随后就"查不到"了，而工具其实有能力回答）。
        if (!resolved.found() && name.indexOf('$') > 0) {
            Resolution rewritten = resolveOnce(queries, repoId, name.replace('$', '.'));
            if (rewritten.found()) {
                return rewritten;
            }
        }
        return resolved;
    }

    private static Resolution resolveOnce(SymbolQueryService queries, long repoId, String name) {
        if (name.isEmpty()) {
            return Resolution.none("符号名是空的");
        }
        List<SymbolView> pool = lookup(queries, repoId, name);

        // ① 全限定名整体命中 —— 最不含歧义的写法，优先用
        List<SymbolView> exact = pool.stream()
                .filter(symbol -> symbol.qualifiedName().equals(name)).toList();
        if (exact.size() == 1) {
            return withOverloads(queries, repoId, exact.get(0));
        }

        // ② 限定形式：类型 + 成员
        String typePart = null;
        String memberName = null;
        Integer argCount = null;
        int separator = separatorIndex(name);
        if (separator > 0) {
            Matcher matcher = MEMBER_REF.matcher(name.substring(separator + 1));
            if (matcher.matches()) {
                typePart = name.substring(0, separator);
                memberName = matcher.group(1);
                argCount = matcher.group(2) == null ? null : Integer.valueOf(matcher.group(2));
                List<SymbolView> types = typesBy(lookup(queries, repoId, typePart), typePart);
                if (types.size() == 1) {
                    List<SymbolView> members = membersOf(queries, repoId, types.get(0), memberName, argCount);
                    if (!members.isEmpty()) {
                        return new Resolution(members, List.of(), overloadNote(members));
                    }
                    // 类型对、成员名不对：把该类型的成员列出来让模型自己纠正（定向修正，不是重试）
                    return Resolution.none("类型 " + types.get(0).qualifiedName() + " 下没有叫「"
                            + memberName + "」的成员。它的成员有：" + memberNames(queries, types.get(0)));
                }
            }
        }

        // ③ 裸名字
        List<SymbolView> byName = pool.stream().filter(symbol -> symbol.name().equals(name)).toList();
        if (byName.isEmpty() && memberName != null) {
            // 模型把类名写错了，但成员名是对的 —— 这时最有用的话是"这个成员属于哪几个类"
            String wanted = memberName;
            List<SymbolView> byMember = lookup(queries, repoId, wanted).stream()
                    .filter(symbol -> symbol.name().equals(wanted)).toList();
            if (!byMember.isEmpty()) {
                return ambiguous(wanted, groupByOwner(byMember),
                        "找不到类型「" + typePart + "」。但「" + wanted + "」在这些类型里有同名成员，请用其中一个限定：");
            }
        }
        if (byName.isEmpty()) {
            return Resolution.none("索引里没有叫「" + name + "」的符号。可以先用 textSearch 搜关键词，"
                    + "或用 findDefinition 试一个类名。");
        }
        // ③.5 **优先类型而不是它的成员**：构造函数与类同名（实测：查询 "FindImplementationsTool 有哪些成员"
        // 解析到了构造函数，而构造函数没有成员 → 那一题必然答错）。
        // 类名指向"那个类"永远比指向"它的某个成员"更符合提问意图。
        List<SymbolView> typesOnly = byName.stream().filter(symbol -> isTypeKind(symbol.kind())).toList();
        if (!typesOnly.isEmpty()) {
            byName = typesOnly;
        }

        Map<String, List<SymbolView>> byOwner = groupByOwner(byName);
        if (byOwner.size() == 1) {
            List<SymbolView> group = byOwner.values().iterator().next();
            return new Resolution(group, List.of(), overloadNote(group));
        }
        return ambiguous(name, byOwner, "「" + name + "」在 " + byOwner.size()
                + " 个类型里都有同名符号，**这里不猜**：请用「类名.方法名」限定其一。候选：");
    }

    public static String describe(SymbolView symbol) {
        return symbol.kind() + " " + symbol.qualifiedName()
                + (symbol.signature() == null || symbol.signature().isBlank() || symbol.signature().isBlank()
                ? "" : "  " + symbol.signature())
                + "  (" + symbol.location() + ")";
    }

    /** 类型类符号（类/接口/枚举/记录/注解）—— 与 eval 包里那套判据同一个口径。 */
    static boolean isTypeKind(String kind) {
        return switch (kind) {
            case "CLASS", "INTERFACE", "ENUM", "RECORD", "ANNOTATION" -> true;
            default -> false;
        };
    }

    private static List<SymbolView> lookup(SymbolQueryService queries, long repoId, String text) {
        return queries.locate(repoId, text, LOOKUP_LIMIT);
    }

    private static List<SymbolView> typesBy(List<SymbolView> pool, String text) {
        return pool.stream()
                .filter(symbol -> SymbolKinds.isType(symbol.kind()))
                .filter(symbol -> symbol.name().equals(text) || symbol.qualifiedName().equals(text))
                .toList();
    }

    private static List<SymbolView> membersOf(SymbolQueryService queries, long repoId,
                                             SymbolView type, String memberName, Integer argCount) {
        List<SymbolView> members = queries.findMembersInType(repoId, type.qualifiedName(), memberName);
        if (argCount == null) {
            return members;
        }
        return members.stream()
                .filter(member -> member.qualifiedName().endsWith("/" + argCount))
                .toList();
    }

    /** 命中的是方法/构造器时，把同一个类型下的同名重载一起带上 —— 人问"谁调用了 read"指的是全部重载。 */
    private static Resolution withOverloads(SymbolQueryService queries, long repoId, SymbolView symbol) {
        if (!SymbolKinds.isCallable(symbol.kind()) || symbol.parentId() == null) {
            return new Resolution(List.of(symbol), List.of(), "");
        }
        SymbolView owner;
        try {
            owner = queries.requireSymbol(symbol.parentId());
        } catch (NotFoundException e) {
            return new Resolution(List.of(symbol), List.of(), "");
        }
        List<SymbolView> overloads = queries.findMembersInType(repoId, owner.qualifiedName(), symbol.name());
        if (overloads.isEmpty()) {
            return new Resolution(List.of(symbol), List.of(), "");
        }
        return new Resolution(overloads, List.of(), overloadNote(overloads));
    }

    private static Map<String, List<SymbolView>> groupByOwner(List<SymbolView> symbols) {
        Map<String, List<SymbolView>> byOwner = new LinkedHashMap<>();
        for (SymbolView symbol : symbols) {
            String key = symbol.parentId() == null ? "type:" + symbol.id() : "parent:" + symbol.parentId();
            byOwner.computeIfAbsent(key, ignored -> new ArrayList<>()).add(symbol);
        }
        return byOwner;
    }

    private static Resolution ambiguous(String name, Map<String, List<SymbolView>> byOwner, String prefix) {
        List<SymbolView> candidates = byOwner.values().stream()
                .map(group -> group.get(0))
                .limit(MAX_CANDIDATES)
                .toList();
        String list = candidates.stream().map(SymbolResolver::describe).collect(Collectors.joining("\n- "));
        return new Resolution(List.of(), candidates,
                prefix + "\n- " + list + (byOwner.size() > MAX_CANDIDATES ? "\n（其余略）" : ""));
    }

    private static String overloadNote(List<SymbolView> symbols) {
        if (symbols.size() <= 1) {
            return "";
        }
        return "（同一个类型下有 " + symbols.size() + " 个同名符号/重载，已一并纳入："
                + symbols.stream().map(SymbolView::qualifiedName).collect(Collectors.joining("、")) + "）";
    }

    private static String memberNames(SymbolQueryService queries, SymbolView type) {
        return queries.children(type.id()).stream()
                .map(SymbolView::name)
                .distinct()
                .limit(20)
                .collect(Collectors.joining("、"));
    }

    /** 最后一个 {@code #} 或 {@code .} 的位置；两个都有时以 {@code #} 为准（它更明确）。 */
    private static int separatorIndex(String name) {
        int hash = name.indexOf('#');
        if (hash > 0) {
            return hash;
        }
        int dot = name.lastIndexOf('.');
        return dot > 0 ? dot : -1;
    }

    /**
     * 去掉模型爱写的装饰：反引号、引号、尖括号、结尾的 {@code ()} 与悬空的 {@code .}/{@code #}。
     *
     * <p><b>注意 {@code <init>} 例外</b>：构造器在符号表里的真名就是 {@code <init>}，
     * 早先"见到尖括号就删"的写法会把构造器写成 {@code init}，于是**所有涉及构造器的查询都解析不到**
     * —— 这个 bug 是第 5 步的离线对比实验撞出来的（真值里的构造器一个都没被查到）。
     */
    static String clean(String raw) {
        if (raw == null) {
            return "";
        }
        String text = raw.strip().replace("`", "").replace("\"", "").strip();
        if (!text.contains("<init>")) {
            text = text.replace("<", "").replace(">", "");
        }
        text = text.strip();
        while (text.endsWith("()")) {
            text = text.substring(0, text.length() - 2).strip();
        }
        while (!text.isEmpty() && (text.endsWith(".") || text.endsWith("#"))) {
            text = text.substring(0, text.length() - 1).strip();
        }
        return text.strip();
    }
}

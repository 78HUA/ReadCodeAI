package com.readcodeai.retrieve;

import com.readcodeai.retrieve.model.SymbolKinds;
import com.readcodeai.retrieve.model.SymbolView;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 问题路由：判断一个问题该走哪一层检索。
 *
 * <p><b>这是「能算准的别猜」落地的地方。</b> 四类问题里有三类是**确定性问题**
 * （谁调用了它 / 它调用了谁 / 有哪些实现），答案是图查询算出来的 ——
 * 让模型去"组织"这类答案，只会把确定性换成不确定性。
 * 只有剩下那一类（模糊语义查找）才交给模型。
 *
 * <p>路由靠的是**问题里的词汇模式 + 能否在符号表里找到目标符号**，
 * 不做语义理解 —— 因为路由错了的代价（用错层）比多问一句更大，而模式匹配是可解释、可测的。
 */
@Component
public class QueryRouter {

    /** 问题该走哪条路。前四条是确定性的，只有 {@code SEMANTIC} 需要模型。 */
    public enum Route {
        /** 谁调用了它 */
        CALLERS,
        /** 它调用了谁 */
        CALLEES,
        /** 有哪些实现类 */
        IMPLEMENTATIONS,
        /** 这个类有哪些方法/字段（结构） */
        STRUCTURE,
        /** 定义/实现在哪 */
        LOCATE,
        /** 模糊语义查找 —— 只有这一类需要向量/模型 */
        SEMANTIC;

        public boolean isDeterministic() {
            return this != SEMANTIC;
        }
    }

    public record Routed(Route route, List<SymbolView> targets) {

        public boolean isDeterministic() {
            return route.isDeterministic() && !targets.isEmpty();
        }
    }

    /** 提问方式的词汇模式。中文与英文都要覆盖（这个项目的主要使用者写中文）。 */
    private static final List<Pattern> CALLERS_PATTERNS = List.of(
            Pattern.compile("谁调用|被谁调用|哪些地方调用|调用点|调用者|who calls|callers? of", Pattern.CASE_INSENSITIVE));
    private static final List<Pattern> CALLEES_PATTERNS = List.of(
            Pattern.compile("调用了谁|调用了哪些|依赖了哪些|会调用什么|what does .* call", Pattern.CASE_INSENSITIVE));
    private static final List<Pattern> IMPLEMENTATIONS_PATTERNS = List.of(
            Pattern.compile("有哪些实现|实现类|谁实现了|有几个实现|implementations?|implemented by", Pattern.CASE_INSENSITIVE));
    /** 结构题：问一个类型有哪些成员。注意要排在「定位题」之前判断 —— 「有哪些方法」不是「在哪定义」。 */
    private static final List<Pattern> STRUCTURE_PATTERNS = List.of(
            Pattern.compile("有哪些方法|哪些方法|有哪些字段|有哪些成员|有哪些属性|方法列表|字段列表|"
                    + "what methods|which methods|what fields|members of", Pattern.CASE_INSENSITIVE));
    private static final List<Pattern> LOCATE_PATTERNS = List.of(
            Pattern.compile("在哪定义|定义在哪|在哪个类|在哪实现|在哪里实现|的位置|定义在|where is .* defined",
                    Pattern.CASE_INSENSITIVE));

    /**
     * 从问题里挑出**代码标识符**候选：中文问句里夹着的英文词，通常就是符号名。
     *
     * <p><b>不设长度下限</b>：实测踩过 —— 只允许 3 个字符以上时，
     * reggie 的核心类 {@code R} 这种**单字符类名**根本提取不出来，问题直接掉进语义检索。
     * 放宽不会带来噪声，因为候选只是"待查清单"，**最终能不能用取决于符号表里有没有精确同名**。
     */
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_$][A-Za-z0-9_$]*");

    /** 这些词是问句里的普通英文，不是符号名，别拿它们去符号表里查。 */
    private static final List<String> STOP_WORDS = List.of(
            "the", "and", "for", "what", "which", "where", "who", "why", "how",
            "call", "calls", "called", "caller", "callers", "method", "methods",
            "class", "classes", "implementation", "implementations", "defined", "definition",
            // 单双字符的英文虚词（放宽长度下限后必须挡住，否则每个问句都会多几个候选）
            "a", "an", "is", "to", "of", "in", "on", "it", "by", "or", "do", "as",
            "at", "be", "we", "no", "so", "if", "me", "my", "up", "us");

    public Routed route(long repoId, String question, SymbolLookup lookup) {
        Route route = detectRoute(question);
        List<String> candidates = identifierCandidates(question);
        List<SymbolView> targets = resolveTargets(route, candidates, lookup);
        // 目标符号找不到就退回语义检索 —— 路由不到具体符号时，确定性查询无从下手
        return new Routed(targets.isEmpty() && route.isDeterministic() ? Route.SEMANTIC : route, targets);
    }

    /** 按「问法」判断路线；判断不了的走语义检索。 */
    Route detectRoute(String question) {
        if (matches(question, CALLERS_PATTERNS)) {
            return Route.CALLERS;
        }
        if (matches(question, CALLEES_PATTERNS)) {
            return Route.CALLEES;
        }
        if (matches(question, IMPLEMENTATIONS_PATTERNS)) {
            return Route.IMPLEMENTATIONS;
        }
        if (matches(question, STRUCTURE_PATTERNS)) {
            return Route.STRUCTURE;
        }
        if (matches(question, LOCATE_PATTERNS)) {
            return Route.LOCATE;
        }
        return Route.SEMANTIC;
    }

    private static boolean matches(String question, List<Pattern> patterns) {
        return patterns.stream().anyMatch(p -> p.matcher(question).find());
    }

    /** 问题里出现的英文标识符候选（去停用词、去重、按出现顺序）。 */
    static List<String> identifierCandidates(String question) {
        List<String> candidates = new ArrayList<>();
        Matcher matcher = IDENTIFIER.matcher(question);
        while (matcher.find()) {
            String token = matcher.group();
            if (STOP_WORDS.contains(token.toLowerCase(Locale.ROOT)) || candidates.contains(token)) {
                continue;
            }
            candidates.add(token);
        }
        return candidates;
    }

    /**
     * 把候选标识符换成真实符号。
     *
     * <p><b>优先做限定查找</b>：问题里同时出现类型名和方法名时（"XxxService 的 read 方法"），
     * 先在那个类型里找 —— 否则裸方法名不唯一时只能挑"第一个同名的"，
     * 那是猜，不是查。**实测：不加这一步，同名方法多的语料上命中率会掉到 0**
     * （答案指向了另一个同名方法，和真值零重叠）。
     */
    private List<SymbolView> resolveTargets(Route route, List<String> candidates, SymbolLookup lookup) {
        SymbolView owner = firstExactType(candidates, lookup);
        if (owner != null) {
            for (String candidate : candidates) {
                if (candidate.equals(owner.name())) {
                    continue;
                }
                List<SymbolView> inType = lookup.findInType(owner.qualifiedName(), candidate).stream()
                        .filter(symbol -> kindMatchesRoute(route, symbol))
                        .toList();
                if (!inType.isEmpty()) {
                    return inType;
                }
            }
        }
        for (String candidate : candidates) {
            List<SymbolView> exact = lookup.find(candidate).stream()
                    .filter(symbol -> symbol.name().equals(candidate))
                    .filter(symbol -> kindMatchesRoute(route, symbol))
                    .toList();
            if (!exact.isEmpty()) {
                return exact;
            }
        }
        return List.of();
    }

    /** 候选里有没有精确命中某个**类型**的 —— 有的话它就是"限定范围"的线索。 */
    private static SymbolView firstExactType(List<String> candidates, SymbolLookup lookup) {
        for (String candidate : candidates) {
            for (SymbolView symbol : lookup.find(candidate)) {
                if (symbol.name().equals(candidate) && SymbolKinds.isType(symbol.kind())) {
                    return symbol;
                }
            }
        }
        return null;
    }

    private static boolean kindMatchesRoute(Route route, SymbolView symbol) {
        boolean isType = SymbolKinds.isType(symbol.kind());
        return switch (route) {
            // 问调用关系问的是「哪个方法」，类型没有调用边
            case CALLERS, CALLEES -> SymbolKinds.isCallable(symbol.kind());
            // 问实现类、问成员的目标都必须是类型
            case IMPLEMENTATIONS, STRUCTURE -> isType;
            default -> true;
        };
    }

    /** 查符号的手段由调用方注入，路由本身不碰数据库（便于单测）。 */
    public interface SymbolLookup {

        List<SymbolView> find(String name);

        /** 在指定类型里按名字找成员；不支持的实现返回空列表即可。 */
        default List<SymbolView> findInType(String typeQualifiedName, String name) {
            return List.of();
        }
    }
}

package com.readcodeai.retrieve;

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
    private static final List<Pattern> LOCATE_PATTERNS = List.of(
            Pattern.compile("在哪定义|定义在哪|在哪个类|在哪实现|在哪里实现|的位置|定义在|where is .* defined",
                    Pattern.CASE_INSENSITIVE));

    /** 从问题里挑出**代码标识符**候选：中文问句里夹着的英文词，通常就是符号名。 */
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_$][A-Za-z0-9_$]{2,}");

    /** 这些词是问句里的普通英文，不是符号名，别拿它们去符号表里查。 */
    private static final List<String> STOP_WORDS = List.of(
            "the", "and", "for", "what", "which", "where", "who", "why", "how",
            "call", "calls", "called", "caller", "callers", "method", "methods",
            "class", "classes", "implementation", "implementations", "defined", "definition");

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
     * <p>多条命中时优先取**名字完全一致**的：问「谁调用了 submit」时，
     * 候选里可能还有提问者随口提到的其它词，精确匹配能把它们筛掉。
     */
    private List<SymbolView> resolveTargets(Route route, List<String> candidates, SymbolLookup lookup) {
        for (String candidate : candidates) {
            List<SymbolView> found = lookup.find(candidate);
            List<SymbolView> exact = found.stream()
                    .filter(s -> s.name().equals(candidate))
                    .filter(s -> kindMatchesRoute(route, s))
                    .toList();
            if (!exact.isEmpty()) {
                return exact;
            }
        }
        return List.of();
    }

    private static boolean kindMatchesRoute(Route route, SymbolView symbol) {
        boolean isType = switch (symbol.kind()) {
            case "CLASS", "INTERFACE", "ENUM", "RECORD", "ANNOTATION" -> true;
            default -> false;
        };
        return switch (route) {
            // 问调用关系问的是「哪个方法」，类型没有调用边
            case CALLERS, CALLEES -> "METHOD".equals(symbol.kind()) || "CONSTRUCTOR".equals(symbol.kind());
            // 问实现类的目标必须是类型
            case IMPLEMENTATIONS -> isType;
            default -> true;
        };
    }

    /** 查符号的手段由调用方注入，路由本身不碰数据库（便于单测）。 */
    @FunctionalInterface
    public interface SymbolLookup {
        List<SymbolView> find(String name);
    }
}

package com.readcodeai.review;

import com.readcodeai.retrieve.SymbolQueryService;
import com.readcodeai.retrieve.model.CallSiteView;
import com.readcodeai.retrieve.model.SymbolView;
import com.readcodeai.review.model.ReviewReport.MachineFinding;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * **规则查得出来的那部分审查**：不经过模型。
 *
 * <p>这是「能算准的别猜」在代码审查上的落地 —— 先说清楚哪些是算出来的：
 * 方法多长、参数有几个、catch 块是不是空的、有没有人调用、类是不是太大了、
 * 这类对外调用里多少处压根没解析出来（盲区集中在哪里）。这些**看一眼代码或索引就有答案**，
 * 让模型去"发现"它们，只是把确定性换成不确定性。
 *
 * <p>模型的活因此被压到很小：材料里那些**规则说不清**的东西（逻辑矛盾、边界漏了、语义重复…），
 * 而且它说的每一句都要带证据、还要过核验。
 *
 * <p>规则不追求全：每条都能解释、都能复核，比"看起来覆盖很多"重要。
 * 阈值是工程取值（写在常量里，一处可调），不是行业标准。
 */
public final class ReviewToolkit {

    /** 方法超过这个行数就算长：不是规范，只是一个"值得看一眼"的信号。 */
    static final int LONG_METHOD_LINES = 60;

    /** 参数超过这个数就算多：从签名里数逗号，近似但确定。 */
    static final int MANY_PARAMS = 4;

    /** 成员超过这个数就算大：类大到一定程度，通常已经有几件事挤在一起了。 */
    static final int GOD_CLASS_MEMBERS = 25;

    /** 未解析调用占比超过这个数、且总数够多时，才认为"盲区集中在这个类"。 */
    static final double UNRESOLVED_RATIO = 0.5;

    private static final int UNRESOLVED_MIN_TOTAL = 5;

    /** 空的 catch 块：{@code catch (...) {}} 或后面紧跟只有右括号/注释的块。 */
    private static final Pattern CATCH = Pattern.compile("catch\\s*\\([^)]*\\)\\s*\\{");

    private ReviewToolkit() {
    }

    /**
     * 对一个类跑全部规则。
     *
     * @param sourceLines 类源码（含首行前的行号偏移已在 {@code sourceStartLine} 里给出）
     */
    public static List<MachineFinding> inspect(SymbolQueryService queries, SymbolView target,
                                               List<SymbolView> members, String sourceLines,
                                               int sourceStartLine, List<CallSiteView> callees) {
        List<MachineFinding> findings = new ArrayList<>();
        findings.addAll(longMethods(members));
        findings.addAll(manyParameters(members));
        findings.addAll(emptyCatchBlocks(sourceLines, sourceStartLine, target));
        findings.addAll(classSize(target, members));
        findings.addAll(unresolvedCalls(target, callees));
        return findings;
    }

    static List<MachineFinding> longMethods(List<SymbolView> members) {
        return members.stream()
                .filter(member -> isCallable(member.kind()))
                .filter(member -> member.endLine() - member.startLine() + 1 >= LONG_METHOD_LINES)
                .map(member -> new MachineFinding("LONG_METHOD", "low",
                        "方法 " + member.name() + " 有 " + (member.endLine() - member.startLine() + 1)
                                + " 行（阈值 " + LONG_METHOD_LINES + "）：先看它是不是同时干了好几件事",
                        member.filePath(), member.startLine(), member.endLine()))
                .toList();
    }

    static List<MachineFinding> manyParameters(List<SymbolView> members) {
        List<MachineFinding> findings = new ArrayList<>();
        for (SymbolView member : members) {
            if (!isCallable(member.kind()) || member.signature() == null) {
                continue;
            }
            int count = parameterCount(member.signature());
            if (count > MANY_PARAMS) {
                findings.add(new MachineFinding("MANY_PARAMS", "low",
                        "方法 " + member.name() + " 有 " + count + " 个参数（阈值 " + MANY_PARAMS
                                + "）：调用点容易传错顺序，考虑收成一个参数对象",
                        member.filePath(), member.startLine(), member.endLine()));
            }
        }
        return findings;
    }

    /**
     * 数签名里的参数个数（**近似**：按括号里的顶层逗号数；泛型里的逗号会被…
     * 嗯，会被误数 —— 所以这里先剥掉尖括号再数，这是本规则唯一的取巧之处，写在注释里免得被当成 bug）。
     */
    static int parameterCount(String signature) {
        int open = signature.indexOf('(');
        int close = signature.lastIndexOf(')');
        if (open < 0 || close <= open) {
            return 0;
        }
        String inside = signature.substring(open + 1, close);
        StringBuilder stripped = new StringBuilder();
        int depth = 0;
        for (char c : inside.toCharArray()) {
            if (c == '<') {
                depth++;
            } else if (c == '>') {
                depth = Math.max(0, depth - 1);
            } else if (depth == 0) {
                stripped.append(c);
            }
        }
        String text = stripped.toString().strip();
        return text.isEmpty() ? 0 : text.split(",").length;
    }

    /** 空 catch：吞掉异常是代码审查里最常见、也最值得指出的一类问题。 */
    static List<MachineFinding> emptyCatchBlocks(String source, int sourceStartLine, SymbolView target) {
        if (source == null || source.isBlank()) {
            return List.of();
        }
        List<String> lines = source.lines().toList();
        List<MachineFinding> findings = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            Matcher matcher = CATCH.matcher(lines.get(i));
            if (!matcher.find()) {
                continue;
            }
            if (bodyIsEmpty(lines, i, matcher.end())) {
                int line = sourceStartLine + i;
                findings.add(new MachineFinding("EMPTY_CATCH", "medium",
                        "空 catch：异常被吞掉了，出问题时不会有任何痕迹（至少要记一条日志或补一句为什么可以忽略）",
                        target.filePath(), line, line));
            }
        }
        return findings;
    }

    /**
     * 从 catch 那行的括号之后开始看：立刻闭合、或后面只有注释/空行再闭合，才算空。
     *
     * <p><b>踩过一次</b>：{@code catch (Exception e) {} } 写成多行时（括号在行尾、内容在下一行），
     * 只看"括号后面为空"会把有日志的 catch 也判成空 catch —— 假阳性比漏报更伤人，
     * 因为它会让使用者开始不信规则。
     */
    private static boolean bodyIsEmpty(List<String> lines, int catchLine, int afterBrace) {
        String current = lines.get(catchLine);
        String rest = current.substring(Math.min(afterBrace, current.length())).strip();
        if (rest.startsWith("}")) {
            return true;
        }
        if (!rest.isEmpty() && !rest.startsWith("//")) {
            return false;
        }
        for (int i = catchLine + 1; i < lines.size(); i++) {
            String line = lines.get(i).strip();
            if (line.isEmpty() || line.startsWith("//") || line.startsWith("*") || line.startsWith("/*")) {
                continue;
            }
            return line.startsWith("}");
        }
        return true;
    }

    static List<MachineFinding> classSize(SymbolView target, List<SymbolView> members) {
        if (members.size() <= GOD_CLASS_MEMBERS) {
            return List.of();
        }
        return List.of(new MachineFinding("GOD_CLASS", "low",
                "这个类有 " + members.size() + " 个成员（阈值 " + GOD_CLASS_MEMBERS
                        + "）：看看是不是几件事挤在一起，可以按职责拆开",
                target.filePath(), target.startLine(), target.endLine()));
    }

    /**
     * 未解析调用占比高：这个信号**只有静态分析工具给得出**（IDE 也会告诉你"找不到符号"，
     * 但不会把"这个类的调用有一半在盲区里"当成审查意见说出来）。
     */
    static List<MachineFinding> unresolvedCalls(SymbolView target, List<CallSiteView> callees) {
        if (callees.size() < UNRESOLVED_MIN_TOTAL) {
            return List.of();
        }
        long unresolved = callees.stream().filter(call -> !call.resolved()).count();
        if ((double) unresolved / callees.size() < UNRESOLVED_RATIO) {
            return List.of();
        }
        return List.of(new MachineFinding("UNRESOLVED_CALLS", "info",
                "这个类的 " + callees.size() + " 处对外调用里有 " + unresolved
                        + " 处没能解析出目标（外部依赖或动态调用）：阅读时对这段调用关系要留个心眼",
                target.filePath(), target.startLine(), target.endLine()));
    }

    private static boolean isCallable(String kind) {
        return "METHOD".equals(kind) || "CONSTRUCTOR".equals(kind);
    }
}

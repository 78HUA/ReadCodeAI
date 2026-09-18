package com.readcodeai.agent;

import com.readcodeai.config.LlmClient;

import java.util.ArrayList;
import java.util.List;

/**
 * 脚本化的假模型：**用它把循环机制在不起网络、不花 token 的情况下逐条验证掉**。
 *
 * <p>为什么必须有它：多跳循环里真正容易出错的是**机制**（环检测、预算闸门、证据核验、
 * 格式错误的兜底），而不是模型聪不聪明。这些机制如果用真实模型来测，就变成
 * "网络好的时候能测、每次都花钱、还不可复现" —— 那不是测试，是碰运气。
 *
 * <p>所以分成两层：
 * <ul>
 *   <li><b>机制</b>：脚本模型，完全确定、毫秒级、可断言每一个分支</li>
 *   <li><b>效果</b>：真实模型，只在验证记录里报数字（见 {@code MultiHopLiveTest}）</li>
 * </ul>
 */
public class ScriptedLlmClient implements LlmClient {

    /** 第几轮"模型"说什么。turn 从 1 开始。 */
    public interface Script {
        String next(int turn, String userPrompt);
    }

    private final Script script;
    private final List<String> prompts = new ArrayList<>();
    private int fixedPromptTokens = -1;
    private int fixedCompletionTokens = -1;

    public ScriptedLlmClient(Script script) {
        this.script = script;
    }

    /** 按顺序念台词；念完就拒答（避免脚本用尽后静默返回空字符串）。 */
    public static ScriptedLlmClient lines(String... lines) {
        return new ScriptedLlmClient((turn, prompt) -> turn <= lines.length
                ? lines[turn - 1]
                : refuse("脚本已用尽"));
    }

    /** 固定每次调用的 token 用量 —— 预算相关的断言要能精确算出来，不能靠估算。 */
    public ScriptedLlmClient withTokenUsage(int promptTokens, int completionTokens) {
        this.fixedPromptTokens = promptTokens;
        this.fixedCompletionTokens = completionTokens;
        return this;
    }

    @Override
    public boolean available() {
        return true;
    }

    @Override
    public String model() {
        return "scripted";
    }

    @Override
    public Completion complete(String systemPrompt, String userPrompt) {
        prompts.add(userPrompt);
        String content = script.next(prompts.size(), userPrompt);
        return new Completion(content,
                tokens(fixedPromptTokens, systemPrompt + userPrompt),
                tokens(fixedCompletionTokens, content));
    }

    public List<String> prompts() {
        return List.copyOf(prompts);
    }

    public int calls() {
        return prompts.size();
    }

    public String lastPrompt() {
        return prompts.isEmpty() ? null : prompts.get(prompts.size() - 1);
    }

    /** 长度估算只用于"没显式指定 token 用量"的场合；中文与代码的比值不一样，别拿它当精确值。 */
    private static int tokens(int fixed, String text) {
        if (fixed >= 0) {
            return fixed;
        }
        return (int) Math.ceil((text == null ? 0 : text.length()) / 3.0);
    }

    // ---- 台词构造（让测试看起来像"模型的输出"，而不是一堆转义字符）----

    public static String callTool(String tool, String argName, String argValue) {
        return "{\"thought\":\"脚本指定：查 " + argValue + "\",\"tool\":\"" + tool
                + "\",\"args\":{\"" + argName + "\":\"" + argValue + "\"}}";
    }

    public static String answer(String text, String file, int startLine, int endLine, String snippet) {
        String snippetJson = snippet == null ? "" : snippet.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n");
        return "{\"thought\":\"够了\",\"final\":{\"answer\":\"" + text
                + "\",\"evidence\":[{\"file\":\"" + file + "\",\"startLine\":" + startLine
                + ",\"endLine\":" + endLine + ",\"snippet\":\"" + snippetJson
                + "\",\"why\":\"脚本给的证据\"}],\"refused\":false,\"refusalReason\":\"\"}}";
    }

    public static String refuse(String reason) {
        return "{\"thought\":\"材料不够\",\"final\":{\"answer\":\"\",\"evidence\":[],\"refused\":true,"
                + "\"refusalReason\":\"" + reason + "\"}}";
    }
}

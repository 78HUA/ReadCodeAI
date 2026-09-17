package com.readcodeai.index.parser;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.RecordDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.SuperExpr;
import com.github.javaparser.ast.nodeTypes.NodeWithJavadoc;
import com.github.javaparser.ast.nodeTypes.NodeWithModifiers;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.resolution.UnsolvedSymbolException;
import com.github.javaparser.resolution.declarations.ResolvedMethodDeclaration;
import com.github.javaparser.resolution.types.ResolvedType;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JavaParserTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;
import com.readcodeai.index.model.CollectedCall;
import com.readcodeai.index.model.CollectedChunk;
import com.readcodeai.index.model.CollectedSymbol;
import com.readcodeai.index.model.CollectedTypeRelation;
import com.readcodeai.index.model.FileOutcome;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;

/**
 * 把一个 Java 仓库解析成符号表、调用边与类型关系。
 *
 * <p><b>为什么分两阶段：</b>要判断一条调用是「仓库内」还是「外部依赖」，必须先把仓库里所有类型
 * 都收集完。所以第一阶段解析全部文件并收集符号（保留 AST），第二阶段才用符号求解器解析调用点。
 * 代价是分析期间所有 AST 都留在内存里 —— 十万行以上的仓库要重新评估这一步（见设计文档第 8 章）。
 *
 * <p><b>已知近似（如实列明，不要当成 bug 去"修"）：</b>
 * <ul>
 *   <li>方法体内嵌套的匿名类 / lambda 里的调用，会归属于外层方法 —— 对使用者来说这通常比
 *       归属到编译器合成的名字更有用，但严格说与 JVM 实际结构不一致</li>
 *   <li>匿名类、局部类、初始化块不建符号（记为盲区）</li>
 *   <li>反射、动态代理、注解处理器生成的代码静态看不到，会落到 unresolved 并带上原因</li>
 * </ul>
 */
public class SourceAnalyzer {

    static final String INIT = "<init>";

    private static final int JAVADOC_MAX = 500;

    private final JavaParser parser;

    public SourceAnalyzer(List<Path> sourceRoots) {
        CombinedTypeSolver typeSolver = new CombinedTypeSolver();
        // JDK 自带类型走反射；仓库内类型走源码解析
        typeSolver.add(new ReflectionTypeSolver(false));
        for (Path root : sourceRoots) {
            typeSolver.add(new JavaParserTypeSolver(root));
        }
        this.parser = new JavaParser(new ParserConfiguration()
                .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21)
                // 注意：JavaParser 3.28 的方法名是 setSymbolResolver（不是 setSymbolSolver）
                .setSymbolResolver(new JavaSymbolSolver(typeSolver)));
    }

    public AnalyzeResult analyze(Path repoRoot, List<Path> javaFiles, int maxFileSizeKb) {
        List<FileOutcome> outcomes = new ArrayList<>();
        List<ParsedFile> parsedFiles = new ArrayList<>();
        // 文件行文本留一份：生成检索单元时要按行切片，切出来的必须是源文件原文（行号与内容都要能回磁盘核对）
        Map<String, List<String>> linesByFile = new HashMap<>();

        // ---- 解析 ----
        long parseStart = System.nanoTime();
        for (Path file : javaFiles) {
            String relativePath = repoRoot.relativize(file).toString().replace('\\', '/');
            long sizeKb;
            try {
                sizeKb = Files.size(file) / 1024;
            } catch (IOException e) {
                sizeKb = 0;
            }
            if (sizeKb > maxFileSizeKb) {
                outcomes.add(new FileOutcome(relativePath, "", 0, false,
                        "文件超过 " + maxFileSizeKb + " KB，已跳过"));
                continue;
            }

            String content;
            int loc;
            try {
                content = Files.readString(file, StandardCharsets.UTF_8);
                loc = (int) content.lines().count();
            } catch (IOException e) {
                outcomes.add(new FileOutcome(relativePath, "", 0, false,
                        "读取失败：" + e.getClass().getSimpleName() + "（可能是非 UTF-8 编码）"));
                continue;
            }

            ParseResult<CompilationUnit> result;
            try {
                result = parser.parse(file);
            } catch (IOException e) {
                // JavaParser 的 parse(Path) 自己会再读一次文件，这里也可能失败
                outcomes.add(new FileOutcome(relativePath, sha256(content), loc, false,
                        "解析时读取失败：" + e.getClass().getSimpleName()));
                continue;
            }
            if (!result.isSuccessful() || result.getResult().isEmpty()) {
                String reason = result.getProblems().isEmpty()
                        ? "未知解析错误"
                        : result.getProblems().get(0).getMessage();
                outcomes.add(new FileOutcome(relativePath, sha256(content), loc, false, reason));
                continue;
            }
            outcomes.add(new FileOutcome(relativePath, sha256(content), loc, true, null));
            parsedFiles.add(new ParsedFile(relativePath, result.getResult().get()));
            linesByFile.put(relativePath, content.lines().toList());
        }
        long parseMillis = (System.nanoTime() - parseStart) / 1_000_000;

        // ---- 阶段一：收集符号 ----
        Set<String> symbolKeys = new HashSet<>();
        List<CollectedSymbol> symbols = new ArrayList<>();
        for (ParsedFile parsedFile : parsedFiles) {
            String packageName = packageOf(parsedFile.unit());
            for (TypeDeclaration<?> type : parsedFile.unit().getTypes()) {
                collectType(type, null, packageName, parsedFile.relativePath(), symbols, parsedFile.rawCalls(), symbolKeys);
            }
        }

        // ---- 阶段二：解析调用与继承关系（现在才知道仓库内有哪些符号）----
        long resolveStart = System.nanoTime();
        List<CollectedCall> calls = new ArrayList<>();
        List<CollectedTypeRelation> relations = new ArrayList<>();
        for (ParsedFile parsedFile : parsedFiles) {
            for (RawCall raw : parsedFile.rawCalls()) {
                calls.add(resolveCall(raw, symbolKeys));
            }
            String packageName = packageOf(parsedFile.unit());
            for (TypeDeclaration<?> type : parsedFile.unit().getTypes()) {
                collectRelations(type, null, packageName, relations, symbolKeys);
            }
        }
        long resolveMillis = (System.nanoTime() - resolveStart) / 1_000_000;

        // ---- 生成检索单元（按符号切，不按行切）----
        List<CollectedChunk> chunks = buildChunks(symbols, parsedFiles, linesByFile);

        return new AnalyzeResult(outcomes, symbols, calls, relations, chunks, parseMillis, resolveMillis);
    }

    // ------------------------------------------------------------------ 检索单元

    /**
     * 生成全文检索用的 chunk。
     *
     * <p>只给**方法 / 构造器 / 字段**切块，不给类型切块 —— 类型块会把它所有方法再存一遍，
     * 属于重复内容，只会稀释检索质量。类型的语义由每个文件一份的头部块承担。
     */
    private List<CollectedChunk> buildChunks(List<CollectedSymbol> symbols, List<ParsedFile> parsedFiles,
                                             Map<String, List<String>> linesByFile) {
        List<CollectedChunk> chunks = new ArrayList<>();
        for (CollectedSymbol symbol : symbols) {
            if (isType(symbol.kind())) {
                continue;
            }
            String content = slice(linesByFile.get(symbol.filePath()), symbol.startLine(), symbol.endLine());
            if (content == null || content.isBlank()) {
                continue;
            }
            chunks.add(new CollectedChunk(symbol.filePath(), CollectedChunk.KIND_SYMBOL,
                    symbol.qualifiedName(), symbol.startLine(), symbol.endLine(),
                    sha256(content), content, estimateTokens(content)));
        }

        for (ParsedFile parsedFile : parsedFiles) {
            List<String> lines = linesByFile.get(parsedFile.relativePath());
            if (lines == null || lines.isEmpty()) {
                continue;
            }
            int end = headerEndLine(symbols, parsedFile.relativePath(), lines.size());
            String content = slice(lines, 1, end);
            if (content == null || content.isBlank()) {
                continue;
            }
            chunks.add(new CollectedChunk(parsedFile.relativePath(), CollectedChunk.KIND_FILE_HEADER,
                    null, 1, end, sha256(content), content, estimateTokens(content)));
        }
        return chunks;
    }

    /**
     * 头部块切到哪一行：**第一个成员的前一行** ——
     * 这样它正好覆盖「包声明 + import + 类 javadoc + 类声明」，不含方法体。
     * 没有成员就切到类型声明行。
     */
    private static int headerEndLine(List<CollectedSymbol> symbols, String filePath, int fileLines) {
        int firstMember = Integer.MAX_VALUE;
        int firstType = Integer.MAX_VALUE;
        for (CollectedSymbol symbol : symbols) {
            if (!filePath.equals(symbol.filePath()) || symbol.startLine() <= 0) {
                continue;
            }
            if (isType(symbol.kind())) {
                firstType = Math.min(firstType, symbol.startLine());
            } else {
                firstMember = Math.min(firstMember, symbol.startLine());
            }
        }
        int end = firstMember != Integer.MAX_VALUE ? firstMember - 1
                : (firstType != Integer.MAX_VALUE ? firstType : 1);
        return Math.max(1, Math.min(end, fileLines));
    }

    private static boolean isType(String kind) {
        return switch (kind) {
            case "CLASS", "INTERFACE", "ENUM", "RECORD", "ANNOTATION" -> true;
            default -> false;
        };
    }

    /** 按行切片（1-based，闭区间）。切不出来返回 null，由调用方跳过。 */
    private static String slice(List<String> lines, int startLine, int endLine) {
        if (lines == null || lines.isEmpty() || startLine < 1) {
            return null;
        }
        int from = Math.max(0, startLine - 1);
        int to = Math.min(lines.size(), endLine);
        return from >= to ? null : String.join("\n", lines.subList(from, to));
    }

    /** 粗估 token。代码里 ASCII 占多数，按 4 字符 ≈ 1 token；只用于上下文预算排序，不做精确计费。 */
    private static int estimateTokens(String content) {
        return Math.max(1, content.length() / 4);
    }

    // ------------------------------------------------------------------ 阶段一

    private void collectType(TypeDeclaration<?> type, String enclosingKey, String packageName,
                             String filePath, List<CollectedSymbol> symbols, List<RawCall> rawCalls,
                             Set<String> symbolKeys) {
        String kind = kindOf(type);
        String typeKey = typeKey(enclosingKey, packageName, type.getNameAsString());
        symbolKeys.add(typeKey);
        symbols.add(new CollectedSymbol(
                kind,
                type.getNameAsString(),
                typeKey,
                filePath,
                modifiersOf(type) + " " + kind.toLowerCase() + " " + type.getNameAsString(),
                enclosingKey,
                line(type, true),
                line(type, false),
                modifiersOf(type),
                null,
                javadocOf(type)));

        for (BodyDeclaration<?> member : type.getMembers()) {
            if (member instanceof TypeDeclaration<?> nested) {
                collectType(nested, typeKey, packageName, filePath, symbols, rawCalls, symbolKeys);
            } else if (member instanceof MethodDeclaration method) {
                collectCallable(method, "METHOD", typeKey, method.getNameAsString(),
                        method.getType().asString(), filePath, symbols, rawCalls, symbolKeys);
            } else if (member instanceof ConstructorDeclaration constructor) {
                collectCallable(constructor, "CONSTRUCTOR", typeKey, INIT,
                        null, filePath, symbols, rawCalls, symbolKeys);
            } else if (member instanceof FieldDeclaration field) {
                collectFields(field, typeKey, filePath, symbols);
            }
            // 其余成员（初始化块、注解成员）第一版不建符号 —— 属于已记录的盲区
        }
    }

    private void collectCallable(CallableDeclaration<?> callable, String kind, String typeKey, String name,
                                 String returnType, String filePath, List<CollectedSymbol> symbols,
                                 List<RawCall> rawCalls, Set<String> symbolKeys) {
        String key = methodKey(typeKey, name, callable.getParameters().size());
        symbolKeys.add(key);
        symbols.add(new CollectedSymbol(
                kind,
                name.equals(INIT) ? simpleNameOf(typeKey) : name,
                key,
                filePath,
                clip(modifiersOf(callable) + " " + (returnType == null ? "" : returnType + " ")
                        + name + "(" + parameterList(callable) + ")", 1000),
                typeKey,
                line(callable, true),
                line(callable, false),
                modifiersOf(callable),
                returnType,
                javadocOf(callable)));

        for (MethodCallExpr call : callable.findAll(MethodCallExpr.class)) {
            String callKind = call.getScope().filter(SuperExpr.class::isInstance).isPresent() ? "SUPER" : "METHOD";
            rawCalls.add(new RawCall(call,
                    call.getScope().map(Node::toString).orElse(""),
                    call.getNameAsString(),
                    call.getArguments().size(),
                    line(call, true),
                    key,
                    callKind));
        }
        for (ObjectCreationExpr creation : callable.findAll(ObjectCreationExpr.class)) {
            rawCalls.add(new RawCall(creation, "",
                    creation.getType().getNameAsString(),
                    creation.getArguments().size(),
                    line(creation, true),
                    key,
                    "CONSTRUCTOR"));
        }
    }

    private void collectFields(FieldDeclaration field, String typeKey, String filePath,
                              List<CollectedSymbol> symbols) {
        for (VariableDeclarator variable : field.getVariables()) {
            symbols.add(new CollectedSymbol(
                    "FIELD",
                    variable.getNameAsString(),
                    typeKey + "." + variable.getNameAsString(),
                    filePath,
                    modifiersOf(field) + " " + field.getElementType().asString() + " " + variable.getNameAsString(),
                    typeKey,
                    line(variable, true),
                    line(variable, false),
                    modifiersOf(field),
                    field.getElementType().asString(),
                    javadocOf(field)));
        }
    }

    // ------------------------------------------------------------------ 阶段二

    private CollectedCall resolveCall(RawCall raw, Set<String> symbolKeys) {
        // 链式调用的 scope 原文可能非常长（this.a.b().c().d()），列宽有限，这里统一裁剪；
        // 它是给人看的原文，不是匹配键，裁剪不影响正确性
        String display = clip(raw.scope().isEmpty()
                ? raw.name() + "/" + raw.arity()
                : raw.scope() + "#" + raw.name() + "/" + raw.arity(), 480);
        try {
            if (raw.node() instanceof MethodCallExpr call) {
                ResolvedMethodDeclaration resolved = call.resolve();
                String key = methodKey(resolved.declaringType().getQualifiedName(),
                        resolved.getName(), resolved.getNumberOfParams());
                return edge(raw, display, key, symbolKeys);
            }
            if (raw.node() instanceof ObjectCreationExpr creation) {
                // 走「构造出的类型」而不是 ResolvedConstructorDeclaration：前者 API 稳定，
                // 且我们需要的只是「哪个类的几参构造器」
                ResolvedType type = creation.getType().resolve();
                if (type.isReferenceType()) {
                    String key = methodKey(type.asReferenceType().getQualifiedName(),
                            INIT, creation.getArguments().size());
                    return edge(raw, display, key, symbolKeys);
                }
                return new CollectedCall(raw.callerKey(), null, display, raw.line(), raw.callKind(), false, "UNSOLVED");
            }
        } catch (UnsolvedSymbolException e) {
            return new CollectedCall(raw.callerKey(), null, display, raw.line(), raw.callKind(), false, "UNSOLVED");
        } catch (UnsupportedOperationException e) {
            // 求解器明确表示这类节点解不了（方法引用、部分 lambda 场景）
            return new CollectedCall(raw.callerKey(), null, display, raw.line(), raw.callKind(), false, "DYNAMIC");
        } catch (StackOverflowError e) {
            // 深继承 / 递归泛型会让求解器栈溢出；一个调用点不能拖垮整次索引
            return new CollectedCall(raw.callerKey(), null, display, raw.line(), raw.callKind(), false, "SOLVER_OVERFLOW");
        } catch (LinkageError e) {
            // 反射解析类路径上的类时，若该类引用了缺失的依赖（如 Jackson 的某个内部类型），
            // 会抛 NoClassDefFoundError —— 它是 Error 不是 Exception，漏掉它整次索引就断了。
            // 换用不同语料才暴露出这一点（实测：reggie 外卖项目中触发）。
            return new CollectedCall(raw.callerKey(), null, display, raw.line(), raw.callKind(), false, "MISSING_CLASS");
        } catch (RuntimeException e) {
            return new CollectedCall(raw.callerKey(), null, display, raw.line(), raw.callKind(), false,
                    "ERROR:" + e.getClass().getSimpleName());
        }
        return new CollectedCall(raw.callerKey(), null, display, raw.line(), raw.callKind(), false, "UNKNOWN_NODE");
    }

    private CollectedCall edge(RawCall raw, String display, String calleeKey, Set<String> symbolKeys) {
        if (symbolKeys.contains(calleeKey)) {
            return new CollectedCall(raw.callerKey(), calleeKey, display, raw.line(), raw.callKind(), true, null);
        }
        return new CollectedCall(raw.callerKey(), null, display, raw.line(), raw.callKind(), false, "EXTERNAL");
    }

    private void collectRelations(TypeDeclaration<?> type, String enclosingKey, String packageName,
                                  List<CollectedTypeRelation> out, Set<String> symbolKeys) {
        String typeKey = typeKey(enclosingKey, packageName, type.getNameAsString());

        if (type instanceof ClassOrInterfaceDeclaration declaration) {
            declaration.getExtendedTypes().forEach(t -> addRelation(out, symbolKeys, typeKey, t, "EXTENDS"));
            declaration.getImplementedTypes().forEach(t -> addRelation(out, symbolKeys, typeKey, t, "IMPLEMENTS"));
        } else if (type instanceof EnumDeclaration declaration) {
            declaration.getImplementedTypes().forEach(t -> addRelation(out, symbolKeys, typeKey, t, "IMPLEMENTS"));
        } else if (type instanceof RecordDeclaration declaration) {
            declaration.getImplementedTypes().forEach(t -> addRelation(out, symbolKeys, typeKey, t, "IMPLEMENTS"));
        }

        for (BodyDeclaration<?> member : type.getMembers()) {
            if (member instanceof TypeDeclaration<?> nested) {
                collectRelations(nested, typeKey, packageName, out, symbolKeys);
            }
        }
    }

    private void addRelation(List<CollectedTypeRelation> out, Set<String> symbolKeys,
                             String typeKey, ClassOrInterfaceType superType, String kind) {
        String raw = clip(superType.asString(), 480);
        try {
            ResolvedType resolved = superType.resolve();
            if (resolved.isReferenceType()) {
                String superKey = resolved.asReferenceType().getQualifiedName();
                if (symbolKeys.contains(superKey)) {
                    out.add(new CollectedTypeRelation(typeKey, raw, superKey, kind, true, false));
                    return;
                }
            }
            out.add(new CollectedTypeRelation(typeKey, raw, null, kind, false, true));
        } catch (RuntimeException | StackOverflowError | LinkageError e) {
            // 解不出来也可能只是「不在本仓库」（框架基类），统一按外部处理并留痕。
            // LinkageError 一并接住：反射解析时可能撞到缺失类，同样不该中断整次索引。
            out.add(new CollectedTypeRelation(typeKey, raw, null, kind, false, true));
        }
    }

    // ------------------------------------------------------------------ 工具

    static String methodKey(String typeKey, String name, int arity) {
        return typeKey + "#" + name + "/" + arity;
    }

    /** 按列宽裁剪。超长时截断并标注，避免「静默丢内容」。 */
    static String clip(String text, int max) {
        if (text == null || text.length() <= max) {
            return text;
        }
        return text.substring(0, max - 3) + "...";
    }

    private static String typeKey(String enclosingKey, String packageName, String simpleName) {
        if (enclosingKey != null) {
            return enclosingKey + "." + simpleName;
        }
        return packageName.isEmpty() ? simpleName : packageName + "." + simpleName;
    }

    private static String simpleNameOf(String typeKey) {
        int dot = typeKey.lastIndexOf('.');
        return dot < 0 ? typeKey : typeKey.substring(dot + 1);
    }

    private static String packageOf(CompilationUnit unit) {
        return unit.getPackageDeclaration().map(pd -> pd.getNameAsString()).orElse("");
    }

    private static String kindOf(TypeDeclaration<?> type) {
        if (type instanceof ClassOrInterfaceDeclaration declaration) {
            return declaration.isInterface() ? "INTERFACE" : "CLASS";
        }
        if (type instanceof EnumDeclaration) {
            return "ENUM";
        }
        if (type instanceof RecordDeclaration) {
            return "RECORD";
        }
        return "ANNOTATION";
    }

    /** 参数类型必须是 NodeWithModifiers：BodyDeclaration 本身不提供 getModifiers()。 */
    private static String modifiersOf(NodeWithModifiers<?> declaration) {
        StringJoiner joiner = new StringJoiner(" ");
        declaration.getModifiers().forEach(m -> joiner.add(m.getKeyword().asString()));
        return joiner.toString();
    }

    private static String javadocOf(NodeWithJavadoc<?> node) {
        return node.getJavadocComment()
                .map(comment -> {
                    String text = comment.getContent().strip();
                    return text.length() <= JAVADOC_MAX ? text : text.substring(0, JAVADOC_MAX) + "...";
                })
                .orElse(null);
    }

    private static String parameterList(CallableDeclaration<?> callable) {
        StringJoiner joiner = new StringJoiner(", ");
        callable.getParameters().forEach(p -> joiner.add(p.getType().asString()));
        return joiner.toString();
    }

    private static int line(Node node, boolean begin) {
        return (begin ? node.getBegin() : node.getEnd()).map(p -> p.line).orElse(0);
    }

    private static String sha256(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JDK 必须提供 SHA-256", e);
        }
    }

    // ------------------------------------------------------------------ 内部载体

    /** 解析成功的文件 + 它的调用点。调用点留到第二阶段解析，所以这里要一并带着。 */
    private static final class ParsedFile {

        private final String relativePath;
        private final CompilationUnit unit;
        private final List<RawCall> calls = new ArrayList<>();

        private ParsedFile(String relativePath, CompilationUnit unit) {
            this.relativePath = relativePath;
            this.unit = unit;
        }

        private CompilationUnit unit() {
            return unit;
        }

        private List<RawCall> rawCalls() {
            return calls;
        }

        @SuppressWarnings("unused")
        private String relativePath() {
            return relativePath;
        }
    }
    private record RawCall(Node node, String scope, String name, int arity, int line,
                           String callerKey, String callKind) {
    }
}

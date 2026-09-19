package com.readcodeai.summary;

import com.readcodeai.retrieve.SymbolQueryService;
import com.readcodeai.retrieve.model.RepoView;
import com.readcodeai.summary.SummaryRepository.FileScale;
import com.readcodeai.summary.SummaryRepository.ScaleVitals;
import com.readcodeai.summary.model.RepoSummary;
import com.readcodeai.summary.model.RepoSummary.Module;
import com.readcodeai.summary.model.RepoSummary.Scale;
import com.readcodeai.summary.model.RepoSummary.SymbolRef;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * 结构化摘要：**能算的部分全部算出来，剩下那一点才交给模型**。
 *
 * <p>「给它一个仓库，它先告诉我这仓库是干什么的」——这件事如果做成"把文件塞给模型让它读"，
 * 那就退化成普通 AI 问答了，白白丢掉前面五步攒下的底座。所以这里的分工是硬的：
 *
 * <table>
 *   <tr><th>部分</th><th>来源</th><th>可核对性</th></tr>
 *   <tr><td>模块划分 / 规模 / 入口 / 枢纽 / 实现关系 / 无调用者的类</td><td>索引（查表与图查询）</td><td>每个数字、每个符号都能点开看代码</td></tr>
 *   <tr><td>每个模块"大致负责什么"</td><td>模型</td><td>它提到的符号名要回索引里核对；编的名字会被标出来</td></tr>
 * </table>
 *
 * <p>模块划分的口径：先求所有包的**公共前缀**（通常是 {@code com.company.product}），
 * 再看前缀之后的第一段 —— 那一段就是模块名。这样既不会被公司域名淹没，
 * 也不会把一个包拆成十几层。
 */
@Service
public class RepoSummaryService {

    private static final Logger log = LoggerFactory.getLogger(RepoSummaryService.class);

    /** 排行榜取前几名：摘要是给人一眼看懂的，列 50 个枢纽等于没列。 */
    private static final int TOP_N = 10;

    private static final int MODULE_KEY_TYPES = 6;

    private static final int MODULE_SAMPLE_PATHS = 5;

    private static final int MAX_MODULES = 40;

    /**
     * 一条划分线分不出这么多模块时，就把前缀再加深一段。
     *
     * <p>为什么需要它：真实仓库的包名第一段往往是纯域名（{@code com}/{@code io}/{@code org}），
     * 按它划分只会得到"google、example"这种对读者毫无信息量的模块名。
     * 加深到 {@code com.google.gson} 之后才是人真正想要的划分（internal / stream / reflect / …）。
     */
    private static final int MIN_USEFUL_MODULES = 3;

    private final SymbolQueryService queries;
    private final SummaryRepository repository;
    private final SemanticSummarizer semanticSummarizer;

    public RepoSummaryService(SymbolQueryService queries, SummaryRepository repository,
                              SemanticSummarizer semanticSummarizer) {
        this.queries = queries;
        this.repository = repository;
        this.semanticSummarizer = semanticSummarizer;
    }

    /**
     * @param withSemantics 是否让模型补一句语义说明。**关掉它结构部分照样完整** ——
     *                      没配 Key、或不想花 token 时都能用，这是可降级设计在这一步的体现
     */
    public RepoSummary summarize(Long repoId, boolean withSemantics) {
        long effectiveRepoId = repoId != null ? repoId : queries.requireLatestRepoId();
        RepoView repo = queries.requireRepo(effectiveRepoId);

        RepoSummary.Structure structure = buildStructure(repo);
        RepoSummary.Semantics semantics = withSemantics
                ? semanticSummarizer.describe(repo, structure)
                : RepoSummary.noSemantics("本次请求未要求语义说明（structure 部分不受影响）");
        return new RepoSummary(repo.id(), repo.name(), repo.rootPath(), repo.commitHash(),
                repo.indexedAt(), structure, semantics);
    }

    RepoSummary.Structure buildStructure(RepoView repo) {
        List<FileScale> files = repository.fileScales(repo.id());
        ScaleVitals vitals = repository.vitals(repo.id());

        Scale scale = new Scale(
                repo.fileCount(), repo.parsedOkCount(), repo.parseSuccessRate(), repo.totalLoc(),
                vitals.classCount(), vitals.interfaceCount(), vitals.methodCount(),
                vitals.totalSymbols(), repo.callEdgeCount(), repo.callResolvedCount(),
                repo.callResolveRate());

        Modules partitioned = buildModules(repo.id(), files);
        log.info("摘要结构：{} 个模块（前缀 {}）· {} 个文件 · {} 个符号",
                partitioned.modules().size(), partitioned.prefix(), repo.fileCount(), vitals.totalSymbols());

        return new RepoSummary.Structure(scale, partitioned.prefix(), partitioned.modules(),
                repository.entryPoints(repo.id(), TOP_N),
                repository.callHubs(repo.id(), TOP_N),
                repository.topMethods(repo.id(), TOP_N),
                repository.implementations(repo.id(), TOP_N),
                repository.uncalledClasses(repo.id(), TOP_N));
    }

    /** 模块划分的产物：**用哪个前缀切的** + 切出来的模块。前缀要一起返回，否则界面上没法说明划分口径。 */
    record Modules(String prefix, List<Module> modules) {
    }

    private Modules buildModules(long repoId, List<FileScale> files) {
        // 文件 → 所属包（取它顶层类型的包名；没有顶层类型的文件归到 null）
        Map<String, String> packageByPath = new LinkedHashMap<>();
        Set<String> packages = new TreeSet<>();
        for (FileScale file : files) {
            String pkg = packageOf(file.typeQualifiedName());
            packageByPath.put(file.path(), pkg);
            if (pkg != null) {
                packages.add(pkg);
            }
        }
        String commonPrefix = choosePrefix(packages);

        // 包 → 模块名
        Map<String, String> moduleByPackage = new LinkedHashMap<>();
        for (String pkg : packages) {
            moduleByPackage.put(pkg, moduleName(pkg, commonPrefix));
        }

        Map<String, ModuleAccumulator> accumulators = new LinkedHashMap<>();
        for (FileScale file : files) {
            String pkg = packageByPath.get(file.path());
            String module = pkg == null ? "(没有顶层类型)" : moduleByPackage.get(pkg);
            ModuleAccumulator accumulator = accumulators.computeIfAbsent(module,
                    name -> new ModuleAccumulator(name, pkg));
            accumulator.add(file);
        }

        List<Module> modules = new ArrayList<>();
        for (ModuleAccumulator accumulator : accumulators.values()) {
            modules.add(accumulator.toModule(repoId, commonPrefix));
        }
        modules.sort(Comparator.comparingInt(Module::symbolCount).reversed());
        List<Module> capped = modules.size() <= MAX_MODULES ? modules : modules.subList(0, MAX_MODULES);
        return new Modules(commonPrefix, capped);
    }

    /** 顶层类型 {@code com.a.B.C} 的包是 {@code com.a.B}；没有类型则返回 null。 */
    static String packageOf(String typeQualifiedName) {
        if (typeQualifiedName == null) {
            return null;
        }
        int lastDot = typeQualifiedName.lastIndexOf('.');
        return lastDot < 0 ? "" : typeQualifiedName.substring(0, lastDot);
    }

    /** 所有包的公共前缀（按段比较）。一个包都没有时返回空串。 */
    static String commonPackagePrefix(Set<String> packages) {
        if (packages.isEmpty()) {
            return "";
        }
        String[] shared = packages.iterator().next().split("\\.");
        int sharedLength = shared.length;
        for (String pkg : packages) {
            String[] segments = pkg.split("\\.");
            int i = 0;
            while (i < sharedLength && i < segments.length && shared[i].equals(segments[i])) {
                i++;
            }
            sharedLength = i;
            if (sharedLength == 0) {
                return "";
            }
        }
        return String.join(".", java.util.Arrays.copyOfRange(shared, 0, sharedLength));
    }

    /**
     * 挑选划分前缀：先取所有包的公共前缀；如果这样分出来的模块太少，就沿"出现次数最多的下一段"逐层加深。
     *
     * <p>规则是确定性的（次数相同时按字典序取），所以同一份代码每次算出来一样 —— 摘要要可复现。
     */
    static String choosePrefix(Set<String> packages) {
        String prefix = commonPackagePrefix(packages);
        while (distinctModuleCount(packages, prefix) < MIN_USEFUL_MODULES) {
            String deeper = extendPrefix(packages, prefix);
            if (deeper == null) {
                break;
            }
            prefix = deeper;
        }
        return prefix;
    }

    static int distinctModuleCount(Set<String> packages, String prefix) {
        return (int) packages.stream().map(pkg -> moduleName(pkg, prefix)).distinct().count();
    }

    /** 在当前前缀之下取「下一个包段」里出现次数最多的那个；已经到底了返回 null。 */
    static String extendPrefix(Set<String> packages, String prefix) {
        Map<String, Integer> counts = new java.util.HashMap<>();
        for (String pkg : packages) {
            if (!prefix.isEmpty() && !pkg.startsWith(prefix + ".")) {
                continue;
            }
            String rest = prefix.isEmpty() ? pkg : pkg.substring(prefix.length() + 1);
            int dot = rest.indexOf('.');
            if (dot < 0) {
                continue;
            }
            counts.merge(rest.substring(0, dot), 1, Integer::sum);
        }
        if (counts.isEmpty()) {
            return null;
        }
        String best = counts.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed()
                        .thenComparing(Map.Entry.comparingByKey()))
                .map(Map.Entry::getKey)
                .findFirst()
                .orElse(null);
        return prefix.isEmpty() ? best : prefix + "." + best;
    }

    /**
     * 模块名 = 公共前缀之后的第一段。
     *
     * <p>三种情况都要说清楚，否则"模块"会静默丢掉一片代码：
     * 包与前缀相同 → {@code (根包)}；在前缀之下 → 那一段的名字；
     * 与前缀无关（如示例代码 {@code com.example}）→ 单独归成一类，名字里带上包名，别混进正常模块。
     */
    static String moduleName(String pkg, String commonPrefix) {
        if (commonPrefix.isEmpty()) {
            int dot = pkg.indexOf('.');
            return dot > 0 ? pkg.substring(0, dot) : pkg;
        }
        if (pkg.equals(commonPrefix)) {
            return "(根包)";
        }
        if (pkg.startsWith(commonPrefix + ".")) {
            String rest = pkg.substring(commonPrefix.length() + 1);
            int dot = rest.indexOf('.');
            return dot > 0 ? rest.substring(0, dot) : rest;
        }
        return "(其他包) " + pkg;
    }

    /** 模块内代表类型的匹配正则：根包只要本包，其余模块要整棵子树。 */
    static String keyTypeRegex(String modulePackage, String commonPrefix) {
        if (modulePackage == null || modulePackage.startsWith("(其他包)")) {
            return null;
        }
        String escaped = modulePackage.replace(".", "[.]");
        return modulePackage.equals(commonPrefix)
                ? "^" + escaped + "[.][A-Za-z0-9_$]+$"
                : "^" + escaped + "[.].+$";
    }

    /** 攒一个模块的规模与样例文件。 */
    private final class ModuleAccumulator {

        private final String name;
        private final String packagePrefix;
        private int fileCount;
        private int totalLoc;
        private int symbolCount;
        private final List<String> samplePaths = new ArrayList<>();

        private ModuleAccumulator(String name, String packagePrefix) {
            this.name = name;
            this.packagePrefix = packagePrefix;
        }

        private void add(FileScale file) {
            fileCount++;
            totalLoc += file.loc();
            symbolCount += file.symbolCount();
            if (samplePaths.size() < MODULE_SAMPLE_PATHS && !samplePaths.contains(file.path())) {
                samplePaths.add(file.path());
            }
        }

        private Module toModule(long repoId, String commonPrefix) {
            List<SymbolRef> keyTypes = repository.keyTypes(repoId,
                    keyTypeRegex(packagePrefix, commonPrefix), MODULE_KEY_TYPES);
            return new Module(name, packagePrefix == null ? "" : packagePrefix,
                    fileCount, totalLoc, symbolCount, keyTypes, List.copyOf(samplePaths));
        }
    }
}

package com.readcodeai.summary;

import com.readcodeai.index.store.SymbolQueryRepository;
import com.readcodeai.index.store.SymbolQueryRepository.NamedType;
import com.readcodeai.retrieve.FileContentService;
import com.readcodeai.retrieve.model.RepoView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 「这个项目是做什么的」这句话的**材料**：全部从索引与源码里算出来。
 *
 * <h3>为什么不是把源码丢给模型让它自己总结</h3>
 * 模型看几百个文件也答不好"这项目是做什么的"——它需要的是**业务对象的名字**与**对外接口的名字**，
 * 而这两样恰恰是能从索引里精确取出来的：
 * <ul>
 *   <li><b>业务对象</b>：{@code entity/domain/model/po/dto/vo} 这些包下的类名，
 *       它们就是这个系统在管的东西（菜品、订单、购物车、骑手……）</li>
 *   <li><b>对外接口</b>：控制器类源码里的 {@code @GetMapping("/page")} 这类路径，
 *       它们说明系统对外提供什么能力</li>
 *   <li><b>调用枢纽与入口</b>：哪几个类是核心、程序从哪里启动</li>
 * </ul>
 *
 * <p>模型在这件事上的职责被压到最小：**把上面这些名字组织成一句人话**（不许写数字、不许编名字）。
 * 这与摘要的结构部分是同一条纪律：能算准的算，模型只负责表达。
 */
@Component
public class ProjectMaterialBuilder {

    private static final Logger log = LoggerFactory.getLogger(ProjectMaterialBuilder.class);

    /** 业务对象/领域类型的包名特征 —— 各家写法不一，常见几种都收进来。 */
    private static final List<String> DOMAIN_PACKAGE_HINTS =
            List.of(".entity.", ".domain.", ".model.", ".po.", ".dto.", ".vo.");

    private static final int MAX_DOMAIN_TYPES = 30;

    private static final int MAX_CONTROLLERS = 20;

    private static final int MAX_PATHS = 30;

    /** {@code @GetMapping("/page")} / {@code @RequestMapping(value = "/dish")} 两种写法都要认。 */
    private static final Pattern MAPPING = Pattern.compile(
            "@(?:Get|Post|Put|Delete|Patch|Request)Mapping\\s*\\(\\s*(?:value\\s*=\\s*)?\"([^\"]+)\"");

    private final SymbolQueryRepository symbols;
    private final FileContentService fileContentService;

    public ProjectMaterialBuilder(SymbolQueryRepository symbols, FileContentService fileContentService) {
        this.symbols = symbols;
        this.fileContentService = fileContentService;
    }

    /**
     * @param domainTypes 业务对象类名（菜品、订单、购物车…）
     * @param controllers 对外接口所在的类名
     * @param paths       接口路径样例（来自控制器源码的注解）
     * @param hubs        被调用最多的类名（系统的核心零件）
     */
    public record Material(List<String> domainTypes, List<String> controllers, List<String> paths,
                           List<String> hubs, List<String> entryPoints) {

        public boolean isEmpty() {
            return domainTypes.isEmpty() && controllers.isEmpty() && paths.isEmpty()
                    && hubs.isEmpty() && entryPoints.isEmpty();
        }

        /** 给模型看的材料文本。 */
        public String describe() {
            StringBuilder text = new StringBuilder();
            text.append("业务对象（这个系统在管的东西）：").append(orNone(domainTypes)).append('\n');
            text.append("对外接口所在的类：").append(orNone(controllers)).append('\n');
            text.append("接口路径样例：").append(orNone(paths)).append('\n');
            text.append("被调用最多的类（核心零件）：").append(orNone(hubs)).append('\n');
            text.append("程序入口：").append(orNone(entryPoints)).append('\n');
            return text.toString();
        }

        private static String orNone(List<String> values) {
            return values.isEmpty() ? "（没有取到）" : String.join("、", values);
        }
    }

    public Material build(RepoView repo, List<com.readcodeai.summary.model.RepoSummary.Ranked> hubs,
                          List<com.readcodeai.summary.model.RepoSummary.SymbolRef> entryPoints,
                          String modulePrefix) {
        List<NamedType> types = symbols.topLevelTypes(repo.id(), 4000);

        List<String> domainTypes = types.stream()
                .filter(type -> DOMAIN_PACKAGE_HINTS.stream().anyMatch(hint -> type.qualifiedName().contains(hint)))
                .map(NamedType::name)
                .distinct()
                .limit(MAX_DOMAIN_TYPES)
                .toList();
        if (domainTypes.isEmpty() && modulePrefix != null && !modulePrefix.isBlank()) {
            // 兜底：**库项目**（gson、工具库）没有 entity/controller 包，业务名词就藏在根包里
            // （Gson、JsonReader、JsonWriter…）。没有这条兜底，模型拿到的材料会是空的，
            // 它就只能瞎猜"这个项目是做什么的"——那正是我们要避免的。
            domainTypes = types.stream()
                    .filter(type -> type.qualifiedName().startsWith(modulePrefix + "."))
                    .filter(type -> type.qualifiedName().substring(modulePrefix.length() + 1).indexOf('.') < 0)
                    .map(NamedType::name)
                    .distinct()
                    .limit(MAX_DOMAIN_TYPES)
                    .toList();
            log.info("没有 entity/domain 包（像是库项目）：改用根包 {} 下的 {} 个类型当主要概念",
                    modulePrefix, domainTypes.size());
        }

        List<NamedType> controllerTypes = types.stream()
                .filter(type -> type.name().endsWith("Controller")
                        || type.qualifiedName().contains(".controller."))
                .limit(MAX_CONTROLLERS)
                .toList();

        Set<String> paths = new LinkedHashSet<>();
        for (NamedType controller : controllerTypes) {
            if (paths.size() >= MAX_PATHS) {
                break;
            }
            paths.addAll(extractPaths(repo, controller));
        }

        Material material = new Material(domainTypes,
                controllerTypes.stream().map(NamedType::name).distinct().toList(),
                paths.stream().limit(MAX_PATHS).toList(),
                hubs.stream().map(hub -> hub.symbol().qualifiedName()).limit(8).toList(),
                entryPoints.stream().map(com.readcodeai.summary.model.RepoSummary.SymbolRef::qualifiedName)
                        .limit(5).toList());

        log.info("项目材料：业务对象 {} 个 · 控制器 {} 个 · 接口路径 {} 条（用于生成「这个项目是做什么的」）",
                material.domainTypes().size(), material.controllers().size(), material.paths().size());
        return material;
    }

    /** 从控制器源码里抽接口路径；读不到就跳过（材料少一条不影响整体）。 */
    private List<String> extractPaths(RepoView repo, NamedType controller) {
        List<String> paths = new ArrayList<>();
        try {
            FileContentService.FileContent content = fileContentService.read(repo.id(),
                    controller.filePath(), controller.startLine(), controller.endLine());
            StringBuilder source = new StringBuilder();
            content.lines().forEach(line -> source.append(line.text()).append('\n'));
            Matcher matcher = MAPPING.matcher(source);
            while (matcher.find() && paths.size() < MAX_PATHS) {
                paths.add(matcher.group(1));
            }
        } catch (RuntimeException e) {
            log.debug("抽取接口路径失败（跳过这个类）：{} · {}", controller.qualifiedName(), e.getMessage());
        }
        return paths;
    }
}

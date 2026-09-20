package com.enterprise.cs;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

/**
 * 架构守护六规则——《02-模块划分与工程结构》§6 的可执行形态（M0批1 落地，CI 强制，违规即红）。
 * 任何豁免必须先回写《02》§6 豁免清单（当前为空），禁止就地注释绕过。
 */
@AnalyzeClasses(packages = "com.enterprise.cs", importOptions = ImportOption.DoNotIncludeTests.class)
final class ArchitectureTests {

    /** 限界上下文包名（cs-api 自身类位于根包，组装例外，不入矩阵，《02》§3）。 */
    private static final List<String> MODULES = List.of(
            "commons", "infra", "ai", "conversation", "knowledge", "tooling",
            "orchestration", "collaboration", "channel", "eval", "admin");

    /**
     * 模块依赖白名单（《02》§4 允许的箭头）。
     * 注：conversation→orchestration 为《03》§3.1 主链路时序的触发边（会话持久化后触发编排）。
     */
    private static final Map<String, Set<String>> ALLOWED_DEPENDENCIES = Map.ofEntries(
            Map.entry("commons", Set.of()),
            Map.entry("infra", Set.of("commons")),
            Map.entry("ai", Set.of("commons", "infra")),
            Map.entry("conversation", Set.of("commons", "infra", "orchestration")),
            Map.entry("knowledge", Set.of("commons", "infra", "ai")),
            Map.entry("tooling", Set.of("commons", "infra", "ai")),
            Map.entry("orchestration", Set.of("commons", "infra", "ai", "knowledge", "tooling", "collaboration")),
            Map.entry("collaboration", Set.of("commons", "infra", "conversation")),
            Map.entry("channel", Set.of("commons", "infra", "conversation", "orchestration")),
            Map.entry("eval", Set.of("commons", "infra", "orchestration", "knowledge")),
            Map.entry("admin", Set.of(
                    "commons", "infra", "ai", "conversation", "knowledge", "tooling",
                    "orchestration", "collaboration", "channel", "eval")));

    /** R1：模块级依赖白名单——非白名单模块间零依赖。 */
    @ArchTest
    static void r1_moduleDependencyWhitelist(JavaClasses classes) {
        for (String module : MODULES) {
            String[] denied = MODULES.stream()
                    .filter(m -> !m.equals(module) && !ALLOWED_DEPENDENCIES.get(module).contains(m))
                    .map(m -> "..cs." + m + "..")
                    .toArray(String[]::new);
            if (denied.length == 0) {
                continue;
            }
            noClasses().that().resideInAPackage("..cs." + module + "..")
                    .should().dependOnClassesThat().resideInAnyPackage(denied)
                    .because("模块依赖白名单（《02》§4）：cs-" + module)
                    .check(classes);
        }
    }

    /** R2：跨模块只可依赖对方 api 包（domain/app/infra 一律禁止）。 */
    @ArchTest
    static void r2_crossModuleOnlyViaApi(JavaClasses classes) {
        for (String src : MODULES) {
            for (String dst : MODULES) {
                if (src.equals(dst)) {
                    continue;
                }
                noClasses().that().resideInAPackage("..cs." + src + "..")
                        .should().dependOnClassesThat()
                        .resideInAnyPackage(
                                "..cs." + dst + ".domain..",
                                "..cs." + dst + ".app..",
                                "..cs." + dst + ".infra..")
                        .because("跨模块仅可依赖 api 包（《02》§3）：cs-" + src + " → cs-" + dst)
                        .check(classes);
            }
        }
    }

    /** R3：api 包只放契约——不出现 JPA 实体/仓储。骨架期 api 包尚无类，空规则合法（护栏随代码生长生效）。 */
    @ArchTest
    static final ArchRule r3_apiPackagePurity = noClasses()
            .that().resideInAPackage("..cs..api..")
            .should().beAnnotatedWith("jakarta.persistence.Entity")
            .orShould().beAnnotatedWith("jakarta.persistence.Table")
            .orShould().beAssignableTo("org.springframework.data.repository.Repository")
            .because("api 包为对外契约，禁止 JPA 实体与仓储（《02》§3 R3）")
            .allowEmptyShould(true);

    /** R4：模块间禁止循环依赖。 */
    @ArchTest
    static final ArchRule r4_noCyclesBetweenModules = slices()
            .matching("com.enterprise.cs.(*)..")
            .should().beFreeOfCycles()
            .because("模块间禁止循环依赖（《02》§3 R4）");

    /** R5：JPA 实体只存在于声明模块的 domain 包。 */
    @ArchTest
    static final ArchRule r5_entitiesOnlyInDomain = noClasses()
            .that().resideOutsideOfPackage("..cs..domain..")
            .should().beAnnotatedWith("jakarta.persistence.Entity")
            .orShould().beAnnotatedWith("jakarta.persistence.Table")
            .because("JPA 实体只存在于声明模块的 domain 包（《02》§3 R5）");

    /** R6：应用服务层（app 包）禁止直接使用 HTTP 客户端，须经 cs-infra 适配。骨架期 app 包尚无类，空规则合法。 */
    @ArchTest
    static final ArchRule r6_appLayerNoDirectHttpClients = noClasses()
            .that().resideInAPackage("..cs..app..")
            .should().dependOnClassesThat()
            .resideInAnyPackage(
                    "org.springframework.web.client..",
                    "org.springframework.web.reactive.function.client..")
            .because("应用服务层不得直接使用 HTTP 客户端，须经 cs-infra 适配（《02》§3 R6）")
            .allowEmptyShould(true);

    private ArchitectureTests() {
    }
}

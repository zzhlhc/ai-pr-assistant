package com.zhouziheng.review.context;

import com.zhouziheng.gitee.GiteeClient;
import com.zhouziheng.review.ReviewOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 召回本次改动"需要看定义"的相关代码。
 * <p>
 * 这一步解决的是最原始的问题：diff 每个 hunk 只带 3 行上下文，
 * 模型看不到 {@code FeedbackProcessingTeam} 里 {@code members} 字段有没有初始化，
 * 也就判断不出 {@code team.getMembers().stream()} 会不会 NPE。
 * <p>
 * 但也不能把整个仓库塞进去 —— 实测这个仓库 4881 个 Java 文件，必然撑爆上下文窗口。
 * 所以核心是<b>精准 + 有预算</b>：符号索引精确定位，骨架压缩体积，
 * 召回数量和字符预算由 {@link ReviewOptions} 从外部传入，便于对比不同参数下的效果。
 */
@Component
public class CodeContextRecaller {

    private static final Logger log = LoggerFactory.getLogger(CodeContextRecaller.class);

    /** 从 identifiers 通道最多定出多少个候选文件；上限放在"查到索引之后"才生效 */
    private static final int MAX_IDENTIFIER_CANDIDATES = 30;

    private final GiteeClient gitee;
    private final RepoFileIndexer indexer;
    private final JavaSkeletonizer skeletonizer;

    public CodeContextRecaller(GiteeClient gitee, RepoFileIndexer indexer, JavaSkeletonizer skeletonizer) {
        this.gitee = gitee;
        this.indexer = indexer;
        this.skeletonizer = skeletonizer;
    }

    /**
     * @return 相关代码，按优先级排序；任何一步出问题都返回空列表 ——
     * 召回是"锦上添花"，绝不能让它的失败拖垮整个评审
     */
    public List<CodeContext> recall(String owner, String repo, String sha,
                                    ReferencedSymbols symbols, ReviewOptions options) {
        return recallDetailed(owner, repo, sha, symbols, options).contexts();
    }

    /** 跟 recall 一样，只是额外带出过程指标（候选数、耗时），供预览接口展示 */
    public RecallResult recallDetailed(String owner, String repo, String sha,
                                       ReferencedSymbols symbols, ReviewOptions options) {
        if (symbols.isEmpty()) {
            return new RecallResult(0, List.of(), 0);
        }
        long start = System.currentTimeMillis();
        try {
            return doRecall(owner, repo, sha, symbols, options, start);
        } catch (Exception e) {
            log.warn("代码上下文召回失败，本次按纯 diff 评审 | repo={}/{} | {}", owner, repo, e.getMessage());
            return new RecallResult(0, List.of(), System.currentTimeMillis() - start);
        }
    }

    private RecallResult doRecall(String owner, String repo, String sha,
                                  ReferencedSymbols symbols, ReviewOptions options, long start) {
        RepoFileIndex index = indexer.indexOf(owner, repo, sha);
        List<String> candidates = resolvePaths(index, symbols);
        if (log.isDebugEnabled()) {
            log.debug("候选文件 | 数量={} | {}", candidates.size(), candidates);
        }

        int maxFiles = options.maxFilesOrDefault();
        int budget = options.totalBudgetOrDefault();

        List<CodeContext> contexts = new ArrayList<>();
        int rawChars = 0;
        int fetched = 0;

        for (String path : candidates) {
            if (contexts.size() >= maxFiles || budget <= 0) {
                break;
            }
            fetched++;
            String source = gitee.getFileContent(owner, repo, path, sha);
            if (source == null) {
                continue;
            }
            String skeleton = skeletonizer.skeletonize(source);
            if (skeleton.isBlank() || skeleton.length() > budget) {
                continue;
            }
            budget -= skeleton.length();
            rawChars += source.length();
            contexts.add(new CodeContext(path, skeleton, source.length()));
        }

        log.info("代码上下文召回 | 参数={} | 候选文件={} | 拉取文件={} | 实际召回={} | 骨架字符={} | 原始字符={}",
                options.signature(), candidates.size(), fetched, contexts.size(),
                options.totalBudgetOrDefault() - budget, rawChars);
        for (CodeContext context : contexts) {
            log.info("  召回 {}（{} 字符 → {} 压缩率）", context.path(),
                    context.skeleton().length(), RecalledFile.of(context).percent());
        }
        return new RecallResult(candidates.size(), contexts, System.currentTimeMillis() - start);
    }

    /**
     * 把符号解析成文件路径。
     * <p>
     * 顺序就是优先级：identifiers 按 TF-IDF 权重降序，之后才轮到 import
     * 和从被改动文件 import 清单里补出来的依赖（那两个通道给的是全限定名，本身就是精确的）。
     * <p>
     * <b>先过滤、再排序</b>，顺序不能反。符号权重是在"整个 diff 出现过的所有词"上算出来的，
     * 里面混着 String、List、Override 这类每个文件都在用的语法词，它们词频高、权重看起来不低，
     * 但仓库里根本没有对应的类。先排序的话，排名前列会被这些词占满，
     * 真正存在的类被挤到看不到的位置；先过滤再排，参评的就只剩仓库里真实存在的类，
     * 权重才是在同一条起跑线上比较。
     */
    private List<String> resolvePaths(RepoFileIndex index, ReferencedSymbols symbols) {
        Set<String> paths = new LinkedHashSet<>();

        List<ReferencedSymbols.Symbol> ranked = symbols.identifiers().stream()
                .filter(symbol -> index.resolve(symbol.name()) != null)
                .sorted(Comparator.comparingDouble(ReferencedSymbols.Symbol::weight).reversed()
                        .thenComparing(ReferencedSymbols.Symbol::name))
                .limit(MAX_IDENTIFIER_CANDIDATES)
                .toList();
        if (log.isDebugEnabled()) {
            log.debug("identifiers 通道排序（已剔除仓库里不存在的符号）| {}", ranked.stream()
                    .map(symbol -> "%s(%.1f)".formatted(symbol.name(), symbol.weight()))
                    .toList());
        }
        ranked.forEach(symbol -> paths.add(index.resolve(symbol.name())));

        for (String fqn : symbols.imports()) {
            String path = index.resolveFqn(fqn);
            if (path != null) {
                paths.add(path);
            }
        }
        return List.copyOf(paths);
    }
}

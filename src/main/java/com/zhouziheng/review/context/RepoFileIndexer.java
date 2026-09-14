package com.zhouziheng.review.context;

import com.zhouziheng.gitee.GiteeClient;
import com.zhouziheng.gitee.dto.GiteeTree;
import com.zhouziheng.gitee.dto.GiteeTreeNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 构建并缓存 {@link RepoFileIndex}。
 * <p>
 * 一次 git/trees 请求（本项目实测 7451 个路径）就能换回整个仓库的路径清单，
 * 剩下的全是字符串处理，不花一分钱、不调一次模型。
 * 这就是为什么代码场景的"检索"可以完全不依赖 embedding：
 * <b>符号是精确的，不需要语义相似度</b>。
 */
@Component
public class RepoFileIndexer {

    private static final Logger log = LoggerFactory.getLogger(RepoFileIndexer.class);

    /** Java 源码的包根标志。取 indexOf 而不是 startsWith，是为了兼容多模块（模块名/src/main/java/...） */
    private static final String PACKAGE_ROOT = "src/main/java/";

    private final GiteeClient gitee;
    private final Map<String, RepoFileIndex> cache = new ConcurrentHashMap<>();

    public RepoFileIndexer(GiteeClient gitee) {
        this.gitee = gitee;
    }

    /**
     * 按仓库缓存索引。
     * <p>
     * 刻意不按 commit sha 缓存：文件树在不同 commit 之间路径几乎不变，
     * 而按 sha 缓存意味着同一个仓库要重复建 N 份索引。
     * 代价是新建的文件在索引刷新前找不到，对这个场景可以接受。
     * <p>
     * 也刻意不用 computeIfAbsent —— 它的 lambda 执行期间会锁住哈希桶，
     * 里面做 HTTP 调用会把其他仓库的索引查询一起阻塞住。重复构建一次的成本远低于这个风险。
     */
    public RepoFileIndex indexOf(String owner, String repo, String sha) {
        String key = owner + "/" + repo;
        RepoFileIndex cached = cache.get(key);
        if (cached != null) {
            return cached;
        }
        RepoFileIndex built = build(owner, repo, sha);
        cache.putIfAbsent(key, built);
        return built;
    }

    private RepoFileIndex build(String owner, String repo, String sha) {
        GiteeTree tree = gitee.getTree(owner, repo, sha);
        if (tree == null || tree.tree() == null) {
            log.warn("拉取文件树失败，本次不做代码召回 | repo={}/{}", owner, repo);
            return RepoFileIndex.empty();
        }

        Map<String, String> byPackagePath = new HashMap<>();
        Map<String, String> bySimpleName = new HashMap<>();

        for (GiteeTreeNode node : tree.tree()) {
            String path = node.path();
            if (!"blob".equals(node.type()) || !path.endsWith(".java")) {
                continue;
            }

            int root = path.indexOf(PACKAGE_ROOT);
            if (root >= 0) {
                byPackagePath.putIfAbsent(path.substring(root + PACKAGE_ROOT.length()), path);
            }

            String fileName = path.substring(path.lastIndexOf('/') + 1);
            String simpleName = fileName.substring(0, fileName.length() - ".java".length());
            bySimpleName.merge(simpleName, path, (a, b) -> a.length() <= b.length() ? a : b);
        }

        if (Boolean.TRUE.equals(tree.truncated())) {
            log.warn("文件树被 Gitee 截断，索引不完整 | repo={}/{} | 已索引={}", owner, repo, bySimpleName.size());
        }
        log.info("仓库索引构建完成 | repo={}/{} | java 文件={} | 全限定名={}",
                owner, repo, bySimpleName.size(), byPackagePath.size());

        return new RepoFileIndex(byPackagePath, bySimpleName);
    }

}

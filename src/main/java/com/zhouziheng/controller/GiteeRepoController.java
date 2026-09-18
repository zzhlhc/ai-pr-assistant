package com.zhouziheng.controller;

import com.zhouziheng.gitee.GiteeClient;
import com.zhouziheng.gitee.dto.GiteeCommitSummary;
import com.zhouziheng.review.catalog.RepoCatalogRepository;
import com.zhouziheng.review.catalog.RepoOption;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClientException;

import java.util.List;

/**
 * 页面上那两个下拉框的数据源：先选仓库，再列它最近的 commit。
 * <p>
 * 仓库清单是实时拉的（认证接口 /user/repos），所以在 Gitee 上 fork 一个项目，
 * 刷新页面就能选到它；只有 commit 列表仍然是按仓库现拉的。
 */
@RestController
@RequestMapping("/api/gitee")
public class GiteeRepoController {

    /** 下拉框里给出的 commit 数量 */
    private static final int COMMIT_LIMIT = 30;

    private static final Logger log = LoggerFactory.getLogger(GiteeRepoController.class);

    private final GiteeClient gitee;
    private final RepoCatalogRepository catalog;

    public GiteeRepoController(GiteeClient gitee, RepoCatalogRepository catalog) {
        this.gitee = gitee;
        this.catalog = catalog;
    }

    /**
     * 当前令牌能访问的仓库。
     * <p>
     * 拉不到（没配令牌、令牌失效、网络不通）就退回库里的预置清单，
     * 至少页面上还能选，不至于整个提交流程瘫在这里。
     */
    @GetMapping("/repos")
    public List<RepoOption> listRepos() {
        try {
            return gitee.listMyRepos().stream()
                    .map(repo -> new RepoOption(repo.fullName(), repo.displayName(), repo.description(),
                            repo.stars(), repo.sourceFullName()))
                    .toList();
        } catch (RestClientException e) {
            log.warn("拉取令牌名下的仓库失败，退回预置清单 | {}", e.getMessage());
            return catalog.findAll();
        }
    }

    @GetMapping("/repos/{owner}/{repo}/commits")
    public List<GiteeCommitSummary> listCommits(@PathVariable String owner, @PathVariable String repo) {
        return gitee.listCommits(owner, repo, COMMIT_LIMIT);
    }
}

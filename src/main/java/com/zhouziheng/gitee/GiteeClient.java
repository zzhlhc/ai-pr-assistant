package com.zhouziheng.gitee;

import com.zhouziheng.gitee.dto.GiteeCommit;
import com.zhouziheng.gitee.dto.GiteeCommitSummary;
import com.zhouziheng.gitee.dto.GiteeContent;
import com.zhouziheng.gitee.dto.GiteeRepoSummary;
import com.zhouziheng.gitee.dto.GiteeTree;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriBuilder;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * 调用 Gitee OpenAPI v5，配了令牌就带上。
 * <p>
 * 为什么要带：认证身份的额度比匿名（60 次/小时）高一个量级。一次评审要 20~35 次请求
 * （1 次 commit + 1 次 trees + 十几个文件正文），匿名额度跑两次就没了。
 * <p>
 * 代价是可见范围：Gitee 用令牌的身份只能看该账号名下的仓库，读别人的公开仓库会直接 404
 * （实测 /repos/{owner}/{repo} 系列全 404，只有 /users/{user}/repos 这类列表接口认令牌）。
 * 所以清单里放的是本账号 fork 过来的项目，见 data.sql。
 */
@Component
public class GiteeClient {

    private static final Logger log = LoggerFactory.getLogger(GiteeClient.class);

    private final RestClient restClient;
    private final GiteeProperties properties;

    public GiteeClient(RestClient.Builder builder, GiteeProperties properties) {
        this.restClient = builder.baseUrl(properties.baseUrl()).build();
        this.properties = properties;
    }

    /**
     * 当前令牌能访问到的仓库列表，给页面的仓库下拉框当数据源。
     * <p>
     * 走认证接口 /user/repos：Gitee 的仓库搜索接口已经废了（见 docs/notes/Gitee-接口限制.md），
     * 而私人令牌只能看到自己账号名下的仓库，范围正好就是这里返回的这一批。
     * 所以「在 Gitee 上 fork 一个热门项目」＝「下拉框里自动多一个选项」，不需要再维护清单。
     * <p>
     * 返回体字段很多且是 snake_case，只挑下拉框要用的几个。
     * 注意 human_name 才是页面上显示的名字（若依/RuoYi），full_name 是路径（y_project/RuoYi）。
     */
    public List<GiteeRepoSummary> listMyRepos() {
        List<Map<String, Object>> repos = restClient.get()
                .uri(uriBuilder -> auth(uriBuilder.path("/user/repos"))
                        .queryParam("per_page", 100)
                        .queryParam("sort", "updated")
                        .build())
                .retrieve()
                .body(new ParameterizedTypeReference<List<Map<String, Object>>>() {
                });

        return repos.stream()
                .map(repo -> {
                    // fork 过来的仓库，列表里显示原项目名字才像样：parent 就是原仓库对象。
                    // 但 full_name 必须留着自己账号的路径 —— 令牌只认自己名下的仓库。
                    Map<?, ?> parent = (Map<?, ?>) repo.get("parent");
                    Map<?, ?> shown = parent != null ? parent : repo;
                    return new GiteeRepoSummary(
                            (String) repo.get("full_name"),
                            (String) shown.get("human_name"),
                            (String) shown.get("description"),
                            ((Number) shown.get("stargazers_count")).intValue(),
                            parent != null ? (String) parent.get("full_name") : null);
                })
                .toList();
    }

    /**
     * 拉取 commit 详情。Gitee 会同时在 files[].patch 里返回每个文件的 diff，
     * 所以不需要再单独调一次 diff 接口。
     */
    public GiteeCommit getCommit(String owner, String repo, String sha) {
        return restClient.get()
                .uri(uriBuilder -> auth(uriBuilder.path("/repos/{owner}/{repo}/commits/{sha}"))
                        .build(owner, repo, sha))
                .retrieve()
                .body(GiteeCommit.class);
    }

    /**
     * 仓库最近的若干个 commit，给页面的 commit 下拉框用。
     * <p>
     * 列表接口不带 files，返回体小、也快，用户挑完某一条再去调 {@link #getCommit} 拉 diff。
     */
    public List<GiteeCommitSummary> listCommits(String owner, String repo, int limit) {
        return restClient.get()
                .uri(uriBuilder -> auth(uriBuilder.path("/repos/{owner}/{repo}/commits"))
                        .queryParam("per_page", limit)
                        .build(owner, repo))
                .retrieve()
                .body(new ParameterizedTypeReference<List<GiteeCommitSummary>>() {
                });
    }

    /**
     * 拉取整个仓库的文件树（递归）。一次请求换回全部路径，是建立符号索引的基础。
     */
    public GiteeTree getTree(String owner, String repo, String sha) {
        return restClient.get()
                .uri(uriBuilder -> auth(uriBuilder.path("/repos/{owner}/{repo}/git/trees/{sha}"))
                        .queryParam("recursive", "1")
                        .build(owner, repo, sha))
                .retrieve()
                .body(GiteeTree.class);
    }

    /**
     * 读取单个文件的完整内容，自动 base64 解码。
     * <p>
     * 路径必须逐段拼接：直接当成一个路径变量传进去的话，斜杠会被编码成 %2F，
     * Gitee 那边就找不到了。
     *
     * @return 文件正文；路径不存在或读取失败时返回 null
     */
    public String getFileContent(String owner, String repo, String path, String ref) {
        try {
            GiteeContent file = restClient.get()
                    .uri(uriBuilder -> {
                        UriBuilder builder = uriBuilder.path("/repos/{owner}/{repo}/contents");
                        for (String segment : path.split("/")) {
                            builder.pathSegment(segment);
                        }
                        return auth(builder.queryParam("ref", ref)).build(owner, repo);
                    })
                    .retrieve()
                    .body(GiteeContent.class);

            if (file == null || file.content() == null) {
                return null;
            }
            // MimeDecoder：Gitee 的 base64 里带换行，用基本解码器会直接抛异常
            return new String(Base64.getMimeDecoder().decode(file.content()), StandardCharsets.UTF_8);
        } catch (RestClientException e) {
            // 路径不存在时 Gitee 返回的是空数组 []，反序列化成对象必然失败，属于预期情况
            log.debug("读取文件失败 | path={} | {}", path, e.getMessage());
            return null;
        }
    }

    /**
     * 未配置令牌时不带 access_token —— 公开仓库匿名也能读，只是额度只有 60 次/小时；
     * 配了就要带上，不能传空串，Gitee 对空的 access_token 会直接返回 401。
     */
    private UriBuilder auth(UriBuilder builder) {
        if (StringUtils.hasText(properties.token())) {
            builder.queryParam("access_token", properties.token());
        }
        return builder;
    }
}

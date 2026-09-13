package com.zhouziheng.gitee;

import com.zhouziheng.gitee.dto.GiteeCommit;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * 调用 Gitee OpenAPI v5。
 */
@Component
public class GiteeClient {

    private final RestClient restClient;
    private final GiteeProperties properties;

    public GiteeClient(RestClient.Builder builder, GiteeProperties properties) {
        this.properties = properties;
        this.restClient = builder.baseUrl(properties.baseUrl()).build();
    }

    /**
     * 拉取 commit 详情。Gitee 会同时在 files[].patch 里返回每个文件的 diff，
     * 所以不需要再单独调一次 diff 接口。
     */
    public GiteeCommit getCommit(String owner, String repo, String sha) {
        return restClient.get()
                .uri("/repos/{owner}/{repo}/commits/{sha}?access_token={token}",
                        owner, repo, sha, properties.token())
                .retrieve()
                .body(GiteeCommit.class);
    }
}

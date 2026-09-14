package com.zhouziheng.gitee;

import com.zhouziheng.gitee.dto.GiteeCommit;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
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
     * <p>
     * 未配置令牌时不带 access_token 参数，公开仓库可以正常访问；
     * 注意不能传空的 access_token，Gitee 会直接返回 401。
     */
    public GiteeCommit getCommit(String owner, String repo, String sha) {
        return restClient.get()
                .uri(uriBuilder -> {
                    uriBuilder.path("/repos/{owner}/{repo}/commits/{sha}");
                    if (StringUtils.hasText(properties.token())) {
                        uriBuilder.queryParam("access_token", properties.token());
                    }
                    return uriBuilder.build(owner, repo, sha);
                })
                .retrieve()
                .body(GiteeCommit.class);
    }
}

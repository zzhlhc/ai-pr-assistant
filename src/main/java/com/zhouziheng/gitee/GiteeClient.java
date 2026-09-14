package com.zhouziheng.gitee;

import com.zhouziheng.gitee.dto.GiteeCommit;
import com.zhouziheng.gitee.dto.GiteeContent;
import com.zhouziheng.gitee.dto.GiteeTree;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriBuilder;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * 调用 Gitee OpenAPI v5。
 */
@Component
public class GiteeClient {

    private static final Logger log = LoggerFactory.getLogger(GiteeClient.class);

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

    /**
     * 拉取整个仓库的文件树（递归）。一次请求换回全部路径，是建立符号索引的基础。
     */
    public GiteeTree getTree(String owner, String repo, String sha) {
        return restClient.get()
                .uri(uriBuilder -> {
                    uriBuilder.path("/repos/{owner}/{repo}/git/trees/{sha}");
                    uriBuilder.queryParam("recursive", "1");
                    if (StringUtils.hasText(properties.token())) {
                        uriBuilder.queryParam("access_token", properties.token());
                    }
                    return uriBuilder.build(owner, repo, sha);
                })
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
                        if (StringUtils.hasText(properties.token())) {
                            builder.queryParam("access_token", properties.token());
                        }
                        builder.queryParam("ref", ref);
                        return builder.build(owner, repo);
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
}

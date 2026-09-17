package com.zhouziheng.review.agent;

import com.zhouziheng.review.agent.dto.AgentChatRequest;
import com.zhouziheng.review.agent.dto.AgentChatResponse;
import com.zhouziheng.review.agent.dto.AgentMessage;
import com.zhouziheng.review.agent.dto.AgentToolSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.model.openai.autoconfigure.OpenAiChatProperties;
import org.springframework.ai.model.openai.autoconfigure.OpenAiCommonProperties;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.io.IOException;
import java.net.ConnectException;
import java.net.http.HttpClient;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 直连 OpenAI 兼容协议的 /chat/completions，支持 tools 字段。
 * <p>
 * 刻意绕开 Spring AI 的 ChatClient 手写这一层，原因只有一个：
 * agent 循环需要看到每一轮的原始消息（谁说了什么、调了什么工具、token 花在哪一轮），
 * 而高层抽象会把中间过程封在里面，最后只给你一个结果对象。
 * 代价是要自己维护消息列表，好处是整条链路完全透明 —— 这正是 agent 最该被看见的部分。
 * <p>
 * 超时也在这里显式设死：框架把请求级超时默认写死 60 秒，会把长回答掐断，
 * 报错却是一句极具误导性的 Error reading response。
 * 最后一轮要一次性吐完整份评审报告，是整条链路里最慢的一次调用。
 */
@Component
public class AgentChatClient {

    private static final Logger log = LoggerFactory.getLogger(AgentChatClient.class);

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    public static final Duration READ_TIMEOUT = Duration.ofMinutes(5);
    private static final Duration RETRY_BACKOFF = Duration.ofSeconds(1);
    private static final int MESSAGE_LIMIT = 120;

    private final RestClient restClient;
    private final OpenAiChatProperties chatProperties;

    public AgentChatClient(RestClient.Builder builder, OpenAiChatProperties chatProperties,
                           OpenAiCommonProperties commonProperties) {
        this.chatProperties = chatProperties;

        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(READ_TIMEOUT);

        this.restClient = builder
                .baseUrl(commonProperties.getBaseUrl())
                .defaultHeader("Authorization", "Bearer " + commonProperties.getApiKey())
                .requestFactory(requestFactory)
                .build();
    }

    public AgentChatResponse chat(List<AgentMessage> messages, List<AgentToolSpec> tools) {
        AgentChatRequest request = new AgentChatRequest(
                chatProperties.getModel(), messages, tools, chatProperties.getTemperature());
        String body = AgentJson.MAPPER.writeValueAsString(request);

        log.info("agent 请求 | model={} | 消息数={} | 工具数={} | 各段字符数={}",
                chatProperties.getModel(), messages.size(), tools == null ? 0 : tools.size(),
                messages.stream().map(m -> m.role() + "=" + lengthOf(m.content())).collect(Collectors.joining("，")));

        String raw = requestRaw(body);

        AgentChatResponse response = AgentJson.MAPPER.readValue(raw, AgentChatResponse.class);
        AgentChatResponse.Usage usage = response.usageOrEmpty();
        log.info("agent 响应 | 结束原因={} | prompt={}（缓存命中 {}）| completion={}",
                response.choices() == null || response.choices().isEmpty() ? "无"
                        : response.choices().get(0).finishReason(),
                usage.promptTokens(), usage.promptCacheHitTokens(), usage.completionTokens());
        return response;
    }

    /**
     * 发一次请求，失败就再发一次，两次都不行才把错误抛出去。
     * <p>
     * 值得重试的原因：这类失败大多发生在"模型已经算完、回包传到一半连接断了"，
     * 重试等于把这一轮重跑一遍，代价远小于让整条评审任务直接失败。
     * <p>
     * 但 4xx（限流除外）是确定性错误 —— 参数、鉴权、余额的问题，重试多少次都是一样的结果，
     * 白等一轮超时而已，所以直接抛。
     */
    private String requestRaw(String body) {
        try {
            return requestOnce(body);
        } catch (RestClientException first) {
            if (!retryable(first)) {
                throw new IllegalStateException(failureMessage(first), first);
            }
            log.warn("agent 请求失败，{} 秒后重试一次 | {}", RETRY_BACKOFF.toSeconds(), describe(first), first);
            sleepQuietly();
        }
        try {
            return requestOnce(body);
        } catch (RestClientException second) {
            throw new IllegalStateException(failureMessage(second), second);
        }
    }

    private String requestOnce(String body) {
        return restClient.post()
                .uri("/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(String.class);
    }

    private boolean retryable(RestClientException e) {
        if (e instanceof HttpClientErrorException clientError) {
            return clientError.getStatusCode().value() == 429;
        }
        return true;
    }

    private void sleepQuietly() {
        try {
            Thread.sleep(RETRY_BACKOFF.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 这条消息会原样写进任务失败原因、显示在页面上，
     * 所以刻意不拼堆栈、不堆类名 —— 只说人话：什么错了、接下来能做什么。
     */
    private String failureMessage(Throwable cause) {
        return "调用 DeepSeek 失败（" + describe(cause) + "）：本轮没拿到结果，消息没丢，可重试本任务或检查网络/代理";
    }

    private String describe(Throwable e) {
        if (e instanceof RestClientResponseException response) {
            return "接口返回 HTTP " + response.getStatusCode().value()
                    + "，" + abbreviate(response.getResponseBodyAsString());
        }
        Throwable root = rootCause(e);
        if (root instanceof SocketTimeoutException) {
            return "读响应超时，这一轮模型想太久或网络太慢";
        }
        if (root instanceof UnknownHostException) {
            return "域名解析不了，检查网络或代理设置";
        }
        if (root instanceof ConnectException) {
            return "连接被拒绝，多为代理没开或接口地址不对";
        }
        if (root instanceof IOException) {
            return "响应读到一半连接被断开（" + abbreviate(root.getMessage()) + "）";
        }
        return root.getClass().getSimpleName() + "：" + abbreviate(root.getMessage());
    }

    private Throwable rootCause(Throwable e) {
        Throwable current = e;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current;
    }

    private String abbreviate(String text) {
        if (text == null) {
            return "无详情";
        }
        String flat = text.replaceAll("\\s+", " ").trim();
        return flat.length() <= MESSAGE_LIMIT ? flat : flat.substring(0, MESSAGE_LIMIT) + "…";
    }

    private int lengthOf(String content) {
        return content == null ? 0 : content.length();
    }
}

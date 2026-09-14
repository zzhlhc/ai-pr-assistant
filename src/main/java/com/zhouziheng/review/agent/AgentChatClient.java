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
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
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
 * 超时也在这里显式设死：预塞式那次踩过的坑（框架默认 60 秒掐断长回答，
 * 报错却是一句极具误导性的 Error reading response）没必要再踩第二遍。
 * 最后一轮要一次性吐完整份评审报告，是整条链路里最慢的一次调用。
 */
@Component
public class AgentChatClient {

    private static final Logger log = LoggerFactory.getLogger(AgentChatClient.class);

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration READ_TIMEOUT = Duration.ofMinutes(5);

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

        String raw = restClient.post()
                .uri("/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(String.class);

        AgentChatResponse response = AgentJson.MAPPER.readValue(raw, AgentChatResponse.class);
        AgentChatResponse.Usage usage = response.usageOrEmpty();
        log.info("agent 响应 | 结束原因={} | prompt={}（缓存命中 {}）| completion={}",
                response.choices() == null || response.choices().isEmpty() ? "无"
                        : response.choices().get(0).finishReason(),
                usage.promptTokens(), usage.promptCacheHitTokens(), usage.completionTokens());
        return response;
    }

    private int lengthOf(String content) {
        return content == null ? 0 : content.length();
    }
}

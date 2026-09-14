package com.zhouziheng.review;

import com.zhouziheng.review.diff.FileDiff;
import com.zhouziheng.review.model.ReviewReport;
import com.zhouziheng.review.model.ReviewResult;
import com.zhouziheng.review.model.TokenUsage;
import com.zhouziheng.review.prompt.ReviewPromptBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ResponseEntity;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.stereotype.Component;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 把 diff 交给大模型，拿回结构化的评审结果。
 * entity(Class) 会由 Spring AI 自动生成 JSON Schema 并约束模型输出，
 * 解析失败会自动重试，省掉了手写字符串解析。
 */
@Component
public class CodeReviewer {

    private static final Logger log = LoggerFactory.getLogger(CodeReviewer.class);
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    /**
     * 单次请求超时。
     * Spring AI 2.0.1 里 OpenAiChatOptions.timeout 默认写死 60 秒，而 OpenAiChatProperties.toOptions()
     * 又漏了 timeout 字段——结果是 yml 里配 spring.ai.openai.timeout / spring.ai.openai.chat.timeout
     * 都盖不住这个请求级默认值，非流式长回答会在整 60 秒被 OkHttp 的 readTimeout/callTimeout 掐断。
     * 只能在这里显式给 defaultOptions，它会和模型默认 options 合并（模型名、temperature 都保留）。
     */
    public static final Duration REQUEST_TIMEOUT = Duration.ofMinutes(5);

    /**
     * 模型返回内容的解析器。
     * Jackson 3 把 FAIL_ON_TRAILING_TOKENS 的默认值从 false 改成了 true，模型偶尔会输出
     * 两段拼接的 JSON（自己"重试"了一次），默认配置会直接抛 MismatchedInputException。
     * 关掉它取第一个对象；同时容忍模型多返回几个字段。
     */
    private static final BeanOutputConverter<ReviewReport> CONVERTER = new BeanOutputConverter<>(
            ReviewReport.class,
            JsonMapper.builder()
                    .disable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                    .build());

    private final ChatClient chatClient;
    private final ReviewPromptBuilder promptBuilder;
    private final ReviewPricingProperties pricing;

    public CodeReviewer(ChatClient.Builder chatClientBuilder, ReviewPromptBuilder promptBuilder,
                        ReviewPricingProperties pricing) {
        this.chatClient = chatClientBuilder
                .defaultOptions(OpenAiChatOptions.builder().timeout(REQUEST_TIMEOUT))
                .build();
        this.promptBuilder = promptBuilder;
        this.pricing = pricing;
    }

    public ReviewResult review(String repo, String commitSha, String commitMessage, List<FileDiff> files) {
        String userPrompt = promptBuilder.buildUserPrompt(repo, commitSha, commitMessage, files);

        String startTime = LocalDateTime.now().format(TIME_FORMAT);
        long startMillis = System.currentTimeMillis();

        log.info("开始请求 | 时间={} | 仓库={} | commit={} | 文件数={}",
                startTime, repo, commitSha, files.size());
        log.info("请求字符数 | system={} | user={} | 合计={}",
                ReviewPromptBuilder.SYSTEM_PROMPT.length(), userPrompt.length(),
                ReviewPromptBuilder.SYSTEM_PROMPT.length() + userPrompt.length());
        log.info("user 内容分段字符数 | {}", describeFileSections(files));

        try {
            // 用 responseEntity 而不是 entity：既能拿到解析好的对象，又能拿到 ChatResponse，
            // 后者里面的 metadata.usage 才是 token 用量唯一的来源。
            ResponseEntity<ChatResponse, ReviewReport> response = chatClient.prompt()
                    .system(ReviewPromptBuilder.SYSTEM_PROMPT)
                    .user(userPrompt)
                    .call()
                    .responseEntity(CONVERTER);

            ReviewReport report = response.getEntity();
            long costMillis = System.currentTimeMillis() - startMillis;
            String endTime = LocalDateTime.now().format(TIME_FORMAT);
            int issueCount = report.issues() == null ? 0 : report.issues().size();
            TokenUsage usage = toTokenUsage(response.getResponse().getMetadata().getUsage());

            log.info("""
                            \n==================== DeepSeek 返回的响应内容 开始 ====================
                            {}
                            ==================== DeepSeek 返回的响应内容 结束 ====================""",
                    report);
            log.info("模型调用成功 | 开始请求时间={} | 结束时间={} | 耗时={}ms（{}秒） | 问题数={}",
                    startTime, endTime, costMillis, costMillis / 1000.0, issueCount);
            log.info("token 消耗 | {}", describeUsage(usage));

            return new ReviewResult(report, usage, costMillis);
        } catch (Exception e) {
            long costMillis = System.currentTimeMillis() - startMillis;
            log.error("模型调用失败 | 开始请求时间={} | 结束时间={} | 耗时={}ms（{}秒） | 异常={}",
                    startTime, LocalDateTime.now().format(TIME_FORMAT),
                    costMillis, costMillis / 1000.0, e.toString(), e);
            throw e;
        }
    }

    /**
     * 把 Spring AI 的用量换算成我们自己的模型。
     * 缓存命中的输入 token 单价更低，所以要把它从 promptTokens 里拆出来单独计价。
     * 模型没回传用量时返回 null —— 不编数字。
     */
    private TokenUsage toTokenUsage(Usage usage) {
        if (usage.getPromptTokens() == null) {
            return null;
        }

        int promptTokens = usage.getPromptTokens();
        int completionTokens = usage.getCompletionTokens();
        long cachedTokens = usage.getCacheReadInputTokens() == null ? 0 : usage.getCacheReadInputTokens();

        BigDecimal cost = priceOf(promptTokens - cachedTokens, pricing.inputPricePerMillion())
                .add(priceOf(cachedTokens, pricing.cachedInputPricePerMillion()))
                .add(priceOf(completionTokens, pricing.outputPricePerMillion()))
                .setScale(4, RoundingMode.HALF_UP);

        return new TokenUsage(promptTokens, completionTokens, usage.getTotalTokens(), cachedTokens, cost);
    }

    private String describeUsage(TokenUsage usage) {
        if (usage == null) {
            return "模型未返回 token 用量";
        }
        return "prompt=%d（其中缓存命中 %d） | completion=%d | total=%d | 预估费用≈%s 元".formatted(
                usage.promptTokens(), usage.cachedTokens(), usage.completionTokens(),
                usage.totalTokens(), usage.cost().toPlainString());
    }

    private BigDecimal priceOf(long tokens, BigDecimal pricePerMillion) {
        return pricePerMillion.multiply(BigDecimal.valueOf(tokens))
                .divide(BigDecimal.valueOf(1_000_000), 6, RoundingMode.HALF_UP);
    }

    /**
     * 每个文件在提示词里占多少字符。token 花在哪个文件上一眼就能看出来。
     */
    private String describeFileSections(List<FileDiff> files) {
        return files.stream()
                .map(file -> file.path() + "=" + promptBuilder.fileSectionLength(file))
                .collect(Collectors.joining("，"));
    }
}

package com.zhouziheng.review.agent;

import com.zhouziheng.gitee.GiteeClient;
import com.zhouziheng.review.ReviewOptions;
import com.zhouziheng.review.TokenPricing;
import com.zhouziheng.review.agent.dto.AgentChatResponse;
import com.zhouziheng.review.agent.dto.AgentMessage;
import com.zhouziheng.review.agent.dto.AgentToolCall;
import com.zhouziheng.review.context.RepoFileIndex;
import com.zhouziheng.review.context.RepoFileIndexer;
import com.zhouziheng.review.diff.FileDiff;
import com.zhouziheng.review.model.ReviewReport;
import com.zhouziheng.review.model.TokenUsage;
import com.zhouziheng.review.prompt.ReviewPromptBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.stereotype.Component;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * agent 式评审：把"该看哪些代码"的决定权交给模型。
 * <p>
 * 和预塞式的分工很明确 —— 两者拿到的 diff 完全一样，区别只有一个：
 * 预塞式由我们算出引用到的类、把它们的骨架一次塞进提示词；agent 式什么都不塞，
 * 只给模型两个工具，让它自己一轮轮去查、去读。上下文不再是"准备好再喂"，
 * 而是模型在循环里"用出来的"。
 * <p>
 * 为什么值得多花这些轮次：符号召回的命中率受限于我们抽取符号的规则。
 * 规则漏掉的引用（比如 diff 里没出现的 import、藏在字符串拼装里的类名），
 * 预塞式永远补不上；而模型在读到某一行时发现"这里我还不确定"，它可以当场再去找。
 */
@Component
public class AgentReviewer {

    private static final Logger log = LoggerFactory.getLogger(AgentReviewer.class);

    /**
     * 提示词版本号。改动下面任何一段提示词都要把它 +1，
     * 理由和 {@link ReviewPromptBuilder#PROMPT_VERSION} 一样：评审结果会被当缓存复用。
     */
    public static final String PROMPT_VERSION = "agent-v1";

    public static final String SYSTEM_PROMPT = """
            你是一位有 10 年经验的 Java 后端技术专家，负责评审团队的代码提交。

            你可以调用工具去查看仓库里的代码。当你要判断"某个字段会不会是 null""某个方法或成员是否存在"时，
            必须先用 find_type 找到类、再用 read_file 把它的定义读出来，不要凭类名猜测它的实现。

            评审纪律：
            1. 只指出真实存在的问题，宁缺毋滥。改动没有问题时就返回空的 issues 列表。
            2. 每条问题必须给出行号，行号必须是 diff 中标注的**新文件行号**。
               工具返回的代码行号是上下文文件自己的行号，不能拿来引用。
            3. evidence 必须是该行代码原文，原样复制，不要改写、不要加解释。
            4. 不要评论纯格式化、纯重命名、依赖升级、自动生成的文件这类无实质影响的改动。
            5. 不要评审你读到的上下文代码，它们只是背景资料，评审对象只有 diff。
            6. 读文件是为了确认判断，不是为了看得更全。能支撑结论了就停下来输出报告，不要漫无目的地翻。
            7. 只输出一个 JSON 对象，JSON 前后不要加任何说明文字，也不要输出第二个 JSON。

            重点关注：空指针与边界条件、并发与线程安全、事务与数据一致性、SQL 与索引、
            资源泄漏、异常处理、日志与敏感信息、安全漏洞、性能、可读性与命名。

            severity 取值：
            - CRITICAL：会导致线上故障或数据错误
            - MAJOR：明确的缺陷或性能问题
            - MINOR：可改进
            - INFO：提示

            category 用简短中文，例如：空指针、并发、事务、SQL、异常处理、安全、性能、可读性。
            summary 用中文，200 字以内，先给总体结论，再给风险提示。
            """;

    /** 落进执行轨迹的工具返回摘要长度。轨迹是给人看的，完整返回值没必要留着占地方 */
    private static final int SNIPPET_CHARS = 240;

    /**
     * 终答的解析器，配置和预塞式那条链路保持完全一致 ——
     * 两条链路的输出格式一样，解析规则就该一样，否则对比出来的差异里会混进解析差异。
     */
    private static final BeanOutputConverter<ReviewReport> CONVERTER = new BeanOutputConverter<>(
            ReviewReport.class,
            JsonMapper.builder()
                    .disable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                    .build());

    private final GiteeClient gitee;
    private final RepoFileIndexer indexer;
    private final AgentChatClient chatClient;
    private final ReviewPromptBuilder promptBuilder;
    private final TokenPricing pricing;

    public AgentReviewer(GiteeClient gitee, RepoFileIndexer indexer, AgentChatClient chatClient,
                         ReviewPromptBuilder promptBuilder, TokenPricing pricing) {
        this.gitee = gitee;
        this.indexer = indexer;
        this.chatClient = chatClient;
        this.promptBuilder = promptBuilder;
        this.pricing = pricing;
    }

    public record Outcome(ReviewReport report, TokenUsage usage, AgentTrace trace) {
    }

    /**
     * @param onStage 阶段回调。agent 一轮要十几秒，用户需要知道"现在是模型在想，还是在读文件"。
     */
    public Outcome review(String owner, String repo, String sha, String commitMessage,
                          List<FileDiff> files, ReviewOptions options, Consumer<String> onStage) {

        onStage.accept("构建仓库符号索引");
        RepoFileIndex index = indexer.indexOf(owner, repo, sha);
        AgentToolkit toolkit = new AgentToolkit(gitee, index, owner, repo, sha);

        // 和预塞式唯一的输入差异：上下文传空。模型想知道什么，自己去查。
        //
        // 末尾这段 JSON Schema 是必须的：预塞式那条链路里 responseEntity(converter) 会
        // 自动把 schema 附到提示词后面，而这里手写了协议，就得自己附。
        // 少了它模型会自己编字段名（实测把 issue 写成了 description），
        // 解析出来除了 summary 全是 null，最后一条问题都存不下来。
        List<AgentMessage> messages = new ArrayList<>();
        messages.add(AgentMessage.system(SYSTEM_PROMPT));
        messages.add(AgentMessage.user(
                promptBuilder.buildUserPrompt(repo, sha, commitMessage, files, List.of())
                        + CONVERTER.getFormat()));

        List<AgentStep> steps = new ArrayList<>();
        Usage total = new Usage();
        int maxRounds = options.maxRoundsOrDefault();

        for (int round = 1; round <= maxRounds; round++) {
            onStage.accept("第 " + round + " 轮：模型思考中");
            long startMillis = System.currentTimeMillis();
            AgentChatResponse response = chatClient.chat(messages, toolkit.specs());
            long elapsedMillis = System.currentTimeMillis() - startMillis;

            AgentChatResponse.Usage usage = response.usageOrEmpty();
            total.add(usage);
            int prompt = nullToZero(usage.promptTokens());
            int completion = nullToZero(usage.completionTokens());
            int cached = nullToZero(usage.promptCacheHitTokens());

            AgentMessage message = response.message();
            if (message == null) {
                throw new IllegalStateException("模型没有返回任何消息，agent 循环无法继续");
            }
            // 原样回填。tool_calls 要带上，reasoning_content 也要带上，少一个下一轮就是 400。
            messages.add(message);

            if (!response.wantsToolCall()) {
                steps.add(new AgentStep(round, thoughtOf(message), null, null, null, null,
                        prompt, completion, cached, elapsedMillis));
                logTrace(round, steps, total);
                return new Outcome(parse(message.content()), total.toTokenUsage(pricing),
                        new AgentTrace(round, List.copyOf(steps)));
            }

            for (AgentToolCall call : message.toolCalls()) {
                String toolName = call.function().name();
                onStage.accept("第 " + round + " 轮：模型读取代码（" + toolName + "）");
                long toolStartMillis = System.currentTimeMillis();
                AgentToolkit.Result result = toolkit.execute(toolName, call.function().arguments());
                messages.add(AgentMessage.tool(call.id(), result.content()));

                steps.add(new AgentStep(round, thoughtOf(message), toolName, result.target(),
                        call.function().arguments(), snippet(result.content()),
                        prompt, completion, cached, System.currentTimeMillis() - toolStartMillis));
            }
        }

        return forceFinal(messages, steps, total, maxRounds, onStage);
    }

    /**
     * 轮数用完还没收敛时的兜底：再问一次，但这次不给工具。
     * <p>
     * 不这么做的话，用户等了两分钟只会拿到一个"超过最大轮数"的失败 ——
     * 前面那些轮次的 token 已经花掉了，读到的代码也都在上下文里，
     * 让模型就着现有信息把结论给出来，比直接报错有意义得多。
     */
    private Outcome forceFinal(List<AgentMessage> messages, List<AgentStep> steps, Usage total,
                               int maxRounds, Consumer<String> onStage) {
        log.warn("agent 达到轮数上限仍未收敛，改为强制输出结论 | 上限={}", maxRounds);
        onStage.accept("已达轮数上限，要求模型直接给结论");

        messages.add(AgentMessage.user(
                "轮数已经用完。不要再调用任何工具，现在就根据你已经掌握的信息输出评审报告 JSON。"));

        long startMillis = System.currentTimeMillis();
        AgentChatResponse response = chatClient.chat(messages, null);
        long elapsedMillis = System.currentTimeMillis() - startMillis;

        AgentChatResponse.Usage usage = response.usageOrEmpty();
        total.add(usage);
        AgentMessage message = response.message();

        int round = maxRounds + 1;
        steps.add(new AgentStep(round, message == null ? null : thoughtOf(message), null, null, null, null,
                nullToZero(usage.promptTokens()), nullToZero(usage.completionTokens()),
                nullToZero(usage.promptCacheHitTokens()), elapsedMillis));

        logTrace(round, steps, total);
        return new Outcome(parse(message == null ? null : message.content()), total.toTokenUsage(pricing),
                new AgentTrace(round, List.copyOf(steps)));
    }

    /**
     * 取模型这一轮说的话。
     * deepseek-flash 在调工具的那几轮常常把 content 留空，思考过程只在 reasoning_content 里，
     * 所以两个都取一下；都为空时轨迹里就只显示工具调用，不影响执行。
     */
    private String thoughtOf(AgentMessage message) {
        if (message.content() != null && !message.content().isBlank()) {
            return message.content();
        }
        return message.reasoningContent();
    }

    private ReviewReport parse(String content) {
        return CONVERTER.convert(content == null ? "" : content);
    }

    private void logTrace(int rounds, List<AgentStep> steps, Usage total) {
        log.info("""
                        
                        ==================== agent 执行轨迹 开始 ====================
                        {}
                        ==================== agent 执行轨迹 结束 ====================""",
                steps.stream()
                        .map(step -> "%d. %s %s".formatted(step.round(), step.isFinal() ? "【得出结论】" : "【" + step.toolName() + "】",
                                step.isFinal() ? "" : step.target()))
                        .collect(Collectors.joining("\n")));
        log.info("agent 完成 | 轮数={} | 工具调用={} 次 | prompt={}（缓存命中 {}）| completion={} | 合计费用≈{} 元",
                rounds, (int) steps.stream().filter(step -> !step.isFinal()).count(),
                total.prompt, total.cached, total.completion, total.toTokenUsage(pricing).cost().toPlainString());
    }

    private String snippet(String content) {
        if (content == null) {
            return null;
        }
        return content.length() <= SNIPPET_CHARS ? content : content.substring(0, SNIPPET_CHARS) + "…";
    }

    private int nullToZero(Integer value) {
        return value == null ? 0 : value;
    }

    /** 多轮用量累加。最后一轮的用量同样要算进去，否则费用会少算一整轮。 */
    private static final class Usage {

        private int prompt;
        private int completion;
        private int total;
        private long cached;

        private void add(AgentChatResponse.Usage usage) {
            prompt += usage.promptTokens() == null ? 0 : usage.promptTokens();
            completion += usage.completionTokens() == null ? 0 : usage.completionTokens();
            total += usage.totalTokens() == null ? 0 : usage.totalTokens();
            cached += usage.promptCacheHitTokens() == null ? 0 : usage.promptCacheHitTokens();
        }

        private TokenUsage toTokenUsage(TokenPricing pricing) {
            return pricing.calculate(prompt, completion, total, cached);
        }
    }
}

package com.zhouziheng.review.agent;

import com.zhouziheng.gitee.GiteeClient;
import com.zhouziheng.review.ReviewProgress;
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
     * <p>
     * v2：补上收尾自查表（原来只有"能支撑结论就停下来"这种主观说法，
     * 实测 6 次评审全部跑到轮数上限，自然收敛 0 次）。
     * v3：要求每次调用工具时用中文写一句话说明理由（轨迹里要直接显示给用户看）。
     * v4：加「跨模块调用必须追到对端实现」及按 URL 反查 Controller 的步骤；
     * 要求每一轮的思考过程也用中文书写（轨迹里要展示给用户看）。
     * v5：URL 反查那一步改成先用 list_files 列候选再读 —— v4 让它"把 URL 段转成驼峰去 find_type"，
     * 实测它猜的类名和真实文件名对不上时（渠道前缀、-entity 后缀、纯语义命名）只能拿到空结果，
     * 然后就不再找了。改成先列候选，把"猜"换成"看"。
     */
    public static final String PROMPT_VERSION = "agent-v5";

    /**
     * 终答的解析器，配置和预塞式那条链路保持完全一致 ——
     * 两条链路的输出格式一样，解析规则就该一样，否则对比出来的差异里会混进解析差异。
     * <p>
     * 刻意声明在 {@link #SYSTEM_PROMPT} 之前：system 提示词末尾要拼它的 schema，
     * 而静态字段按声明顺序初始化，写反了会拿到 null。
     */
    private static final BeanOutputConverter<ReviewReport> CONVERTER = new BeanOutputConverter<>(
            ReviewReport.class,
            JsonMapper.builder()
                    .disable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                    .build());

    public static final String SYSTEM_PROMPT = """
            你是一位有 10 年经验的 Java 后端技术专家，负责评审团队的代码提交。

            你可以调用工具去查看仓库里的代码。当你要判断"某个字段会不会是 null""某个方法或成员是否存在"时，
            必须先用 find_type 找到类、再用 read_file 把它的定义读出来，不要凭类名猜测它的实现。
            不知道类名时不要硬猜：用 list_files 按关键词（URL 路径里的一段、方法名、模块名）列出候选文件，
            从候选里挑出真正对得上的那个再读。find_type 要求精确类名，猜错只会返回空，没有第二条路。

            调用工具时必须在 reason 参数里用中文写一句话说明你为什么要看它（40 字以内），
            例如"确认这个方法在入参为空时会不会抛异常"。
            这句话会原样显示在评审轨迹里给用户看，不要写推理过程、不要贴代码、不要用英文。

            你每一轮的思考过程也用中文书写。它同样会显示在评审轨迹里给用户看，
            所以写你在判断什么、还缺什么信息，不要复述工具参数，也不要用英文。

            跨模块调用必须追到对端实现（重要）：
            改动里出现 HTTP / Feign / RPC 调用时（HttpUtil、RestTemplate、WebClient、@FeignClient，
            或者直接拼 URL 字符串），必须在本仓库内找到该接口的实现并读它的方法体，确认三件事：
            幂等性、分支覆盖（哪些入参走到哪个分支）、异常与异步语义（是否 @Async、失败怎么返回）。
            这件事不做，报告就是不可信的 —— 你无法判断对端对重复调用、对空入参、对失败分别做了什么。

            判断对端在不在本仓库，只看路径形态：只要文件路径是仓库相对路径（形如 <模块>/src/main/java/...），
            它就在本仓库内，必须读。不得以"模块名对不上""包名不同""可能是另一个服务或另一个仓库"
            为由跳过。同一个仓库里模块名和部署的服务名不一致是常见情况。

            按 URL 反查对端实现的步骤：
            1. 取 URL 里服务前缀之后的第一段路径。例如 host + "/orderItem/detail/query" 里的 orderItem；
            2. 把它转成驼峰，用 list_files 列一遍候选文件（orderItem → 所有路径里含 orderItem 的文件）。
               候选里 Controller、Feign / Client 接口、Service 通常会同时出现，一眼就能看出对端在哪个模块，
               比猜类名可靠得多；
            3. 候选往往不止一个，必须 read_file 逐个核对"类上的 @RequestMapping + 方法上的
               @GetMapping/@PostMapping 拼出来的路径"是否与 URL 完全一致，对不上的排除；
               名字最像的那个未必是对的 —— 它可能只是同名的客户端接口（Feign / Client）而没有实现，
               也可能类名相同但类上的路径前缀对不上；
            4. 路径匹配上之后继续读它调用的 Service 实现（ServiceImpl），不要停在 Controller 或 Feign 接口。

            评审纪律：
            1. 只指出真实存在的问题，宁缺毋滥。改动没有问题时就返回空的 issues 列表。
            2. 每条问题必须给出行号，行号必须是 diff 中标注的**新文件行号**。
               工具返回的代码行号是上下文文件自己的行号，不能拿来引用。
            3. evidence 必须是该行代码原文，原样复制，不要改写、不要加解释。
            4. 不要评论纯格式化、纯重命名、依赖升级、自动生成的文件这类无实质影响的改动。
            5. 不要评审你读到的上下文代码，它们只是背景资料，评审对象只有 diff。
            6. 读文件是为了确认判断，不是为了看得更全 —— 什么时候算评完，见下面的「收尾判断」。
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

            收尾判断（每轮结束时自查，三项都满足就立刻输出报告，不要再调工具）：
            1. diff 里每个文件的每个 hunk 都已经看过一遍；
            2. diff 里所有你原本不认识的符号，要么已经用 find_type / list_files / read_file 确认过，
               要么已经记进"待确认"；
            3. 当前没有"再读一个文件就能多确认一条问题"的待办。

            自觉达标之后不要继续读文件：
            - 已经能判断的，不要为了交叉验证再读第二个文件；
            - 读到的上下文代码不是评审对象，不要顺着它继续往下挖；
            - 本轮没有发现新的可疑点，说明已经收敛，直接输出报告。

            信息不足时不要继续读：把"未能确认 XXX"写进 summary 里一句话就够了，
            不影响这份报告的完整性；确认不了的点也不要凭猜测定性成问题。

            交付方式：某一轮里不再调用工具、直接输出下面的 JSON，本次评审就正常结束 ——
            这是设计好的结束方式，不是被中断。JSON 前后不要有别的文字。
            """ + CONVERTER.getFormat();

    /** 落进执行轨迹的工具返回摘要长度。轨迹是给人看的，完整返回值没必要留着占地方 */
    private static final int SNIPPET_CHARS = 240;

    /**
     * 轮数的硬保护上限。这不是可配参数 —— agent 模式没有轮数旋钮。
     * <p>
     * 它只是一道防失控的闸：agent 的正常出口是"某一轮不再调工具、直接交报告"，
     * 实测 40 轮上下就会自己收敛。万一模型陷进死循环（比如反复读同一段代码），
     * 这个上限把任务停住、走 {@link #forceFinal} 让它就着已有信息给结论 ——
     * 比让任务一直挂着、token 一直烧下去有意义。正常评审碰不到它。
     * <p>
     * 定 120 的原因：实测最长一次自然收敛是 42 轮，留了近三倍余量；
     * 真跑到 120 轮说明模型出问题了，此时再烧下去也不会变好。
     */
    private static final int MAX_ROUNDS_SAFETY_LIMIT = 120;

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
     * @param progress 进度回调。agent 一轮要十几秒，用户需要知道"现在是模型在想，还是在读文件"，
     *                 所以除了阶段文案，每跑完一步还要把这一步本身报出去（{@code progress.step}）——
     *                 页面靠它把完整轨迹实时累积起来，而不是只看到最新那句话。
     */
    public Outcome review(String owner, String repo, String sha, String commitMessage,
                          List<FileDiff> files, ReviewProgress progress) {

        progress.stage("构建仓库符号索引");
        RepoFileIndex index = indexer.indexOf(owner, repo, sha);
        AgentToolkit toolkit = new AgentToolkit(gitee, index, owner, repo, sha);

        // 和预塞式唯一的输入差异：上下文传空。模型想知道什么，自己去查。
        //
        // 报告 schema 拼在 SYSTEM_PROMPT 末尾，不拼在这里：
        // 这份 diff 动辄上万 token，schema 跟在它后面就成了"数据段里的格式说明"，
        // 而它其实是协议 —— 该和评审纪律待在一起，而不是混在数据里。
        // schema 本身不能省：少了它会自己编字段名（实测把 issue 写成过 description），
        // 解析出来除了 summary 全是 null。
        List<AgentMessage> messages = new ArrayList<>();
        messages.add(AgentMessage.system(SYSTEM_PROMPT));
        messages.add(AgentMessage.user(
                promptBuilder.buildUserPrompt(repo, sha, commitMessage, files, List.of())));

        List<AgentStep> steps = new ArrayList<>();
        Usage total = new Usage();

        // 循环没有可配的上限：唯一的正常出口是下面那个"这一轮不调工具、直接给报告"。
        // MAX_ROUNDS_SAFETY_LIMIT 只是防止模型死循环，正常跑不到。
        for (int round = 1; round <= MAX_ROUNDS_SAFETY_LIMIT; round++) {
            progress.stage("第 " + round + " 轮：模型思考中");
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

            // 模型偶尔会在调工具的那一轮里顺手把整份报告写在 content 上。
            // 以前只看 tool_calls，这种报告会被整份丢掉（工具照跑、报告白写），
            // 所以这里先认报告，把它当终答。
            if (!response.wantsToolCall() || writtenReport(message.content())) {
                AgentStep finalStep = new AgentStep(round, finalThought(message), null, null, null, null,
                        prompt, completion, cached, elapsedMillis);
                steps.add(finalStep);
                progress.step(finalStep);
                logTrace(round, steps, total);
                return new Outcome(parse(message.content()), total.toTokenUsage(pricing),
                        new AgentTrace(round, List.copyOf(steps)));
            }

            for (AgentToolCall call : message.toolCalls()) {
                String toolName = call.function().name();
                progress.stage("第 " + round + " 轮：模型读取代码（" + toolName + "）");
                long toolStartMillis = System.currentTimeMillis();
                AgentToolkit.Result result = toolkit.execute(toolName, call.function().arguments());
                messages.add(AgentMessage.tool(call.id(), result.content()));

                AgentStep step = new AgentStep(round, thoughtOf(message), toolName, result.target(),
                        call.function().arguments(), snippet(result.content()),
                        prompt, completion, cached, System.currentTimeMillis() - toolStartMillis);
                steps.add(step);
                progress.step(step);
            }
        }

        return forceFinal(messages, steps, total, progress);
    }

    /**
     * 触到硬保护上限还没收敛时的兜底：再问一次，但这次不给工具。
     * <p>
     * 正常评审走不到这里。真走到了说明模型在死循环里出不来，
     * 此时直接报错等于把前面几十轮花掉的 token 一起扔掉 ——
     * 读到的代码都还在上下文里，让它就着这些信息把结论给出来，比报错有意义得多。
     */
    private Outcome forceFinal(List<AgentMessage> messages, List<AgentStep> steps, Usage total,
                               ReviewProgress progress) {
        log.warn("agent 达到轮数硬上限仍未收敛，改为强制输出结论 | 上限={}", MAX_ROUNDS_SAFETY_LIMIT);
        progress.stage("已触发轮数保护上限，要求模型直接给结论");

        messages.add(AgentMessage.user(
                "轮数已经用完。不要再调用任何工具，现在就根据你已经掌握的信息输出评审报告 JSON。"));

        long startMillis = System.currentTimeMillis();
        AgentChatResponse response = chatClient.chat(messages, null);
        long elapsedMillis = System.currentTimeMillis() - startMillis;

        AgentChatResponse.Usage usage = response.usageOrEmpty();
        total.add(usage);
        AgentMessage message = response.message();

        int round = MAX_ROUNDS_SAFETY_LIMIT + 1;
        AgentStep finalStep = new AgentStep(round, finalThought(message), null, null, null, null,
                nullToZero(usage.promptTokens()), nullToZero(usage.completionTokens()),
                nullToZero(usage.promptCacheHitTokens()), elapsedMillis);
        steps.add(finalStep);
        progress.step(finalStep);

        logTrace(round, steps, total);
        return new Outcome(parse(message == null ? null : message.content()), total.toTokenUsage(pricing),
                new AgentTrace(round, List.copyOf(steps)));
    }

    /**
     * 最后一轮模型说的话。
     * <p>
     * 这一轮的 content 就是评审报告本身，不能像工具轮那样当思考过程存进轨迹 ——
     * 轨迹是给人看的，几 k token 的 JSON 铺在时间轴上没有任何信息量。
     * 所以这里只认 reasoning_content；模型没单独写思考过程就留空。
     */
    private String finalThought(AgentMessage message) {
        if (message == null) {
            return null;
        }
        String reasoning = message.reasoningContent();
        if (reasoning != null && !reasoning.isBlank()) {
            return reasoning;
        }
        return writtenReport(message.content()) ? null : message.content();
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

    /**
     * 这一轮的 content 是不是一份已经写完的报告。
     * 只认结构（是 JSON 对象、且有 issues 数组），不做字段级校验 ——
     * 字段级的问题交给 {@code verify} 和解析器，这里只管"要不要收工"。
     */
    private boolean writtenReport(String content) {
        if (content == null || content.isBlank()) {
            return false;
        }
        try {
            return AgentJson.MAPPER.readTree(content).path("issues").isArray();
        } catch (Exception e) {
            return false;
        }
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

package com.zhouziheng.review.agent;

import com.zhouziheng.gitee.GiteeClient;
import com.zhouziheng.review.agent.dto.AgentToolSpec;
import com.zhouziheng.review.context.RepoFileIndex;
import org.springframework.util.StringUtils;
import tools.jackson.databind.JsonNode;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 交给模型的工具集，以及真正执行它们的地方。
 * <p>
 * 模型只是"说"它想调哪个工具、参数填什么，一个字节都执行不了；
 * 这个类才是它的手。三个工具的分工是一个组合拳：
 * <ul>
 *   <li>{@code find_type} —— 类名换路径。查的是已经建好的仓库索引，不发一次网络请求、瞬时返回。</li>
 *   <li>{@code list_files} —— 关键词换候选路径。同样只查索引，但它接受的是模糊输入，
 *       用来补 {@code find_type} 那条"必须给精确类名、猜错就返回空"的死路。</li>
 *   <li>{@code read_file} —— 路径换内容。只有这一步要真去 Gitee 取文件。</li>
 * </ul>
 * 之所以敢让模型自由读文件，而不是把整个仓库塞给它，是因为"取文件"这一步是真的有代价的：
 * 索引虽然免费，但每个文件的正文都得单独一次 HTTP。
 * <p>
 * 一次评审用一个实例，内部缓存本次已经读过的文件 ——
 * 模型重复读同一个文件（它确实会这么干）时不该再花一次网络请求。
 */
public class AgentToolkit {

    /**
     * 单次读取的行数硬上限。
     * 模型偶尔会要"整个文件"，几百行的类反序列化之后够用，
     * 但一个 3000 行的文件一次全塞进去会把上下文预算一次吃光。
     */
    private static final int MAX_READ_LINES = 300;

    /** 单次工具结果的字符上限，防止超长行把预算打穿 */
    private static final int MAX_RESULT_CHARS = 16_000;

    /**
     * {@code list_files} 单次最多列出的路径条数。
     * <p>
     * 关键词给得太泛时会命中几百个（比如 "shop" 在一个业务仓库里能命中 166 个），
     * 全列出来既吃预算又帮不上忙。截断的同时会把总数报出来，
     * 让模型知道自己该换一个更具体的词，而不是以为仓库里就这么多。
     */
    private static final int MAX_LIST_RESULTS = 40;

    /**
     * 每次调用都必须填的一句中文理由，直接显示在评审轨迹里给用户看。
     * <p>
     * 为什么不用模型自己的思考过程：thinking 模式下那部分在 reasoning_content 里，
     * 是英文、而且几千字符起步，既没法约束也没法读。做成必填参数才是可控的 ——
     * 模型必须给它、而且长度和语言都由这句话规定。
     */
    private static final String REASON_DESC = "用中文一句话说明你为什么调用它，40 字以内，"
            + "例如「确认这个方法在入参为空时会不会抛异常」。"
            + "这句话会原样显示在评审轨迹里给用户看，所以不要写推理过程、不要贴代码、不要用英文。";

    private final GiteeClient gitee;
    private final RepoFileIndex index;
    private final String owner;
    private final String repo;
    private final String sha;
    private final Map<String, String> fileCache = new HashMap<>();

    public AgentToolkit(GiteeClient gitee, RepoFileIndex index, String owner, String repo, String sha) {
        this.gitee = gitee;
        this.index = index;
        this.owner = owner;
        this.repo = repo;
        this.sha = sha;
    }

    /** 工具返回值。target 是这次操作的对象，单独拎出来给执行轨迹用，免得前端再去解析 arguments。 */
    public record Result(String target, String content) {
    }

    public List<AgentToolSpec> specs() {
        return List.of(
                AgentToolSpec.of("find_type",
                        "根据类名查找它在仓库中的文件路径。当你在 diff 里遇到一个不认识的类、需要知道它的定义在哪时调用。",
                        Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "reason", Map.of("type", "string", "description", REASON_DESC),
                                        "name", Map.of("type", "string", "description", "简单类名，例如 OrderItemMapper")),
                                "required", List.of("name", "reason"))),
                AgentToolSpec.of("list_files",
                        "按关键词列出仓库里的文件路径（忽略大小写，匹配路径中的任意一段）。"
                                + "不知道某个功能的实现文件叫什么、手里只有 URL 路径段 / 方法名 / 模块名时，"
                                + "先用它列出候选，再用 read_file 读候选内容。"
                                + "find_type 必须给精确类名、猜错只能拿到空结果，拿不准类名时就用这个工具。"
                                + "它只匹配文件路径，看不到文件内容。",
                        Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "reason", Map.of("type", "string", "description", REASON_DESC),
                                        "pattern", Map.of("type", "string", "description",
                                                "文件路径里的一段关键词，例如 orderItem、ServiceImpl、Controller。"
                                                        + "忽略大小写，也可以用 * 通配，例如 *Controller.java")),
                                "required", List.of("pattern", "reason"))),
                AgentToolSpec.of("read_file",
                        "读取仓库中某个文件的指定行范围，每行开头是行号。"
                                + "先用 find_type 或 list_files 拿到路径，再用这个工具读它的内容。",
                        Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "reason", Map.of("type", "string", "description", REASON_DESC),
                                        "path", Map.of("type", "string", "description", "仓库内的相对路径"),
                                        "startLine", Map.of("type", "integer", "description", "起始行，从 1 开始"),
                                        "endLine", Map.of("type", "integer", "description", "结束行，含该行")),
                                "required", List.of("path", "reason"))));
    }

    /**
     * 执行一次工具调用。
     * <p>
     * arguments 是模型自己拼出来的 JSON 字符串，属于不可信输入：
     * 可能语法错误、可能少参数、可能填了越界的行号。这里一律转成一句人能看懂的
     * 提示语返回给模型，让它自己纠正 —— 比直接抛异常中断整条链路划算得多。
     */
    public Result execute(String name, String argumentsJson) {
        JsonNode args = parse(argumentsJson);
        return switch (name) {
            case "find_type" -> findType(args);
            case "list_files" -> listFiles(args);
            case "read_file" -> readFile(args);
            default -> new Result(name, "没有这个工具：" + name);
        };
    }

    private Result findType(JsonNode args) {
        String name = args.path("name").asString("").trim();
        if (!StringUtils.hasText(name)) {
            return new Result(name, "缺少参数 name");
        }
        String path = index.resolve(name);
        if (path == null) {
            return new Result(name, "仓库索引里没有名为 " + name + " 的类。它可能是 JDK 或第三方依赖里的类型，"
                    + "也可能是拼写不对。不要假设它的实现。");
        }
        return new Result(path, path);
    }

    /**
     * 模糊查找文件路径。和 {@link #findType} 一样只查内存里的索引，不发网络请求。
     * <p>
     * 它存在的理由是 {@code find_type} 的一个硬伤：参数必须是模型已经知道且拼写正确的类名。
     * 模型在 diff 里看到一个 URL 或一个方法名时，类名是它猜出来的 —— 猜错就得到一句
     * "仓库索引里没有这个类"，然后就没了下文。实测里它甚至会因此断定"对端不在本仓库"而放弃。
     */
    private Result listFiles(JsonNode args) {
        String pattern = args.path("pattern").asString("").trim();
        if (!StringUtils.hasText(pattern)) {
            return new Result(pattern, "缺少参数 pattern");
        }

        List<String> hits = index.search(pattern);
        if (hits.isEmpty()) {
            return new Result(pattern, "仓库里没有路径匹配 " + pattern + " 的文件。这个工具只匹配文件路径，"
                    + "看不到文件内容 —— 方法名、字段名、注解、URL 字符串都匹配不到。"
                    + "要找一个方法或接口的定义在哪个文件，换成它所属的类名、所在的模块名，"
                    + "或者 URL 路径里的一段再试。");
        }

        int total = hits.size();
        List<String> shown = total > MAX_LIST_RESULTS ? hits.subList(0, MAX_LIST_RESULTS) : hits;

        StringBuilder text = new StringBuilder();
        text.append("匹配 ").append(pattern).append(" 的文件路径（共 ").append(total).append(" 个");
        if (total > MAX_LIST_RESULTS) {
            text.append("，只列出最短的 ").append(MAX_LIST_RESULTS).append(" 个");
        }
        text.append("）：\n");
        for (String path : shown) {
            text.append(path).append('\n');
        }
        if (total > MAX_LIST_RESULTS) {
            text.append("（还有 ").append(total - MAX_LIST_RESULTS)
                    .append(" 个没列出，换一个更具体的词可以缩小范围）\n");
        }
        return new Result(pattern, truncate(text.toString()));
    }

    private Result readFile(JsonNode args) {
        String path = args.path("path").asString("").trim();
        if (!StringUtils.hasText(path)) {
            return new Result(path, "缺少参数 path");
        }
        if (path.startsWith("/") || path.contains("..")) {
            return new Result(path, "path 必须是仓库内的相对路径，不能以 / 开头，也不能包含 ..");
        }

        String content = cachedContent(path);
        if (content == null) {
            return new Result(path, "读不到这个文件：" + path + "。请确认路径是否和 find_type 返回的一致。");
        }
        return new Result(path, slice(path, content, args));
    }

    /**
     * 刻意不用 computeIfAbsent：它的 lambda 执行期间会锁住哈希桶，
     * 而这里面的 lambda 会发 HTTP 请求。虽然当前 agent 循环是单线程顺序执行的、
     * 撞不上这个问题，但保持和 {@code RepoFileIndexer} 一致的写法，避免以后并发化时埋雷。
     */
    private String cachedContent(String path) {
        String cached = fileCache.get(path);
        if (cached != null) {
            return cached;
        }
        String fetched = gitee.getFileContent(owner, repo, path, sha);
        if (fetched != null) {
            fileCache.put(path, fetched);
        }
        return fetched;
    }

    /**
     * 按行号切片并加上行号前缀。
     * 行号格式刻意和 diff 里的 {@code [行号]} 区分开，避免模型把上下文文件的行号
     * 当成 diff 的行号引用到报告里 —— 那样的问题会被证据校验直接丢掉。
     */
    private String slice(String path, String content, JsonNode args) {
        String[] lines = content.split("\n", -1);
        int total = lines.length;

        int start = clamp(args.path("startLine").asInt(1), 1, total);
        int end = clamp(args.path("endLine").asInt(start + MAX_READ_LINES - 1), start,
                Math.min(total, start + MAX_READ_LINES - 1));

        StringBuilder text = new StringBuilder();
        text.append("文件：").append(path)
                .append("（共 ").append(total).append(" 行，以下是第 ").append(start).append('-').append(end).append(" 行）\n");
        for (int lineNo = start; lineNo <= end; lineNo++) {
            text.append(lineNo).append("| ").append(lines[lineNo - 1]).append('\n');
        }
        if (end < total) {
            text.append("（后面还有内容，需要的话用 startLine / endLine 继续读）\n");
        }
        return truncate(text.toString());
    }

    private JsonNode parse(String argumentsJson) {
        if (!StringUtils.hasText(argumentsJson)) {
            return AgentJson.MAPPER.createObjectNode();
        }
        try {
            return AgentJson.MAPPER.readTree(argumentsJson);
        } catch (Exception e) {
            return AgentJson.MAPPER.createObjectNode();
        }
    }

    private String truncate(String content) {
        if (content.length() <= MAX_RESULT_CHARS) {
            return content;
        }
        return content.substring(0, MAX_RESULT_CHARS) + "\n（内容过长已截断）";
    }

    private int clamp(int value, int min, int max) {
        if (value < min) {
            return min;
        }
        return Math.min(value, max);
    }
}

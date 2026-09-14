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
 * 这个类才是它的手。两个工具的分工是一个组合拳：
 * <ul>
 *   <li>{@code find_type} —— 类名换路径。查的是已经建好的仓库索引，不发一次网络请求、瞬时返回。</li>
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
                                        "name", Map.of("type", "string", "description", "简单类名，例如 ApiParameterGroup")),
                                "required", List.of("name"))),
                AgentToolSpec.of("read_file",
                        "读取仓库中某个文件的指定行范围，每行开头是行号。先用 find_type 拿到路径，再用这个工具读它的内容。",
                        Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "path", Map.of("type", "string", "description", "仓库内的相对路径"),
                                        "startLine", Map.of("type", "integer", "description", "起始行，从 1 开始"),
                                        "endLine", Map.of("type", "integer", "description", "结束行，含该行")),
                                "required", List.of("path"))));
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

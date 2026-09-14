package com.zhouziheng.review.prompt;

import com.zhouziheng.review.context.CodeContext;
import com.zhouziheng.review.diff.DiffHunk;
import com.zhouziheng.review.diff.DiffLine;
import com.zhouziheng.review.diff.FileDiff;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class ReviewPromptBuilder {

    /**
     * 提示词版本号。改动下面任何一段提示词都要把它 +1。
     * v2：加入"相关代码上下文"（上下文召回）。
     * 评审结果会被当成缓存复用，如果提示词变了还复用旧结果，
     * 会拿到一份被旧结果污染的对比数据。
     */
    public static final String PROMPT_VERSION = "v2";

    public static final String SYSTEM_PROMPT = """
            你是一位有 10 年经验的 Java 后端技术专家，负责评审团队的代码提交。

            评审纪律：
            1. 只指出真实存在的问题，宁缺毋滥。改动没有问题时就返回空的 issues 列表。
            2. 每条问题必须给出行号，行号必须是下方 diff 中标注的**新文件行号**。
            3. evidence 必须是该行代码原文，原样复制，不要改写、不要加解释。
            4. 不要评论纯格式化、纯重命名、依赖升级、自动生成的文件这类无实质影响的改动。
            5. 判断"某个字段会不会是 null""某个方法/成员存不存在"时，以「相关代码上下文」为准。
               那里没有出现的类，说明没有被召回，不要臆测它的内部实现，也不要以"可能不存在"为由报问题。
            6. 不要评审「相关代码上下文」里的代码，它只是背景资料，评审对象只有 diff。
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

    public String buildUserPrompt(String repo, String commitSha, String commitMessage,
                                  List<FileDiff> files, List<CodeContext> contexts) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("仓库：").append(repo).append('\n');
        prompt.append("commit：").append(commitSha).append('\n');
        prompt.append("提交信息：").append(commitMessage).append("\n\n");
        prompt.append("以下是本次改动的 diff。");
        prompt.append("每行开头的方括号里是该行在**新文件**中的行号，标 `·` 的是被删除的行");
        prompt.append("（新文件中已不存在，不要引用它的行号）。\n");
        prompt.append("`+` 是新增行，`-` 是删除行，空格开头是上下文行。\n\n");

        for (FileDiff file : files) {
            appendFile(prompt, file);
        }
        appendContexts(prompt, contexts);
        return prompt.toString();
    }

    /**
     * 追加被召回的相关代码。
     * <p>
     * 上下文召回的意义就在这里：模型光看 diff 里那 3 行上下文，判断不了
     * {@code parameterGroup.getLocal()} 会不会 NPE —— 它需要知道 ApiParameterGroup 里
     * 那个字段是什么类型、有没有初始化。召回就是为了补上这一块。
     * <p>
     * 但必须同时告诉模型"这是节选"，否则它会拿"没看到"当成"不存在"，反而制造新的幻觉。
     */
    private void appendContexts(StringBuilder prompt, List<CodeContext> contexts) {
        if (contexts == null || contexts.isEmpty()) {
            return;
        }
        prompt.append("### 相关代码上下文\n");
        prompt.append("以下是本次 diff 引用到的类的定义节选，只保留包名、类声明、字段声明与方法签名，");
        prompt.append("**方法体已省略**。它只是背景资料，不是评审对象。\n");
        prompt.append("没出现在这里的类就是没被召回，不要根据它来推断。\n\n");
        for (CodeContext context : contexts) {
            prompt.append("#### 文件：").append(context.path()).append("\n");
            prompt.append("```java\n").append(context.skeleton());
            if (!context.skeleton().endsWith("\n")) {
                prompt.append('\n');
            }
            prompt.append("```\n\n");
        }
    }

    /** 召回段占用的字符数，只用于日志 */
    public int contextSectionLength(List<CodeContext> contexts) {
        StringBuilder section = new StringBuilder();
        appendContexts(section, contexts);
        return section.length();
    }

    /**
     * 单个文件那一段的字符数。只用于日志统计，让"token 花在哪个文件上"一目了然，
     * 又不用把几万字的 diff 全打到日志里。
     */
    public int fileSectionLength(FileDiff file) {
        StringBuilder section = new StringBuilder();
        appendFile(section, file);
        return section.length();
    }

    private void appendFile(StringBuilder prompt, FileDiff file) {
        prompt.append("### 文件：").append(file.path())
                .append("（").append(file.status())
                .append("，+").append(file.additions())
                .append(" -").append(file.deletions()).append("）\n");
        prompt.append("```diff\n");
        for (DiffHunk hunk : file.hunks()) {
            prompt.append("@@ 新文件第 ").append(hunk.newStart()).append(" 行起 @@\n");
            for (DiffLine line : hunk.lines()) {
                prompt.append(formatLine(line));
            }
        }
        prompt.append("```\n\n");
    }

    private String formatLine(DiffLine line) {
        String no = line.newLineNo() < 0 ? "  ·" : String.format("%3d", line.newLineNo());
        return "[" + no + "] " + line.type() + line.content() + "\n";
    }
}

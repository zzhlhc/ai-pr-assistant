package com.zhouziheng.review.prompt;

import com.zhouziheng.review.diff.DiffHunk;
import com.zhouziheng.review.diff.DiffLine;
import com.zhouziheng.review.diff.FileDiff;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class ReviewPromptBuilder {

    public static final String SYSTEM_PROMPT = """
            你是一位有 10 年经验的 Java 后端技术专家，负责评审团队的代码提交。

            评审纪律：
            1. 只指出真实存在的问题，宁缺毋滥。改动没有问题时就返回空的 issues 列表。
            2. 每条问题必须给出行号，行号必须是下方 diff 中标注的**新文件行号**。
            3. evidence 必须是该行代码原文，原样复制，不要改写、不要加解释。
            4. 不要评论纯格式化、纯重命名、依赖升级、自动生成的文件这类无实质影响的改动。

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

    public String buildUserPrompt(String repo, String commitSha, String commitMessage, List<FileDiff> files) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("仓库：").append(repo).append('\n');
        prompt.append("commit：").append(commitSha).append('\n');
        prompt.append("提交信息：").append(commitMessage).append("\n\n");
        prompt.append("以下是本次改动的 diff。");
        prompt.append("每行开头的方括号里是该行在**新文件**中的行号，标 `·` 的是被删除的行");
        prompt.append("（新文件中已不存在，不要引用它的行号）。\n");
        prompt.append("`+` 是新增行，`-` 是删除行，空格开头是上下文行。\n\n");

        for (FileDiff file : files) {
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
        return prompt.toString();
    }

    private String formatLine(DiffLine line) {
        String no = line.newLineNo() < 0 ? "  ·" : String.format("%3d", line.newLineNo());
        return "[" + no + "] " + line.type() + line.content() + "\n";
    }
}

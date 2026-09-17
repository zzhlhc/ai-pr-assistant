package com.zhouziheng.review.prompt;

import com.zhouziheng.review.diff.DiffHunk;
import com.zhouziheng.review.diff.DiffLine;
import com.zhouziheng.review.diff.FileDiff;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 把 diff 渲染成提示词里的数据段。
 * <p>
 * 只负责渲染，不掺任何召回逻辑：模型要看哪些额外代码，由它自己在循环里用工具去取。
 * 这里每一行都带新文件行号，模型报回来的行号才能回溯验证。
 */
@Component
public class ReviewPromptBuilder {

    public String buildUserPrompt(String repo, String commitSha, String commitMessage,
                                  List<FileDiff> files) {
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
        return prompt.toString();
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

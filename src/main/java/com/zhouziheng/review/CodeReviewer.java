package com.zhouziheng.review;

import com.zhouziheng.review.diff.FileDiff;
import com.zhouziheng.review.model.ReviewReport;
import com.zhouziheng.review.prompt.ReviewPromptBuilder;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 把 diff 交给大模型，拿回结构化的评审结果。
 * entity(Class) 会由 Spring AI 自动生成 JSON Schema 并约束模型输出，
 * 解析失败会自动重试，省掉了手写字符串解析。
 */
@Component
public class CodeReviewer {

    private final ChatClient chatClient;
    private final ReviewPromptBuilder promptBuilder;

    public CodeReviewer(ChatClient.Builder chatClientBuilder, ReviewPromptBuilder promptBuilder) {
        this.chatClient = chatClientBuilder.build();
        this.promptBuilder = promptBuilder;
    }

    public ReviewReport review(String repo, String commitSha, String commitMessage, List<FileDiff> files) {
        return chatClient.prompt()
                .system(ReviewPromptBuilder.SYSTEM_PROMPT)
                .user(promptBuilder.buildUserPrompt(repo, commitSha, commitMessage, files))
                .call()
                .entity(ReviewReport.class);
    }
}

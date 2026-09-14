package com.zhouziheng.review;

import com.zhouziheng.gitee.GiteeClient;
import com.zhouziheng.gitee.dto.GiteeCommit;
import com.zhouziheng.gitee.dto.GiteeFile;
import com.zhouziheng.review.context.CodeContext;
import com.zhouziheng.review.context.CodeContextRecaller;
import com.zhouziheng.review.context.FileImportResolver;
import com.zhouziheng.review.context.RecallPreview;
import com.zhouziheng.review.context.RecallResult;
import com.zhouziheng.review.agent.AgentReviewer;
import com.zhouziheng.review.context.RecalledFile;
import com.zhouziheng.review.context.ReferencedSymbols;
import com.zhouziheng.review.context.SymbolExtractor;
import com.zhouziheng.review.diff.DiffParser;
import com.zhouziheng.review.diff.FileDiff;
import com.zhouziheng.review.model.ReviewIssue;
import com.zhouziheng.review.model.ReviewReport;
import com.zhouziheng.review.model.ReviewResult;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;

@Service
public class ReviewService {

    /** 二进制、压缩包、锁文件、压缩后的静态资源，评审它们没有意义还费 token */
    private static final Set<String> SKIPPED_SUFFIXES = Set.of(
            ".lock", ".min.js", ".min.css", ".map", ".jar", ".war", ".zip", ".pdf",
            ".png", ".jpg", ".jpeg", ".gif", ".svg", ".ico", ".woff", ".woff2", ".ttf");

    private final GiteeClient giteeClient;
    private final CodeReviewer codeReviewer;
    private final AgentReviewer agentReviewer;
    private final SymbolExtractor symbolExtractor;
    private final FileImportResolver fileImportResolver;
    private final CodeContextRecaller contextRecaller;

    public ReviewService(GiteeClient giteeClient, CodeReviewer codeReviewer, AgentReviewer agentReviewer,
                         SymbolExtractor symbolExtractor, FileImportResolver fileImportResolver,
                         CodeContextRecaller contextRecaller) {
        this.giteeClient = giteeClient;
        this.codeReviewer = codeReviewer;
        this.agentReviewer = agentReviewer;
        this.symbolExtractor = symbolExtractor;
        this.fileImportResolver = fileImportResolver;
        this.contextRecaller = contextRecaller;
    }

    public ReviewResult review(String repo, String commitSha) {
        return review(repo, commitSha, ReviewOptions.DEFAULT, stage -> {
        });
    }

    /**
     * @param options 召回参数（最多召回几个文件、字符预算）
     * @param onStage 阶段回调。一次评审要跑一分钟以上，把"当前在干什么"暴露出去，
     *                前端的进度提示才不会从头到尾卡在同一句话上。
     */
    public ReviewResult review(String repo, String commitSha, ReviewOptions options, Consumer<String> onStage) {
        String[] parts = splitRepo(repo);
        onStage.accept("拉取 commit 与 diff");
        GiteeCommit commit = giteeClient.getCommit(parts[0], parts[1], commitSha);
        List<FileDiff> diffs = toFileDiffs(commit);

        // 两条链路拿到的 diff 完全一样，唯一的差异是"上下文从哪来"。
        // 这样同一个 commit 换策略跑出来的两份报告才有可比性。
        return options.isAgent()
                ? agentReview(parts, repo, commitSha, commit, diffs, options, onStage)
                : preloadReview(parts, repo, commitSha, commit, diffs, options, onStage);
    }

    /**
     * agent 式：上下文由模型自己在循环里读出来，我们只提供查找和读取的工具。
     * 耗时记的是整条链路（含建索引和每一轮往返），因为它就是要拿来和预塞式的单次调用对比的。
     */
    private ReviewResult agentReview(String[] parts, String repo, String commitSha, GiteeCommit commit,
                                     List<FileDiff> diffs, ReviewOptions options, Consumer<String> onStage) {
        long startMillis = System.currentTimeMillis();
        AgentReviewer.Outcome outcome = agentReviewer.review(parts[0], parts[1], commitSha,
                commit.commit().message(), diffs, options, onStage);
        long elapsedMillis = System.currentTimeMillis() - startMillis;

        onStage.accept("校验行号并整理报告");
        return new ReviewResult(verify(outcome.report(), diffs), outcome.usage(), elapsedMillis,
                List.of(), outcome.trace());
    }

    /**
     * 预塞式：由我们先算出 diff 引用了哪些类，把它们的骨架一次性塞进提示词。
     * 召回失败只影响评审质量、不影响评审能否进行，所以那一段是静默退化的。
     */
    private ReviewResult preloadReview(String[] parts, String repo, String commitSha, GiteeCommit commit,
                                       List<FileDiff> diffs, ReviewOptions options, Consumer<String> onStage) {
        onStage.accept("召回相关代码定义");
        List<CodeContext> contexts = contextRecaller.recall(parts[0], parts[1], commitSha,
                resolveSymbols(parts[0], parts[1], commitSha, diffs), options);

        onStage.accept("模型评审中（" + diffs.size() + " 个文件）");
        ReviewResult result = codeReviewer.review(repo, commitSha, commit.commit().message(), diffs, contexts);

        onStage.accept("校验行号并整理报告");
        return new ReviewResult(verify(result.report(), diffs), result.usage(), result.elapsedMillis(),
                contexts.stream().map(RecalledFile::of).toList());
    }

    /**
     * 只跑"拉 diff + 召回"，不调用模型。
     * <p>
     * 召回链路是纯读取加字符串处理，秒级返回且不产生费用，所以单独暴露出来调参数：
     * 先看召回结果合不合适，再决定要不要拿这组参数跑完整评审。
     */
    public RecallPreview preview(String repo, String commitSha, ReviewOptions options) {
        String[] parts = splitRepo(repo);
        GiteeCommit commit = giteeClient.getCommit(parts[0], parts[1], commitSha);
        List<FileDiff> diffs = toFileDiffs(commit);

        RecallResult recall = contextRecaller.recallDetailed(parts[0], parts[1], commitSha,
                resolveSymbols(parts[0], parts[1], commitSha, diffs), options);

        List<RecalledFile> files = recall.contexts().stream().map(RecalledFile::of).toList();
        int contextChars = files.stream().mapToInt(RecalledFile::chars).sum();
        int rawChars = files.stream().mapToInt(RecalledFile::rawChars).sum();
        return new RecallPreview(recall.candidateFiles(), files, contextChars, rawChars,
                options.totalBudgetOrDefault(), options.signature(), recall.elapsedMillis());
    }

    /**
     * 从 diff 提取符号，再补一道"被改动文件自己的 import 清单"。
     * <p>
     * 后者的必要性和为什么不能靠扩大 diff 扫描范围来解决，见 {@link FileImportResolver} 的类注释。
     */
    private ReferencedSymbols resolveSymbols(String owner, String repo, String sha, List<FileDiff> diffs) {
        ReferencedSymbols symbols = symbolExtractor.extract(diffs);
        return symbols.withImports(fileImportResolver.resolve(owner, repo, sha, diffs));
    }

    /**
     * 证据回溯：模型给出的 file + line 必须能在 diff 里找到，找不到的条目直接丢弃。
     * 这是抑制幻觉最有效的一道防线。
     * <p>
     * 顺带丢掉没有正文的条目：这类条目落库时会撞上 issue 列的 NOT NULL 约束，
     * 让整次评审在最后一秒失败。一条没有描述的"问题"本来也没有任何价值。
     */
    private ReviewReport verify(ReviewReport report, List<FileDiff> diffs) {
        Map<String, Set<Integer>> validLines = diffs.stream()
                .collect(Collectors.toMap(FileDiff::path, FileDiff::newLineNumbers));

        List<ReviewIssue> issues = report.issues() == null ? List.of() : report.issues();
        List<ReviewIssue> verified = issues.stream()
                .filter(issue -> StringUtils.hasText(issue.issue()))
                .filter(issue -> validLines.getOrDefault(issue.file(), Set.of()).contains(issue.line()))
                .toList();

        return new ReviewReport(report.summary(), verified);
    }

    private List<FileDiff> toFileDiffs(GiteeCommit commit) {
        return commit.files().stream()
                .filter(this::isReviewable)
                .map(file -> DiffParser.parse(file.filename(), file.status(),
                        file.additions(), file.deletions(), file.patch()))
                .toList();
    }

    private boolean isReviewable(GiteeFile file) {
        if (file.patch() == null || file.patch().isBlank()) {
            return false;
        }
        if (Boolean.TRUE.equals(file.truncated()) || "removed".equals(file.status())) {
            return false;
        }
        String name = file.filename().toLowerCase();
        return SKIPPED_SUFFIXES.stream().noneMatch(name::endsWith);
    }

    /**
     * 支持 owner/repo、https://gitee.com/owner/repo、git@gitee.com:owner/repo.git 三种写法。
     */
    private String[] splitRepo(String repo) {
        String path = repo.trim()
                .replaceFirst("^git@gitee\\.com:", "")
                .replaceFirst("^https?://gitee\\.com/", "")
                .replaceFirst("\\.git$", "")
                .replaceAll("^/+", "")
                .replaceAll("/+$", "");

        String[] parts = path.split("/");
        if (parts.length < 2) {
            throw new IllegalArgumentException("仓库地址解析失败，请使用 owner/repo 或 https://gitee.com/owner/repo：" + repo);
        }
        return parts;
    }
}

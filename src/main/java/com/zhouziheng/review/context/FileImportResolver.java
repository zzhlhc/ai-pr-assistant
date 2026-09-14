package com.zhouziheng.review.context;

import com.zhouziheng.gitee.GiteeClient;
import com.zhouziheng.review.diff.DiffHunk;
import com.zhouziheng.review.diff.DiffLine;
import com.zhouziheng.review.diff.FileDiff;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 从被改动文件的完整 import 清单里补出依赖。
 * <p>
 * 解决的是 {@link SymbolExtractor} 的一个盲区：diff 只是一段片段，
 * 一个文件的 import 都在文件头部，如果改动发生在方法体里，那些 import 根本不会出现在 diff 里。
 * 实测一个 commit 改了 16 个文件，只有 5 个能扫到 import，其余 11 个的依赖完全看不见。
 * <p>
 * 做法是把这个文件的完整内容读下来，拿到它真正的 import 清单（编译器级别的事实），
 * 再用 diff 里出现的小写标识符去匹配：
 *
 * <pre>
 *   diff 新增行：  return commonMethodService.doSomething(req);
 *   文件 import：  import coopwire.provider.deploy.biz.service.common.CommonMethodService;
 *   匹配：         commonMethodService → CommonMethodService → 命中，召回它的定义
 * </pre>
 *
 * 关键在于<b>候选集被限定在这个文件 import 过的几十个类里</b>，
 * 而不是仓库全部的 4881 个类，所以既不会像"小写转大驼峰查全仓库"那样误命中
 * （Query、Type、Builder 这类通用词撞名），又不受"import 行必须在 diff 里"的限制。
 */
@Component
public class FileImportResolver {

    private static final Logger log = LoggerFactory.getLogger(FileImportResolver.class);

    /** 第一个可选组是 static，静态导入的最后一段是成员名，不是类名 */
    private static final Pattern IMPORT =
            Pattern.compile("^\\s*import\\s+(static\\s+)?([A-Za-z_][\\w.]*)\\s*;");

    /** diff 里的小写标识符：变量名、方法名都在里面，噪声靠"是否命中本文件的 import 清单"来过滤 */
    private static final Pattern LOWER_NAME = Pattern.compile("\\b([a-z][A-Za-z0-9_]{2,})\\b");

    /** 单个文件最多补几个依赖：一个巨型文件可能有几十个 import，全补会把候选池冲掉 */
    private static final int MAX_PER_FILE = 8;

    private final GiteeClient gitee;

    public FileImportResolver(GiteeClient gitee) {
        this.gitee = gitee;
    }

    /**
     * @return 补出来的依赖全限定名（去重）。读文件失败或解析不了就跳过，
     * 这一步是给召回查漏补缺的，不能让它影响主流程
     */
    public List<String> resolve(String owner, String repo, String sha, List<FileDiff> diffs) {
        Set<String> changedTypes = changedTypes(diffs);
        Set<String> found = new LinkedHashSet<>();

        for (FileDiff diff : diffs) {
            if (!diff.path().endsWith(".java")) {
                continue;
            }
            try {
                found.addAll(resolveOne(owner, repo, sha, diff, changedTypes));
            } catch (Exception e) {
                log.debug("解析文件依赖失败 | path={} | {}", diff.path(), e.getMessage());
            }
        }

        if (!found.isEmpty()) {
            log.info("从被改动文件的 import 清单补充依赖 | 数量={} | {}", found.size(), found);
        }
        return List.copyOf(found);
    }

    private List<String> resolveOne(String owner, String repo, String sha, FileDiff diff, Set<String> changedTypes) {
        String source = gitee.getFileContent(owner, repo, diff.path(), sha);
        if (source == null) {
            return List.of();
        }

        Map<String, String> imports = parseImports(source);
        if (imports.isEmpty()) {
            return List.of();
        }

        Set<String> used = new LinkedHashSet<>();
        for (String name : lowerNames(diff)) {
            String camel = Character.toUpperCase(name.charAt(0)) + name.substring(1);
            // 本次改动自己的类不用召回，完整内容已经在 diff 里
            if (changedTypes.contains(camel)) {
                continue;
            }
            String fqn = imports.get(camel);
            if (fqn != null) {
                used.add(fqn);
                if (used.size() >= MAX_PER_FILE) {
                    break;
                }
            }
        }
        return List.copyOf(used);
    }

    /** 简单类名 → 全限定名 */
    private Map<String, String> parseImports(String source) {
        Map<String, String> imports = new HashMap<>();
        for (String line : source.split("\n", -1)) {
            if (!line.trim().startsWith("import")) {
                continue;
            }
            Matcher matcher = IMPORT.matcher(line);
            if (!matcher.find()) {
                continue;
            }
            String fqn = matcher.group(2);
            if (matcher.group(1) != null) {
                int dot = fqn.lastIndexOf('.');
                if (dot < 0) {
                    continue;
                }
                fqn = fqn.substring(0, dot);
            }
            imports.putIfAbsent(simpleNameOfFqn(fqn), fqn);
        }
        return imports;
    }

    /** diff 里出现过的小写标识符（去重）。删除行不算，它在新文件里已经不存在了 */
    private Set<String> lowerNames(FileDiff diff) {
        Set<String> names = new LinkedHashSet<>();
        for (DiffHunk hunk : diff.hunks()) {
            for (DiffLine line : hunk.lines()) {
                if (line.type() == '-') {
                    continue;
                }
                Matcher matcher = LOWER_NAME.matcher(line.content());
                while (matcher.find()) {
                    names.add(matcher.group(1));
                }
            }
        }
        return names;
    }

    private Set<String> changedTypes(List<FileDiff> diffs) {
        Set<String> types = new LinkedHashSet<>();
        for (FileDiff diff : diffs) {
            String path = diff.path();
            if (!path.endsWith(".java")) {
                continue;
            }
            String name = path.substring(path.lastIndexOf('/') + 1);
            types.add(name.substring(0, name.length() - ".java".length()));
        }
        return types;
    }

    private String simpleNameOfFqn(String fqn) {
        int dot = fqn.lastIndexOf('.');
        return dot < 0 ? fqn : fqn.substring(dot + 1);
    }
}

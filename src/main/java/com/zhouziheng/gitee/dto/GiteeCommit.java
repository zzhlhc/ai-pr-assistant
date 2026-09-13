package com.zhouziheng.gitee.dto;

import java.util.List;

public record GiteeCommit(String sha,
                          GiteeCommitInfo commit,
                          List<GiteeFile> files,
                          Boolean truncated) {
}

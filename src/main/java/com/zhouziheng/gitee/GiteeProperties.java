package com.zhouziheng.gitee;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "gitee")
public record GiteeProperties(String token, String baseUrl) {
}

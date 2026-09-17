package com.zhouziheng.config;

import com.zhouziheng.review.agent.AgentChatClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.model.openai.autoconfigure.OpenAiChatProperties;
import org.springframework.ai.model.openai.autoconfigure.OpenAiCommonProperties;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 启动时打印实际生效的模型配置。
 * 配置写错时（模型名不对、超时没生效、密钥是占位符）报错信息往往很间接，
 * 先把最终生效的值打出来，能省掉大量猜测。
 */
@Component
public class ModelStartupLogger implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ModelStartupLogger.class);

    private final OpenAiChatProperties chatProperties;
    private final OpenAiCommonProperties commonProperties;

    public ModelStartupLogger(OpenAiChatProperties chatProperties, OpenAiCommonProperties commonProperties) {
        this.chatProperties = chatProperties;
        this.commonProperties = commonProperties;
    }

    @Override
    public void run(ApplicationArguments args) {
        log.info("""
                        
                        ┌─ 模型配置（实际生效值）────────────────────────────
                        │ model         = {}
                        │ baseUrl       = {}
                        │ 请求超时(生效) = {}
                        │ chat.timeout  = {}（仅绑定，不生效）
                        │ common.timeout= {}
                        │ maxRetries    = {}
                        │ apiKey        = {}
                        └───────────────────────────────────────────────""",
                chatProperties.getModel(),
                commonProperties.getBaseUrl(),
                AgentChatClient.READ_TIMEOUT,
                chatProperties.getTimeout(),
                commonProperties.getTimeout(),
                chatProperties.getMaxRetries(),
                mask(commonProperties.getApiKey()));
    }

    private String mask(String apiKey) {
        if (apiKey == null || apiKey.length() < 10) {
            return "(未配置或过短)";
        }
        return apiKey.substring(0, 6) + "****" + apiKey.substring(apiKey.length() - 4);
    }
}

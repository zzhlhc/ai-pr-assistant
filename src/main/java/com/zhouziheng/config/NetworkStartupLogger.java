package com.zhouziheng.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.net.ProxySelector;
import java.net.URI;
import java.util.Map;
import java.util.TreeMap;

/**
 * 启动时打印进程实际看到的代理配置。
 * 排查"请求莫名走了 127.0.0.1:7890 这种本地代理"时，翻配置文件是没用的
 * （IDEA 的运行配置、环境变量、系统属性都可能是来源），直接看 JVM 里的最终值最快。
 * <p>
 * 注意这里打印的只是"线索"。真正消费这些值的是 Spring Boot 4 的
 * ReactorHttpClientBuilder —— 它无条件调用了 reactor-netty 的
 * ClientTransport#proxyWithSystemProperties()，而后者只读 System.getProperties()。
 * 所以环境变量 HTTPS_PROXY 不会生效，-Dhttps.proxyHost 会，且对 gitee、deepseek
 * 这类"不需要代理"的域名同样生效。
 */
@Component
public class NetworkStartupLogger implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(NetworkStartupLogger.class);

    private static final String[] PROXY_PROPERTIES = {
            "http.proxyHost", "http.proxyPort",
            "https.proxyHost", "https.proxyPort",
            "socksProxyHost", "socksProxyPort",
            "java.net.useSystemProxies", "http.nonProxyHosts",
    };

    @Override
    public void run(ApplicationArguments args) {
        Map<String, String> properties = new TreeMap<>();
        for (String key : PROXY_PROPERTIES) {
            properties.put(key, String.valueOf(System.getProperty(key)));
        }

        Map<String, String> envs = new TreeMap<>();
        System.getenv().forEach((key, value) -> {
            if (key.toLowerCase().contains("proxy")) {
                envs.put(key, value);
            }
        });

        log.info("""
                        
                        ┌─ 进程代理配置 ────────────────────────────────
                        │ 系统属性 = {}
                        │ 环境变量 = {}
                        │ JVM -D 参数 = {}
                        │ Selector = {}
                        │ gitee.com → {}
                        └──────────────────────────────────────────────""",
                properties,
                envs.isEmpty() ? "（无）" : envs,
                jvmArguments(),
                ProxySelector.getDefault().getClass().getName(),
                resolve("https://gitee.com"));
    }

    /**
     * JVM 的启动参数。代理只可能来自 -D，所以这里过滤一下更好读。
     */
    private String jvmArguments() {
        return ProcessHandle.current().info().arguments()
                .map(arguments -> {
                    StringBuilder sb = new StringBuilder();
                    for (String argument : arguments) {
                        if (argument.startsWith("-D")) {
                            sb.append(sb.isEmpty() ? "" : " ").append(argument);
                        }
                    }
                    return sb.isEmpty() ? "（无）" : sb.toString();
                })
                .orElse("（读取不到）");
    }

    private String resolve(String url) {
        try {
            return ProxySelector.getDefault().select(new URI(url)).toString();
        } catch (Exception e) {
            return "查询失败：" + e;
        }
    }
}

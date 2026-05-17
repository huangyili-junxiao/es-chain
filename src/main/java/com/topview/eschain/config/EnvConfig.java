package com.topview.eschain.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 统一配置类
 * 对应 application.yml 中的 "app" 节点
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "app")
public class EnvConfig {

    private EsConfig es = new EsConfig();
    private LlmConfig llm = new LlmConfig();

    public EsConfig getEs() {
        return es;
    }

    public void setEs(EsConfig es) {
        this.es = es;
    }

    public LlmConfig getLlm() {
        return llm;
    }

    public void setLlm(LlmConfig llm) {
        this.llm = llm;
    }

    // 内部类：对应 app.es
    @Data
    public static class EsConfig {
        private String host;
        private int port;
        private String scheme;
        private int connectionTimeout;
        private int socketTimeout;

        public String getHost() {
            return host;
        }

        public void setHost(String host) {
            this.host = host;
        }

        public int getPort() {
            return port;
        }

        public void setPort(int port) {
            this.port = port;
        }

        public String getScheme() {
            return scheme;
        }

        public void setScheme(String scheme) {
            this.scheme = scheme;
        }

        public int getConnectionTimeout() {
            return connectionTimeout;
        }

        public void setConnectionTimeout(int connectionTimeout) {
            this.connectionTimeout = connectionTimeout;
        }

        public int getSocketTimeout() {
            return socketTimeout;
        }

        public void setSocketTimeout(int socketTimeout) {
            this.socketTimeout = socketTimeout;
        }

        // 拼接完整 URL 的便捷方法
        public String getFullUrl() {
            return String.format("%s://%s:%d", scheme, host, port);
        }
    }

    // 内部类：对应 app.llm
    @Data
    public static class LlmConfig {
        private String apiKey;
        private String baseUrl;
        private String model;
        private int timeout;

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = model;
        }

        public int getTimeout() {
            return timeout;
        }

        public void setTimeout(int timeout) {
            this.timeout = timeout;
        }
    }
}

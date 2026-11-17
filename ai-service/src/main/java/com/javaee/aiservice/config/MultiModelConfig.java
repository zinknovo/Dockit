package com.javaee.aiservice.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.HashMap;
import java.util.Map;

/**
 * 多模型配置：spring.ai.models.list.{code} 声明每个模型的 provider、端点与模型名映射。
 * provider 可替换——切换厂商只需改配置，无需改代码。
 */
@Configuration
@ConfigurationProperties(prefix = "spring.ai.models")
public class MultiModelConfig {

    /** 默认模型 code，只来自 spring.ai.models.default-model，代码里不另写一份 */
    private String defaultModel;

    /** 模型注册表：code -> 模型配置 */
    private Map<String, ModelConfig> list = new HashMap<>();

    public String getDefaultModel() {
        return defaultModel;
    }

    public void setDefaultModel(String defaultModel) {
        this.defaultModel = defaultModel;
    }

    public Map<String, ModelConfig> getList() {
        return list;
    }

    public void setList(Map<String, ModelConfig> list) {
        this.list = list;
    }

    public static class ModelConfig {
        /** provider 名称，对应 AIServiceProvider 的 name()，默认 openai-compatible */
        private String provider = "openai-compatible";
        /** 显示名（前端模型列表展示），为空时回退到注册表 key（code） */
        private String name;
        private String apiKey;
        private String model;
        private String baseUrl;
        /**
         * 实际调用 provider API 时使用的模型名。
         * 为空时回退到 model（再为空时回退到 code）。
         * 用于把内部模型标识映射到不同厂商的实际模型名（内部标识与端点实际名不一致时配置）。
         */
        private String apiModelName;
        private boolean enabled = true;

        public String getProvider() {
            return provider;
        }

        public void setProvider(String provider) {
            this.provider = provider;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = model;
        }

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getApiModelName() {
            return apiModelName;
        }

        public void setApiModelName(String apiModelName) {
            this.apiModelName = apiModelName;
        }

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }
}

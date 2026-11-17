package com.javaee.aiservice;

import com.javaee.aiservice.agent.ChatProvider;
import com.javaee.aiservice.agent.ChatService;
import com.javaee.aiservice.factory.AIServiceFactory;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@SpringBootTest
public class AllModelsTest {

    private static final Logger log = LoggerFactory.getLogger(AllModelsTest.class);

    @Autowired
    private AIServiceFactory aiServiceFactory;

    @Autowired
    private ChatService chatService;

    private static final String TEST_PROMPT = "请用100字以内介绍一下你自己";

    @Test
    public void testAllModels() {
        log.info("========================================");
        log.info("开始测试所有可用模型");
        log.info("========================================");

        Map<String, String> results = new HashMap<>();
        List<String> failedModels = new ArrayList<>();

        // 获取所有可用模型（配置注册表）
        Map<String, ChatProvider> services = aiServiceFactory.getAllServices();
        assertFalse(services.isEmpty(), "注册表为空：spring.ai.models 没有绑定进来");

        for (Map.Entry<String, ChatProvider> entry : services.entrySet()) {
            String code = entry.getKey();
            ChatProvider service = entry.getValue();

            if (!service.isAvailable()) {
                log.warn("模型 {} 已禁用，跳过测试", service.getModelName());
                continue;
            }

            log.info("----------------------------------------");
            log.info("测试模型: {} ({})", service.getModelName(), code);

            try {
                String response = chatService.callChatApiWithModelCode(TEST_PROMPT, code);

                log.info("✓ 模型 {} 调用成功!", service.getModelName());
                log.info("响应: {}", response);

                results.put(code, response);

            } catch (Exception e) {
                log.error("✗ 模型 {} 调用失败!", service.getModelName(), e);
                failedModels.add(code);
            }
        }

        log.info("========================================");
        log.info("测试总结");
        log.info("========================================");
        log.info("成功模型: {}/{}", results.size(), services.size());

        if (!results.isEmpty()) {
            log.info("成功的模型:");
            results.keySet().forEach(code -> log.info("  - {} ({})", services.get(code).getModelName(), code));
        }

        if (!failedModels.isEmpty()) {
            log.warn("失败的模型:");
            failedModels.forEach(code -> log.warn("  - {} ({})", services.get(code).getModelName(), code));
        }

        log.info("========================================");
    }
}

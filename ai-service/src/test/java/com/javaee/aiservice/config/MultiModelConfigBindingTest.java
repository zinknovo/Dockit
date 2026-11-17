package com.javaee.aiservice.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.List;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

/**
 * 模型注册表必须真的绑定进 {@link MultiModelConfig}。code 带点（如 glm-5.3）时 YAML 键要写成
 * "[glm-5.3]"，否则 Spring 把点当层级拆开，注册表静默变空，AllModelsTest 也会以 0/0 通过。
 */
class MultiModelConfigBindingTest {

    @ParameterizedTest
    @ValueSource(strings = {"application.yml", "application-local.yml"})
    void everyModelBindsAndTheDefaultIsOneOfThem(String file) throws IOException {
        List<PropertySource<?>> sources = new YamlPropertySourceLoader().load(file, new ClassPathResource(file));
        MultiModelConfig config = new Binder(ConfigurationPropertySources.from(sources))
                .bind("spring.ai.models", MultiModelConfig.class)
                .get();

        assertThat(config.getList()).containsOnlyKeys("glm-5.3", "glm-5.3-flashx", "glm-5.3-flash");
        assertThat(config.getDefaultModel()).isEqualTo("glm-5.3-flashx");
        assertThat(config.getList()).containsKey(config.getDefaultModel());
        assertThat(config.getList().get("glm-5.3-flashx").getApiModelName()).isEqualTo("glm-5.3-flashx");
    }
}

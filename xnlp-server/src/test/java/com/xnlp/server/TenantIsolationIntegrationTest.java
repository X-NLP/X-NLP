package com.xnlp.server;

import com.xnlp.core.config.ModelConfig;
import com.xnlp.core.eval.EvaluationDataset;
import com.xnlp.core.eval.EvaluationEntry;
import com.xnlp.core.repository.DatasetRepository;
import com.xnlp.core.repository.ModelConfigRepository;
import com.xnlp.server.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {
                "spring.datasource.url=jdbc:h2:mem:xnlp-tenant-test;MODE=MySQL;DB_CLOSE_DELAY=-1",
                "spring.datasource.username=sa",
                "spring.datasource.password=",
                "spring.sql.init.mode=never",
                "spring.ai.model.chat=none",
                "spring.ai.openai.api-key=test-key"
        })
@ActiveProfiles("h2")
@DisplayName("X-NLP tenant isolation")
class TenantIsolationIntegrationTest {

    @Autowired
    private DatasetRepository datasets;

    @Autowired
    private ModelConfigRepository modelConfigs;

    @AfterEach
    void clearTenantContext() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("JDBC repositories only expose the current tenant's records")
    void repositoriesAreTenantScoped() {
        TenantContext.runWithTenant("tenant-a", () -> {
            datasets.save(dataset("dataset-a", "Tenant A"));
            modelConfigs.save(model("shared-name", "model-a"));
        });
        TenantContext.runWithTenant("tenant-b", () -> {
            datasets.save(dataset("dataset-b", "Tenant B"));
            modelConfigs.save(model("shared-name", "model-b"));
        });

        TenantContext.runWithTenant("tenant-a", () -> {
            assertThat(datasets.findAll()).extracting(EvaluationDataset::getId)
                    .containsExactly("dataset-a");
            assertThat(modelConfigs.findByName("shared-name")).get()
                    .extracting(ModelConfig::getModelName).isEqualTo("model-a");
        });
        TenantContext.runWithTenant("tenant-b", () -> {
            assertThat(datasets.findAll()).extracting(EvaluationDataset::getId)
                    .containsExactly("dataset-b");
            assertThat(modelConfigs.findByName("shared-name")).get()
                    .extracting(ModelConfig::getModelName).isEqualTo("model-b");
        });
    }

    private static EvaluationDataset dataset(String id, String name) {
        EvaluationDataset dataset = new EvaluationDataset();
        dataset.setId(id);
        dataset.setName(name);
        dataset.setEntries(List.of(new EvaluationEntry("entry-" + id, "input", "output")));
        return dataset;
    }

    private static ModelConfig model(String name, String modelName) {
        ModelConfig config = new ModelConfig();
        config.setName(name);
        config.setModelName(modelName);
        config.setModelPath(modelName);
        config.setProvider("spring-ai");
        return config;
    }
}

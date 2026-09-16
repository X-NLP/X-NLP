package com.xnlp.server.startup;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xnlp.core.eval.EvaluationDataset;
import com.xnlp.core.repository.DatasetRepository;
import com.xnlp.server.dataset.versioning.DatasetVersionBootstrap;
import com.xnlp.server.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.List;

/**
 * Imports the bundled evaluation datasets on first startup.
 *
 * <p>Templates use stable IDs and are only inserted when missing, so a user
 * can edit or delete them without the application overwriting existing data
 * on every restart. The configured {@link DatasetRepository} keeps this
 * behavior identical for JDBC and file profiles.</p>
 */
@Component
@Order(2)
public class DatasetTemplateInitializer implements ApplicationListener<ApplicationReadyEvent> {

    private static final Logger log = LoggerFactory.getLogger(DatasetTemplateInitializer.class);
    private static final List<String> TEMPLATE_RESOURCES = List.of(
            "classpath:datasets/sentiment-test.json",
            "classpath:datasets/classify-test.json");

    private final DatasetRepository repository;
    private final ObjectMapper mapper;
    private final ResourceLoader resourceLoader;
    private final DatasetVersionBootstrap versionBootstrap;

    public DatasetTemplateInitializer(
            DatasetRepository repository,
            ObjectMapper mapper,
            ResourceLoader resourceLoader,
            DatasetVersionBootstrap versionBootstrap) {
        this.repository = repository;
        this.mapper = mapper;
        this.resourceLoader = resourceLoader;
        this.versionBootstrap = versionBootstrap;
    }

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        for (String location : TEMPLATE_RESOURCES) {
            importIfMissing(location);
        }
    }

    private void importIfMissing(String location) {
        Resource resource = resourceLoader.getResource(location);
        try (InputStream input = resource.getInputStream()) {
            EvaluationDataset template = mapper.readValue(input, EvaluationDataset.class);
            validate(template, location);
            EvaluationDataset dataset = repository.findById(template.getId()).orElse(null);
            Instant now = Instant.now();
            if (dataset == null) {
                template.setCreatedAt(now);
                template.setUpdatedAt(now);
                template.setEntryCount(template.getEntries().size());
                dataset = repository.save(template);
                log.info("Imported built-in dataset template: {} ({} entries)",
                        template.getName(), template.getEntryCount());
            } else {
                log.debug("Built-in dataset template already exists: {}", template.getName());
            }
            versionBootstrap.bootstrap(TenantContext.currentTenantId(), dataset, "system", now);
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to import dataset template: " + location, ex);
        }
    }

    private static void validate(EvaluationDataset template, String location) {
        if (template.getId() == null || template.getId().isBlank()
                || template.getName() == null || template.getName().isBlank()
                || template.getTaskType() == null || template.getEntries().isEmpty()) {
            throw new IllegalStateException("Invalid dataset template: " + location);
        }
        template.getEntries().forEach(entry -> {
            if (entry.getInput() == null || entry.getInput().isBlank()
                    || entry.getExpectedOutput() == null || entry.getExpectedOutput().isBlank()) {
                throw new IllegalStateException("Invalid dataset entry in template: " + location);
            }
        });
    }
}

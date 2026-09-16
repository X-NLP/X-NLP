package com.xnlp.server.dataset.versioning;

public record DatasetMetadata(String name, String description, String taskType) {

    public DatasetMetadata {
        name = DatasetVersioningSupport.text(name, "name", 190);
        description = DatasetVersioningSupport.nullableText(description, 65535);
        taskType = DatasetVersioningSupport.nullableText(taskType, 64);
    }
}

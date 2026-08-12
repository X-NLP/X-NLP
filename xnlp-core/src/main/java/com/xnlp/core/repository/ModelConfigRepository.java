package com.xnlp.core.repository;

import com.xnlp.core.config.ModelConfig;

import java.util.List;
import java.util.Optional;

/**
 * Repository abstraction for persisting model connection profiles.
 *
 * <p>This follows the Repository pattern (cf. Spring Data) to decouple
 * business logic from storage implementation details. The server selects
 * a persistence adapter from the active Spring profile without changing
 * consumers.
 */
public interface ModelConfigRepository {

    List<ModelConfig> findAll();

    Optional<ModelConfig> findByName(String name);

    ModelConfig save(ModelConfig config);

    void deleteByName(String name);
}

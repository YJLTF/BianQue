package com.bianque.server.store;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DocEmbeddingJpa extends JpaRepository<DocEmbeddingEntity, Long> {

    List<DocEmbeddingEntity> findBySlug(String slug);

    void deleteBySlug(String slug);

    long countByModel(String model);
}

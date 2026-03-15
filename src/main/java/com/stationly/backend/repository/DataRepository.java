package com.stationly.backend.repository;

import java.util.List;
import java.util.Optional;

/**
 * Generic repository interface for Firestore operations.
 * Allows plug-and-play database implementations.
 * 
 * @param <T>  The entity type
 * @param <ID> The ID type
 */
public interface DataRepository<T, ID> {

    /**
     * Save an entity.
     */
    void save(T entity);

    /**
     * Save multiple entities.
     */
    void saveAll(List<T> entities);

    /**
     * Find an entity by its ID.
     */
    Optional<T> findById(ID id);

    /**
     * Find all entities matching a field value.
     */
    List<T> findByField(String fieldName, Object fieldValue);

    /**
     * Find all entities where an array field contains the given value.
     */
    List<T> findByArrayContains(String fieldName, Object value);

    /**
     * Get all entities.
     */
    List<T> findAll();

    /**
     * Find all entities except those where the field matches the value.
     */
    List<T> findAllExcept(String fieldName, Object valueToExclude);

    /**
     * Delete all entities.
     */
    void deleteAll();
}

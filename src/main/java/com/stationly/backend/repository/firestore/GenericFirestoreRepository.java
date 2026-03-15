package com.stationly.backend.repository.firestore;

import com.google.api.core.ApiFuture;
import com.google.cloud.firestore.*;
import com.stationly.backend.repository.DataRepository;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.function.Function;

/**
 * Generic Firestore repository implementation.
 * Provides common CRUD operations for any entity type.
 *
 * @param <T>  The entity type
 * @param <ID> The ID type
 */
@Slf4j
public class GenericFirestoreRepository<T, ID> implements DataRepository<T, ID> {

    protected final Firestore firestore;
    protected final String collectionName;
    protected final Class<T> entityClass;
    protected final Function<T, String> idExtractor;

    public GenericFirestoreRepository(Firestore firestore, String collectionName,
            Class<T> entityClass, Function<T, String> idExtractor) {
        this.firestore = firestore;
        this.collectionName = collectionName;
        this.entityClass = entityClass;
        this.idExtractor = idExtractor;
    }

    @Override
    public void save(T entity) {
        if (firestore == null)
            return;
        try {
            String id = idExtractor.apply(entity);
            firestore.collection(collectionName)
                    .document(id)
                    .set(entity)
                    .get();
            log.trace("Saved {} to {}", id, collectionName);
        } catch (InterruptedException | ExecutionException e) {
            log.error("Failed to save entity to {}", collectionName, e);
        }
    }

    @Override
    public void saveAll(List<T> entities) {
        if (firestore == null || entities.isEmpty())
            return;
        WriteBatch batch = firestore.batch();
        for (T entity : entities) {
            String id = idExtractor.apply(entity);
            DocumentReference docRef = firestore.collection(collectionName).document(id);
            batch.set(docRef, entity);
        }
        try {
            batch.commit().get();
            log.info("Saved {} entities to {}", entities.size(), collectionName);
        } catch (InterruptedException | ExecutionException e) {
            log.error("Failed to save batch to {}", collectionName, e);
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public Optional<T> findById(ID id) {
        if (firestore == null)
            return Optional.empty();
        try {
            DocumentSnapshot doc = firestore.collection(collectionName)
                    .document(String.valueOf(id))
                    .get()
                    .get();
            if (doc.exists()) {
                return Optional.ofNullable(doc.toObject(entityClass));
            }
        } catch (InterruptedException | ExecutionException e) {
            log.error("Failed to find by ID in {}", collectionName, e);
        }
        return Optional.empty();
    }

    @Override
    public List<T> findByField(String fieldName, Object fieldValue) {
        if (firestore == null)
            return new ArrayList<>();
        List<T> results = new ArrayList<>();
        try {
            ApiFuture<QuerySnapshot> future = firestore.collection(collectionName)
                    .whereEqualTo(fieldName, fieldValue)
                    .get();
            for (QueryDocumentSnapshot doc : future.get().getDocuments()) {
                results.add(doc.toObject(entityClass));
            }
        } catch (InterruptedException | ExecutionException e) {
            log.error("Failed to find by field {} in {}", fieldName, collectionName, e);
        }
        return results;
    }

    @Override
    public List<T> findByArrayContains(String fieldName, Object value) {
        if (firestore == null)
            return new ArrayList<>();
        List<T> results = new ArrayList<>();
        try {
            ApiFuture<QuerySnapshot> future = firestore.collection(collectionName)
                    .whereArrayContains(fieldName, value)
                    .get();
            for (QueryDocumentSnapshot doc : future.get().getDocuments()) {
                results.add(doc.toObject(entityClass));
            }
        } catch (InterruptedException | ExecutionException e) {
            log.error("Failed to find by array contains {} in {}", fieldName, collectionName, e);
        }
        return results;
    }

    @Override
    public List<T> findAll() {
        if (firestore == null)
            return new ArrayList<>();
        List<T> results = new ArrayList<>();
        try {
            ApiFuture<QuerySnapshot> future = firestore.collection(collectionName).get();
            for (QueryDocumentSnapshot doc : future.get().getDocuments()) {
                results.add(doc.toObject(entityClass));
            }
        } catch (InterruptedException | ExecutionException e) {
            log.error("Failed to fetch all from {}", collectionName, e);
        }
        return results;
    }

    @Override
    public List<T> findAllExcept(String fieldName, Object valueToExclude) {
        if (firestore == null)
            return new ArrayList<>();
        List<T> results = new ArrayList<>();
        try {
            ApiFuture<QuerySnapshot> future = firestore.collection(collectionName)
                    .whereNotEqualTo(fieldName, valueToExclude)
                    .get();
            for (QueryDocumentSnapshot doc : future.get().getDocuments()) {
                results.add(doc.toObject(entityClass));
            }
        } catch (InterruptedException | ExecutionException e) {
            log.error("Failed to find all except {} in {}", fieldName, collectionName, e);
        }
        return results;
    }

    @Override
    public void deleteAll() {
        if (firestore == null)
            return;
        try {
            ApiFuture<QuerySnapshot> future = firestore.collection(collectionName).get();
            WriteBatch batch = firestore.batch();
            for (QueryDocumentSnapshot doc : future.get().getDocuments()) {
                batch.delete(doc.getReference());
            }
            batch.commit().get();
            log.info("🧹 Deleted all from {}", collectionName);
        } catch (InterruptedException | ExecutionException e) {
            log.error("Failed to delete all from {}", collectionName, e);
        }
    }
}

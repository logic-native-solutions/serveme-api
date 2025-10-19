package com.logicnativesolution.servemeapi.service;

import org.springframework.stereotype.Service;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Lightweight Firestore wrapper that uses reflection to call Firebase Admin SDK when present.
 * Methods will throw UnsupportedOperationException if the SDK is not on the classpath.
 */
@Service
public class FirestoreService {

    private Object getDb() {
        try {
            Class<?> firestoreClient = Class.forName("com.google.firebase.cloud.FirestoreClient");
            Method getFirestore = firestoreClient.getMethod("getFirestore");
            return getFirestore.invoke(null);
        } catch (Exception e) {
            throw new UnsupportedOperationException("Firebase Admin SDK not available: " + e.getMessage(), e);
        }
    }

    public Object create(String collection, Map<String, Object> data) {
        try {
            Object db = getDb();
            Class<?> firestoreClass = Class.forName("com.google.cloud.firestore.Firestore");
            Class<?> collectionRefClass = Class.forName("com.google.cloud.firestore.CollectionReference");
            var collectionMethod = firestoreClass.getMethod("collection", String.class);
            Object colRef = collectionMethod.invoke(db, collection);
            var addMethod = collectionRefClass.getMethod("add", Map.class);
            return addMethod.invoke(colRef, data);
        } catch (Exception e) {
            throw new RuntimeException("Firestore create failed", e);
        }
    }

    public Object set(String collection, String id, Map<String, Object> data) {
        try {
            Object db = getDb();
            Class<?> firestoreClass = Class.forName("com.google.cloud.firestore.Firestore");
            Class<?> collectionRefClass = Class.forName("com.google.cloud.firestore.CollectionReference");
            Class<?> documentRefClass = Class.forName("com.google.cloud.firestore.DocumentReference");
            Class<?> setOptionsClass = Class.forName("com.google.cloud.firestore.SetOptions");

            var collectionMethod = firestoreClass.getMethod("collection", String.class);
            Object colRef = collectionMethod.invoke(db, collection);
            var documentMethod = collectionRefClass.getMethod("document", String.class);
            Object docRef = documentMethod.invoke(colRef, id);

            Object merge = setOptionsClass.getMethod("merge").invoke(null);
            var setMethod = documentRefClass.getMethod("set", Map.class, setOptionsClass);
            return setMethod.invoke(docRef, data, merge);
        } catch (Exception e) {
            throw new RuntimeException("Firestore set failed", e);
        }
    }

    public Object get(String collection, String id) {
        try {
            Object db = getDb();
            Class<?> firestoreClass = Class.forName("com.google.cloud.firestore.Firestore");
            Class<?> collectionRefClass = Class.forName("com.google.cloud.firestore.CollectionReference");
            Class<?> documentRefClass = Class.forName("com.google.cloud.firestore.DocumentReference");
            Class<?> apiFutureClass = Class.forName("com.google.api.core.ApiFuture");

            var collectionMethod = firestoreClass.getMethod("collection", String.class);
            Object colRef = collectionMethod.invoke(db, collection);
            var documentMethod = collectionRefClass.getMethod("document", String.class);
            Object docRef = documentMethod.invoke(colRef, id);
            var getMethod = documentRefClass.getMethod("get");
            Object future = getMethod.invoke(docRef);
            var futureGet = apiFutureClass.getMethod("get");
            return futureGet.invoke(future);
        } catch (Exception e) {
            throw new RuntimeException("Firestore get failed", e);
        }
    }

    /**
     * Returns a list of maps for all documents in the given collection. Each map includes an "id" key.
     */
    public List<Map<String, Object>> listCollection(String collection) {
        try {
            Object db = getDb();
            Class<?> firestoreClass = Class.forName("com.google.cloud.firestore.Firestore");
            Class<?> collectionRefClass = Class.forName("com.google.cloud.firestore.CollectionReference");
            Class<?> documentRefClass = Class.forName("com.google.cloud.firestore.DocumentReference");
            Class<?> documentSnapshotClass = Class.forName("com.google.cloud.firestore.DocumentSnapshot");
            Class<?> apiFutureClass = Class.forName("com.google.api.core.ApiFuture");

            var collectionMethod = firestoreClass.getMethod("collection", String.class);
            Object colRef = collectionMethod.invoke(db, collection);

            // Using listDocuments() to iterate all document references avoids any SDK/page-size quirks
            var listDocumentsMethod = collectionRefClass.getMethod("listDocuments");
            @SuppressWarnings("unchecked")
            Iterable<?> docRefs = (Iterable<?>) listDocumentsMethod.invoke(colRef);

            List<Map<String, Object>> out = new ArrayList<>();
            var getMethod = documentRefClass.getMethod("get");
            var futureGet = apiFutureClass.getMethod("get");
            var getData = documentSnapshotClass.getMethod("getData");
            var getId = documentSnapshotClass.getMethod("getId");
            for (Object docRef : docRefs) {
                Object future = getMethod.invoke(docRef);
                Object snap = futureGet.invoke(future);
                @SuppressWarnings("unchecked")
                Map<String, Object> data = (Map<String, Object>) getData.invoke(snap);
                if (data == null) data = new HashMap<>();
                String id = (String) getId.invoke(snap);
                data.put("id", id);
                out.add(data);
            }
            return out;
        } catch (Exception e) {
            throw new RuntimeException("Firestore listCollection failed", e);
        }
    }

    public <T> T runTransaction(Function<Object, T> function) throws Exception {
        // Not supported without Firestore SDK. Keep signature for future use when SDK is on classpath.
        throw new UnsupportedOperationException("Firestore transactions require Firebase SDK on classpath");
    }

    /**
     * Adds a document to a subcollection under a parent document. Returns a map containing the new document id.
     */
    public Map<String, Object> addToSubcollection(String parentCollection, String parentId, String subcollection, Map<String, Object> data) {
        try {
            Object db = getDb();
            Class<?> firestoreClass = Class.forName("com.google.cloud.firestore.Firestore");
            Class<?> collectionRefClass = Class.forName("com.google.cloud.firestore.CollectionReference");
            Class<?> documentRefClass = Class.forName("com.google.cloud.firestore.DocumentReference");
            Class<?> apiFutureClass = Class.forName("com.google.api.core.ApiFuture");

            var collectionMethod = firestoreClass.getMethod("collection", String.class);
            Object parent = collectionMethod.invoke(db, parentCollection);
            var documentMethod = collectionRefClass.getMethod("document", String.class);
            Object docRef = documentMethod.invoke(parent, parentId);
            var subCollectionMethod = documentRefClass.getMethod("collection", String.class);
            Object sub = subCollectionMethod.invoke(docRef, subcollection);

            var addMethod = collectionRefClass.getMethod("add", Map.class);
            Object future = addMethod.invoke(sub, data);
            var futureGetWithTimeout = apiFutureClass.getMethod("get", long.class, java.util.concurrent.TimeUnit.class);
            Object newDocRef = futureGetWithTimeout.invoke(future, 30L, java.util.concurrent.TimeUnit.SECONDS);
            var getId = documentRefClass.getMethod("getId");
            String id = (String) getId.invoke(newDocRef);
            Map<String, Object> out = new HashMap<>();
            out.put("id", id);
            return out;
        } catch (Exception e) {
            throw new RuntimeException("Firestore addToSubcollection failed", e);
        }
    }

    /**
     * Lists all documents in a subcollection and returns their data maps with an added "id" field.
     */
    public List<Map<String, Object>> listSubcollection(String parentCollection, String parentId, String subcollection) {
        try {
            Object db = getDb();
            Class<?> firestoreClass = Class.forName("com.google.cloud.firestore.Firestore");
            Class<?> collectionRefClass = Class.forName("com.google.cloud.firestore.CollectionReference");
            Class<?> documentRefClass = Class.forName("com.google.cloud.firestore.DocumentReference");
            Class<?> queryClass = Class.forName("com.google.cloud.firestore.Query");
            Class<?> apiFutureClass = Class.forName("com.google.api.core.ApiFuture");
            Class<?> querySnapshotClass = Class.forName("com.google.cloud.firestore.QuerySnapshot");
            Class<?> documentSnapshotClass = Class.forName("com.google.cloud.firestore.DocumentSnapshot");

            var collectionMethod = firestoreClass.getMethod("collection", String.class);
            Object parent = collectionMethod.invoke(db, parentCollection);
            var documentMethod = collectionRefClass.getMethod("document", String.class);
            Object docRef = documentMethod.invoke(parent, parentId);
            var subCollectionMethod = documentRefClass.getMethod("collection", String.class);
            Object sub = subCollectionMethod.invoke(docRef, subcollection);

            var getQueryMethod = queryClass.getMethod("get");
            Object qFuture = getQueryMethod.invoke(sub);
            var futureGet = apiFutureClass.getMethod("get");
            Object qSnap = futureGet.invoke(qFuture);

            var getDocuments = querySnapshotClass.getMethod("getDocuments");
            @SuppressWarnings("unchecked")
            List<?> docs = (List<?>) getDocuments.invoke(qSnap);

            List<Map<String, Object>> out = new ArrayList<>();
            var getData = documentSnapshotClass.getMethod("getData");
            var getId = documentSnapshotClass.getMethod("getId");
            for (Object docSnap : docs) {
                @SuppressWarnings("unchecked")
                Map<String, Object> data = (Map<String, Object>) getData.invoke(docSnap);
                if (data == null) data = new HashMap<>();
                String id = (String) getId.invoke(docSnap);
                data.put("id", id);
                out.add(data);
            }
            return out;
        } catch (Exception e) {
            throw new RuntimeException("Firestore listSubcollection failed", e);
        }
    }

    /**
     * Gets a specific document snapshot from a subcollection under a parent document.
     * Returns the DocumentSnapshot (reflective object) so caller can inspect exists() and getData().
     */
    public Object getFromSubcollection(String parentCollection, String parentId, String subcollection, String docId) {
        try {
            Object db = getDb();
            Class<?> firestoreClass = Class.forName("com.google.cloud.firestore.Firestore");
            Class<?> collectionRefClass = Class.forName("com.google.cloud.firestore.CollectionReference");
            Class<?> documentRefClass = Class.forName("com.google.cloud.firestore.DocumentReference");
            Class<?> apiFutureClass = Class.forName("com.google.api.core.ApiFuture");

            var collectionMethod = firestoreClass.getMethod("collection", String.class);
            Object parent = collectionMethod.invoke(db, parentCollection);
            var documentMethod = collectionRefClass.getMethod("document", String.class);
            Object docRef = documentMethod.invoke(parent, parentId);
            var subCollectionMethod = documentRefClass.getMethod("collection", String.class);
            Object sub = subCollectionMethod.invoke(docRef, subcollection);
            Object goalDocRef = documentMethod.invoke(sub, docId);

            var getMethod = documentRefClass.getMethod("get");
            Object future = getMethod.invoke(goalDocRef);
            var futureGet = apiFutureClass.getMethod("get");
            return futureGet.invoke(future);
        } catch (Exception e) {
            throw new RuntimeException("Firestore getFromSubcollection failed", e);
        }
    }

    /**
     * Sets (merges) a specific document inside a subcollection under a parent document.
     */
    public Object setInSubcollection(String parentCollection, String parentId, String subcollection, String docId, Map<String, Object> data) {
        try {
            Object db = getDb();
            Class<?> firestoreClass = Class.forName("com.google.cloud.firestore.Firestore");
            Class<?> collectionRefClass = Class.forName("com.google.cloud.firestore.CollectionReference");
            Class<?> documentRefClass = Class.forName("com.google.cloud.firestore.DocumentReference");
            Class<?> setOptionsClass = Class.forName("com.google.cloud.firestore.SetOptions");

            var collectionMethod = firestoreClass.getMethod("collection", String.class);
            Object parent = collectionMethod.invoke(db, parentCollection);
            var documentMethod = collectionRefClass.getMethod("document", String.class);
            Object docRef = documentMethod.invoke(parent, parentId);
            var subCollectionMethod = documentRefClass.getMethod("collection", String.class);
            Object sub = subCollectionMethod.invoke(docRef, subcollection);
            Object goalDocRef = documentMethod.invoke(sub, docId);

            Object merge = setOptionsClass.getMethod("merge").invoke(null);
            var setMethod = documentRefClass.getMethod("set", Map.class, setOptionsClass);
            return setMethod.invoke(goalDocRef, data, merge);
        } catch (Exception e) {
            throw new RuntimeException("Firestore setInSubcollection failed", e);
        }
    }
}

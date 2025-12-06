package com.logicnativesolution.servemeapi.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Map;

/**
 * Lightweight FCM notification sender using Firebase Admin via reflection.
 * If Firebase SDK is not present, calls will log and no-op.
 *
 * Notes:
 * - Sends data messages and, when possible, attaches a Notification (title/body) for iOS/APNs reliability.
 * - iOS delivery of FCM requires APNs to be configured in Firebase Console (using your Apple key). This
 *   service does not need your APN_KEY directly when using FCM; it relies on Firebase setup.
 */
@Service
public class NotificationService {
    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    public boolean sendToToken(Map<String, String> data, String token) {
        try {
            Class<?> messageClass = Class.forName("com.google.firebase.messaging.Message");
            Class<?> builderClass = Class.forName("com.google.firebase.messaging.Message$Builder");
            Class<?> firebaseMessagingClass = Class.forName("com.google.firebase.messaging.FirebaseMessaging");

            Method builderMethod = messageClass.getMethod("builder");
            Object builder = builderMethod.invoke(null);
            // set token
            Method setToken = builderClass.getMethod("setToken", String.class);
            setToken.invoke(builder, token);

            // Optionally set Notification(title, body) to improve iOS delivery/UX
            try {
                String title = data != null ? data.getOrDefault("title", null) : null;
                String body = data != null ? data.getOrDefault("body", null) : null;
                if (title == null && body == null && data != null) {
                    // Provide sensible defaults for known types
                    String type = data.get("type");
                    if ("job_offer".equals(type)) {
                        title = "New job near you";
                        body = "A client requested a service you offer";
                    }
                }// Always attach a notification for iOS
                if (title == null) title = "New update";
                if (body == null) body = "You have a new message"; {
                    // Try using Notification(String title, String body)
                    Class<?> notificationClass = Class.forName("com.google.firebase.messaging.Notification");
                    Constructor<?> ctor = notificationClass.getConstructor(String.class, String.class);
                    Object notification = ctor.newInstance(title, body);
                    Method setNotification = builderClass.getMethod("setNotification", notificationClass);
                    setNotification.invoke(builder, notification);
                }
            } catch (ClassNotFoundException ignored) {
                // Older Firebase Admin SDK or not present; continue with data-only
            } catch (NoSuchMethodException ignored) {
                // API shape differences; continue with data-only
            }

            // add data entries
            Method putData = builderClass.getMethod("putData", String.class, String.class);
            if (data != null) {
                for (Map.Entry<String, String> e : data.entrySet()) {
                    if (e.getValue() != null) {
                        putData.invoke(builder, e.getKey(), e.getValue());
                    }
                }
            }

            // build
            Method build = builderClass.getMethod("build");
            Object message = build.invoke(builder);
            // send
            Method getInstance = firebaseMessagingClass.getMethod("getInstance");
            Object fm = getInstance.invoke(null);
            Method send = firebaseMessagingClass.getMethod("send", messageClass);
            String id = (String) send.invoke(fm, message);
            log.info("Sent FCM message id={} payloadKeys={}", id, (data != null ? data.keySet() : null));
            return true;
        } catch (ClassNotFoundException cnfe) {
            log.warn("Firebase Messaging SDK not available; skipping send");
            return false;
        } catch (Exception e) {
            log.warn("Failed to send FCM message", e);
            return false;
        }
    }


    /**
     * Looks up users/{uid}.fcmToken and sends a data message if available.
     */
//    public boolean sendToUser(String uid, Map<String, String> data, FirestoreService firestoreService) {
//        try {
//            Object snap = firestoreService.get("users", uid);
//            Class<?> docSnapClass = Class.forName("com.google.cloud.firestore.DocumentSnapshot");
//            Boolean exists = (Boolean) docSnapClass.getMethod("exists").invoke(snap);
//            if (!exists) return false;
//            @SuppressWarnings("unchecked")
//            Map<String, Object> user = (Map<String, Object>) docSnapClass.getMethod("getData").invoke(snap);
//            if (user == null) return false;
//            Object tok = user.get("fcmToken");
//            if (tok == null) return false;
//            return sendToToken(data, String.valueOf(tok));
//        } catch (ClassNotFoundException e) {
//            log.warn("Firebase SDK not available; cannot fetch user token");
//            return false;
//        } catch (Exception e) {
//            log.warn("Failed to send to user {}", uid, e);
//            return false;
//        }
//    }
    public boolean sendToUser(String uid, Map<String, String> data, FirestoreService firestoreService) {
        System.out.println("===== FCM SEND TO USER: " + uid + " =====");
        try {
            Object snap = firestoreService.get("users", uid);
            Class<?> docSnapClass = Class.forName("com.google.cloud.firestore.DocumentSnapshot");
            Boolean exists = (Boolean) docSnapClass.getMethod("exists").invoke(snap);

            System.out.println("User document exists: " + exists);

            if (!exists) {
                System.out.println("❌ User document does not exist");
                return false;
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> user = (Map<String, Object>) docSnapClass.getMethod("getData").invoke(snap);

            if (user == null) {
                System.out.println("❌ User data is null");
                return false;
            }

            System.out.println("User data keys: " + user.keySet());

            Object tok = user.get("fcmToken");
            System.out.println("FCM token present: " + (tok != null) + " (length: " + (tok != null ? String.valueOf(tok).length() : 0) + ")");

            if (tok == null) {
                System.out.println("❌ No FCM token for user");
                return false;
            }

            String token = String.valueOf(tok);
            System.out.println("Sending FCM with payload: " + data);
            boolean result = sendToToken(data, token);
            System.out.println("FCM send result: " + result);

            return result;
        } catch (ClassNotFoundException e) {
            log.warn("Firebase SDK not available; cannot fetch user token");
            System.out.println("❌ Firebase SDK not available");
            return false;
        } catch (Exception e) {
            log.warn("Failed to send to user {}", uid, e);
            System.out.println("❌ Exception sending to user: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }
}

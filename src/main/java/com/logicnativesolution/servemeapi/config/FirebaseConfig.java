package com.logicnativesolution.servemeapi.config;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
/**
 * Initializes Firebase Admin SDK once at application startup using reflection to avoid hard compile-time dependency.
 * Credentials resolution order (first non-empty wins):
 * 1) spring.app.firebase.credentialsJson (inline JSON)
 * 2) FIREBASE_CREDENTIALS_JSON (inline JSON via env)
 * 3) FIREBASE_CREDENTIALS_JSON_B64 (base64-encoded JSON via env)
 * 4) spring.app.firebase.credentialsFile (file path)
 * 5) GOOGLE_APPLICATION_CREDENTIALS (file path)
 * 6) Application Default Credentials (ADC)
 */
@Slf4j
@Configuration
public class FirebaseConfig {

    @Value("${app.firebase.credentialsFile:${spring.app.firebase.credentialsFile:}}")
    private String credentialsFile;

    @Value("${app.firebase.credentialsJson:${spring.app.firebase.credentialsJson:}}")
    private String credentialsJson;

    @Value("${app.firebase.credentialsClasspath:com/logicnativesolution/servemeapi/serviceaccount/serveme-e527c-4ce9e77f0f71.json}")
    private String credentialsClasspath;

    @Value("${app.firebase.projectId:${spring.app.firebase.projectId:}}")
    private String projectId;

    @PostConstruct
    public void init() {
        InputStream in = null;
        try {
            Class<?> firebaseAppClass = Class.forName("com.google.firebase.FirebaseApp");
            // Check if already initialized: FirebaseApp.getApps().isEmpty()
            var getApps = firebaseAppClass.getMethod("getApps");
            var apps = (java.util.List<?>) getApps.invoke(null);
            if (!apps.isEmpty()) {
                log.debug("Firebase Admin already initialized, skipping");
                return;
            }

            Class<?> googleCredsClass = Class.forName("com.google.auth.oauth2.GoogleCredentials");
            Object credentials = null;

            // 0) Classpath resource (highest priority)
            String classpath = emptyToNull(credentialsClasspath);
            if (classpath != null) {
                InputStream res = Thread.currentThread().getContextClassLoader().getResourceAsStream(classpath);
                if (res == null) {
                    res = FirebaseConfig.class.getClassLoader().getResourceAsStream(classpath);
                }
                if (res == null && classpath.startsWith("/")) {
                    String without = classpath.substring(1);
                    res = Thread.currentThread().getContextClassLoader().getResourceAsStream(without);
                    if (res == null) {
                        res = FirebaseConfig.class.getClassLoader().getResourceAsStream(without);
                    }
                }
                if (res != null) {
                    closeQuietly(in);
                    in = res;
                    credentials = googleCredsClass.getMethod("fromStream", InputStream.class).invoke(null, in);
                    log.info("Firebase Admin: loaded credentials from classpath resource: {}", classpath);
                } else {
                    log.debug("Firebase Admin: classpath credentials resource not found: {}", classpath);
                }
            }

            // 1) Inline JSON from property
            String jsonProp = emptyToNull(credentialsJson);
            if (jsonProp != null) {
                in = new ByteArrayInputStream(jsonProp.getBytes(StandardCharsets.UTF_8));
                credentials = googleCredsClass.getMethod("fromStream", InputStream.class).invoke(null, in);
                log.info("Firebase Admin: loaded credentials from spring.app.firebase.credentialsJson (inline)");
            }

            // 2) Inline JSON from env
            if (credentials == null) {
                String jsonEnv = emptyToNull(System.getenv("FIREBASE_CREDENTIALS_JSON"));
                if (jsonEnv != null) {
                    closeQuietly(in);
                    in = new ByteArrayInputStream(jsonEnv.getBytes(StandardCharsets.UTF_8));
                    credentials = googleCredsClass.getMethod("fromStream", InputStream.class).invoke(null, in);
                    log.info("Firebase Admin: loaded credentials from FIREBASE_CREDENTIALS_JSON env (inline)");
                }
            }

            // 3) Base64-encoded JSON from env
            if (credentials == null) {
                String b64 = emptyToNull(System.getenv("FIREBASE_CREDENTIALS_JSON_B64"));
                if (b64 != null) {
                    try {
                        byte[] decoded = Base64.getDecoder().decode(b64);
                        closeQuietly(in);
                        in = new ByteArrayInputStream(decoded);
                        credentials = googleCredsClass.getMethod("fromStream", InputStream.class).invoke(null, in);
                        log.info("Firebase Admin: loaded credentials from FIREBASE_CREDENTIALS_JSON_B64 env (base64)");
                    } catch (IllegalArgumentException iae) {
                        log.warn("Firebase Admin: FIREBASE_CREDENTIALS_JSON_B64 is not valid Base64; ignoring");
                    }
                }
            }

            // 4/5) File path from property or env
            if (credentials == null) {
                String path = emptyToNull(credentialsFile);
                if (path == null) {
                    path = emptyToNull(System.getenv("GOOGLE_APPLICATION_CREDENTIALS"));
                }
                if (path != null) {
                    // If someone mistakenly put API key or inline JSON into this var, handle gracefully
                    if (looksLikeApiKey(path)) {
                        log.warn("Firebase Admin: Provided GOOGLE_APPLICATION_CREDENTIALS value looks like an API key, not a file path. Use a JSON file path or FIREBASE_CREDENTIALS_JSON.");
                    } else if (looksLikeJsonInline(path)) {
                        closeQuietly(in);
                        in = new ByteArrayInputStream(path.getBytes(StandardCharsets.UTF_8));
                        credentials = googleCredsClass.getMethod("fromStream", InputStream.class).invoke(null, in);
                        log.info("Firebase Admin: loaded credentials from inline JSON mistakenly set in credentialsFile/GOOGLE_APPLICATION_CREDENTIALS");
                    } else {
                        String normalized = normalizePath(path);
                        File file = new File(normalized);
                        if (file.exists() && file.isFile()) {
                            closeQuietly(in);
                            in = new FileInputStream(file);
                            credentials = googleCredsClass.getMethod("fromStream", InputStream.class).invoke(null, in);
                            log.info("Firebase Admin: loaded credentials from file path: {}", file.getAbsolutePath());
                        } else {
                            log.warn("Firebase Admin: credentials file path provided but not found: {} — will try ADC", normalized);
                        }
                    }
                }
            }

            // 6) ADC fallback
            if (credentials == null) {
                var getDefault = googleCredsClass.getMethod("getApplicationDefault");
                credentials = getDefault.invoke(null);
                log.info("Firebase Admin: using Application Default Credentials (ADC)");
            }

            Class<?> firebaseOptionsClass = Class.forName("com.google.firebase.FirebaseOptions");
            var builderMethod = firebaseOptionsClass.getMethod("builder");
            Object builder = builderMethod.invoke(null);
            var setCreds = builder.getClass().getMethod("setCredentials", googleCredsClass);
            setCreds.invoke(builder, credentials);

            // Optional: set projectId if provided
            if (projectId != null && !projectId.isBlank()) {
                try {
                    var setProjectId = builder.getClass().getMethod("setProjectId", String.class);
                    setProjectId.invoke(builder, projectId);
                } catch (NoSuchMethodException ignore) {
                    // Older SDKs may not have setProjectId on builder; ignore.
                }
            }

            var build = builder.getClass().getMethod("build");
            Object options = build.invoke(builder);

            var initializeApp = firebaseAppClass.getMethod("initializeApp", firebaseOptionsClass);
            initializeApp.invoke(null, options);
            log.info("Firebase Admin initialized (reflection)");
        } catch (ClassNotFoundException e) {
            log.warn("Firebase Admin SDK not on classpath; skipping initialization");
        } catch (Exception e) {
            log.error("Failed to initialize Firebase Admin via reflection. Ensure you set either:\n - GOOGLE_APPLICATION_CREDENTIALS to a readable file path, or\n - FIREBASE_CREDENTIALS_JSON (inline JSON), or\n - FIREBASE_CREDENTIALS_JSON_B64 (base64 JSON).\nAlso verify the service account belongs to the Firebase project.", e);
        } finally {
            closeQuietly(in);
        }
    }

    private static String emptyToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }

    private static boolean looksLikeJsonInline(String s) {
        String trimmed = s.trim();
        return trimmed.startsWith("{") && trimmed.endsWith("}");
    }

    private static boolean looksLikeApiKey(String s) {
        return s != null && s.startsWith("AIza");
    }

    // Expands common user shortcuts in paths (e.g., ~ to user.home) and trims surrounding quotes.
    private static String normalizePath(String s) {
        if (s == null) return null;
        String p = s.trim();
        if ((p.startsWith("\"") && p.endsWith("\"")) || (p.startsWith("'") && p.endsWith("'"))) {
            p = p.substring(1, p.length() - 1);
        }
        if (p.startsWith("~")) {
            String home = System.getProperty("user.home");
            if (home != null && !home.isBlank()) {
                p = home + p.substring(1);
            }
        }
        return p;
    }

    private static void closeQuietly(InputStream in) {
        if (in != null) {
            try { in.close(); } catch (Exception ignore) {}
        }
    }
}

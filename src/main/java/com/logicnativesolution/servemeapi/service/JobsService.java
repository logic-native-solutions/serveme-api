package com.logicnativesolution.servemeapi.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.logicnativesolution.servemeapi.model.firestore.JobDoc;
import com.logicnativesolution.servemeapi.dto.jobs.CreateJobRequest;
import com.logicnativesolution.servemeapi.dto.jobs.SendMessageRequest;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class JobsService {

    private static double haversineKm(double lat1, double lon1, double lat2, double lon2) {
        double R = 6371.0; // km
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat/2) * Math.sin(dLat/2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon/2) * Math.sin(dLon/2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1-a));
        return R * c;
    }

    private final FirestoreService firestoreService;
    private final NotificationService notificationService;
    private final ObjectMapper objectMapper;

    public JobsService(FirestoreService firestoreService, NotificationService notificationService, ObjectMapper objectMapper) {
        this.firestoreService = firestoreService;
        this.notificationService = notificationService;
        this.objectMapper = objectMapper;
    }

    public Map<String, Object> create(CreateJobRequest req, String uid) throws Exception {
        if (uid == null) throw new SecurityException("unauthorized");
        if (req.getServiceType() == null || req.getServiceType().isBlank()) {
            throw new IllegalArgumentException("serviceType is required");
        }
        try {
            Object snap = firestoreService.get("services", req.getServiceType());
            Class<?> docSnapClass = Class.forName("com.google.cloud.firestore.DocumentSnapshot");
            Boolean exists = (Boolean) docSnapClass.getMethod("exists").invoke(snap);
            if (!exists) throw new NoSuchElementException("Unknown serviceType");
            @SuppressWarnings("unchecked")
            Map<String, Object> service = (Map<String, Object>) docSnapClass.getMethod("getData").invoke(snap);

            // Validate addOns
            Set<String> selected = new HashSet<>(Optional.ofNullable(req.getAddOnIds()).orElseGet(List::of));
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> addOns = (List<Map<String, Object>>) service.getOrDefault("addOns", List.of());
            Map<String, Long> priceByAddOn = new HashMap<>();
            for (Map<String, Object> a : addOns) {
                String id = Objects.toString(a.get("id"), null);
                Long price = a.get("price") == null ? 0L : ((Number) a.get("price")).longValue();
                if (id != null) priceByAddOn.put(id, price);
            }
            long basePrice = ((Number) service.getOrDefault("basePrice", 0)).longValue();
            long addonsTotal = 0L;
            for (String s : selected) {
                if (!priceByAddOn.containsKey(s)) {
                    throw new IllegalArgumentException("Unknown addOnId: " + s);
                }
                addonsTotal += priceByAddOn.get(s);
            }
            long subtotal = basePrice + addonsTotal;
            long fees = 0L; // TODO: pricing policy
            long total = subtotal + fees;

            String jobId = UUID.randomUUID().toString();
            Instant now = Instant.now();
            Instant expires = now.plus(10, ChronoUnit.MINUTES);

            JobDoc.Address addr = req.getAddress() == null ? null : JobDoc.Address.builder()
                    .line1(req.getAddress().getLine1())
                    .lat(req.getAddress().getLat())
                    .lng(req.getAddress().getLng())
                    .geohash(com.logicnativesolution.servemeapi.util.GeohashUtil.encode(
                            Optional.ofNullable(req.getAddress().getLat()).orElse(0.0),
                            Optional.ofNullable(req.getAddress().getLng()).orElse(0.0),
                            7
                    ))
                    .build();
            JobDoc.DesiredTime dt = req.getDesiredTime() == null ? null : JobDoc.DesiredTime.builder()
                    .type(req.getDesiredTime().getType())
                    .when(req.getDesiredTime().getWhen())
                    .build();
            JobDoc.Price price = JobDoc.Price.builder()
                    .currency(Optional.ofNullable(req.getCurrency()).orElse("ZAR"))
                    .subtotal(subtotal)
                    .fees(fees)
                    .total(total)
                    .build();

            // Payments are initialized via /api/v1/payments/intent (Paystack). No backend intent is created here.
            JobDoc.Payment payment = null; // Frontend will store reference and status after initialize/verify

            JobDoc job = JobDoc.builder()
                    .serviceType(req.getServiceType())
                    .clientId(uid)
                    .status("pending")
                    .description(req.getDescription())
                    .photos(req.getPhotoUrls())
                    .address(addr)
                    .desiredTime(dt)
                    .price(price)
                    .payment(payment)
                    .assignedProviderId(null)
                    .createdAt(now)
                    .expiresAt(expires)
                    .build();

            @SuppressWarnings("unchecked")
            Map<String, Object> map = objectMapper.convertValue(job, Map.class);
            firestoreService.set("jobs", jobId, map);
            map.put("id", jobId);

            // Fan-out to nearby providers for this serviceType and persist the offer list (best-effort)
            try {
                double jLat = Optional.ofNullable(req.getAddress()).map(a -> Optional.ofNullable(a.getLat()).orElse(0.0)).orElse(0.0);
                double jLng = Optional.ofNullable(req.getAddress()).map(a -> Optional.ofNullable(a.getLng()).orElse(0.0)).orElse(0.0);
                int minRadius = Optional.ofNullable((Number) service.getOrDefault("minRadiusKm", 10)).map(Number::intValue).orElse(10);
                int maxProviders = Optional.ofNullable((Number) service.getOrDefault("maxProviders", 20)).map(Number::intValue).orElse(20);

                // If job already has fanOut (e.g., due to a retry), load and avoid duplicates
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> existingFanOut = (List<Map<String, Object>>) map.getOrDefault("fanOut", List.of());
                Set<String> alreadyNotified = existingFanOut.stream()
                        .map(m0 -> Objects.toString(m0.get("providerId"), null))
                        .filter(Objects::nonNull)
                        .collect(Collectors.toSet());

                List<Map<String, Object>> providers = firestoreService.listCollection("providers");
                // Build candidate entries with distance then sort by distance asc
                List<Map<String, Object>> candidateEntries = new ArrayList<>();
                for (Map<String, Object> p : providers) {
                    if (!Boolean.TRUE.equals(p.get("isOnline"))) continue;
                    Object sts = p.get("serviceTypes");
                    boolean supports = false;
                    if (sts instanceof List<?> lst) {
                        for (Object o : lst) { if (req.getServiceType().equals(String.valueOf(o))) { supports = true; break; } }
                    }
                    if (!supports) continue;
                    Double plat = p.get("lat") instanceof Number n ? n.doubleValue() : null;
                    Double plng = p.get("lng") instanceof Number n ? n.doubleValue() : null;
                    if (plat == null || plng == null) continue;
                    double dist = haversineKm(jLat, jLng, plat, plng);
                    if (dist <= minRadius) {
                        String pid = Objects.toString(p.get("id"), null);
                        if (pid != null && !pid.equals(uid) && !alreadyNotified.contains(pid)) {
                            Map<String, Object> entry = new HashMap<>();
                            entry.put("providerId", pid);
                            entry.put("distanceKm", Math.round(dist * 10.0) / 10.0); // 1 decimal
                            candidateEntries.add(entry);
                        }
                    }
                }
                candidateEntries.sort(Comparator.comparingDouble(e -> ((Number) e.get("distanceKm")).doubleValue()));
                if (candidateEntries.size() > maxProviders) {
                    candidateEntries = new ArrayList<>(candidateEntries.subList(0, maxProviders));
                }

                // Notify and persist
                Map<String, String> payload = new HashMap<>();
                payload.put("type", "job_offer");
                payload.put("jobId", jobId);
                payload.put("serviceType", req.getServiceType());
                Date nowTs = Date.from(Instant.now());
                List<Map<String, Object>> toPersist = new ArrayList<>(existingFanOut);
                for (Map<String, Object> entry : candidateEntries) {
                    String pid = Objects.toString(entry.get("providerId"), null);
                    if (pid == null) continue;
                    notificationService.sendToUser(pid, payload, firestoreService);
                    Map<String, Object> persisted = new HashMap<>();
                    persisted.put("providerId", pid);
                    persisted.put("notifiedAt", nowTs);
                    persisted.put("distanceKm", entry.get("distanceKm"));
                    toPersist.add(persisted);
                }
                if (!toPersist.isEmpty()) {
                    firestoreService.set("jobs", jobId, Map.of("fanOut", toPersist));
                    map.put("fanOut", toPersist);
                }
            } catch (Exception fanoutEx) {
                // Best-effort; ignore fan-out failures for now
            }

            return map;
        } catch (ClassNotFoundException e) {
            throw new UnsupportedOperationException("Firebase SDK not available");
        }
    }

    public List<Map<String, Object>> list(String role, String uid) {
        if (uid == null) throw new SecurityException("unauthorized");
        List<Map<String, Object>> all = firestoreService.listCollection("jobs");
        if (role == null || role.isBlank() || role.equalsIgnoreCase("client")) {
            return all.stream().filter(m -> uid.equals(Objects.toString(m.get("clientId"), null))).collect(Collectors.toList());
        } else if (role.equalsIgnoreCase("provider")) {
            return all.stream().filter(m -> uid.equals(Objects.toString(m.get("assignedProviderId"), null))).collect(Collectors.toList());
        } else {
            throw new IllegalArgumentException("role must be 'client' or 'provider'");
        }
    }

    public Map<String, Object> get(String id, String uid) throws Exception {
        if (uid == null) throw new SecurityException("unauthorized");
        try {
            Object snap = firestoreService.get("jobs", id);
            Class<?> docSnapClass = Class.forName("com.google.cloud.firestore.DocumentSnapshot");
            Boolean exists = (Boolean) docSnapClass.getMethod("exists").invoke(snap);
            if (!exists) throw new NoSuchElementException("not_found");
            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) docSnapClass.getMethod("getData").invoke(snap);
            if (data == null) data = new HashMap<>();
            data.put("id", id);
            String clientId = Objects.toString(data.get("clientId"), null);
            String providerId = Objects.toString(data.get("assignedProviderId"), null);
            if (!uid.equals(clientId) && !uid.equals(providerId)) {
                throw new SecurityException("forbidden");
            }
            return data;
        } catch (ClassNotFoundException e) {
            throw new UnsupportedOperationException("Firebase SDK not available");
        }
    }

    public Map<String, Object> accept(String id, String uid) throws Exception {
        if (uid == null) throw new SecurityException("unauthorized");
        try {
            Object snap = firestoreService.get("jobs", id);
            Class<?> docSnapClass = Class.forName("com.google.cloud.firestore.DocumentSnapshot");
            Boolean exists = (Boolean) docSnapClass.getMethod("exists").invoke(snap);
            if (!exists) throw new NoSuchElementException("not_found");
            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) docSnapClass.getMethod("getData").invoke(snap);
            if (data == null) data = new HashMap<>();
            String status = Objects.toString(data.get("status"), "pending");
            String assigned = Objects.toString(data.get("assignedProviderId"), null);

            // If not pending and not already assigned to this uid, reject
            if (!"pending".equals(status) && !uid.equals(assigned)) {
                throw new IllegalStateException("already_taken");
            }

            // Expiry check: if pending and expired, reject
            Object expObj = data.get("expiresAt");
            if ("pending".equals(status) && expObj instanceof Date exp && exp.before(new Date())) {
                throw new IllegalStateException("expired");
            }

            // If still pending, validate that this provider was offered (when fanOut exists)
            if ("pending".equals(status)) {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> fanOut = (List<Map<String, Object>>) data.get("fanOut");
                if (fanOut != null && !fanOut.isEmpty()) {
                    boolean offered = false;
                    for (Map<String, Object> o : fanOut) {
                        if (uid.equals(Objects.toString(o.get("providerId"), null))) { offered = true; break; }
                    }
                    if (!offered) throw new SecurityException("not_offered");
                }
            }

            Map<String, Object> update = new HashMap<>();
            update.put("status", "assigned");
            update.put("assignedProviderId", uid);
            update.put("acceptedAt", Date.from(Instant.now()));
            firestoreService.set("jobs", id, update);
            data.putAll(update);
            data.put("id", id);
            return data;
        } catch (ClassNotFoundException e) {
            throw new UnsupportedOperationException("Firebase SDK not available");
        }
    }

    public Map<String, Object> updateStatus(String id, String newStatus, String uid) throws Exception {
        if (uid == null) throw new SecurityException("unauthorized");
        if (newStatus == null || newStatus.isBlank()) throw new IllegalArgumentException("status is required");
        Set<String> allowed = Set.of("assigned", "enroute", "arrived", "in_progress", "completed", "canceled");
        if (!allowed.contains(newStatus)) throw new IllegalArgumentException("invalid status");
        try {
            Object snap = firestoreService.get("jobs", id);
            Class<?> docSnapClass = Class.forName("com.google.cloud.firestore.DocumentSnapshot");
            Boolean exists = (Boolean) docSnapClass.getMethod("exists").invoke(snap);
            if (!exists) throw new NoSuchElementException("not_found");
            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) docSnapClass.getMethod("getData").invoke(snap);
            if (data == null) data = new HashMap<>();
            String assigned = Objects.toString(data.get("assignedProviderId"), null);
            String current = Objects.toString(data.get("status"), null);
            boolean isProvider = uid.equals(assigned);
            boolean isClient = uid.equals(Objects.toString(data.get("clientId"), null));
            if (!isProvider && !(isClient && ("pending".equals(current) || "assigned".equals(current)) && "canceled".equals(newStatus))) {
                throw new SecurityException("forbidden");
            }
            Map<String, List<String>> transitions = Map.of(
                    "assigned", List.of("enroute", "canceled"),
                    "enroute", List.of("arrived", "canceled"),
                    "arrived", List.of("in_progress", "canceled"),
                    "in_progress", List.of("completed", "canceled")
            );
            if (current != null && transitions.containsKey(current) && !transitions.get(current).contains(newStatus)) {
                throw new IllegalArgumentException("illegal_transition");
            }
            Map<String, Object> update = new HashMap<>();
            update.put("status", newStatus);
            Instant now = Instant.now();
            switch (newStatus) {
                case "enroute" -> update.put("startedAt", Date.from(now));
                case "arrived" -> update.put("startedAt", Date.from(now));
                case "in_progress" -> update.put("startedAt", Date.from(now));
                case "completed" -> update.put("completedAt", Date.from(now));
                case "canceled" -> update.put("expiresAt", Date.from(now));
            }

            // On completion: no automatic capture with Paystack; payment status is driven by webhooks/verify.
            if ("completed".equals(newStatus)) {
                // Optionally, enforce that payment.status == succeeded before allowing completion.
            }

            firestoreService.set("jobs", id, update);
            data.putAll(update);
            data.put("id", id);
            return data;
        } catch (ClassNotFoundException e) {
            throw new UnsupportedOperationException("Firebase SDK not available");
        }
    }

    public Map<String, Object> sendMessage(String id, SendMessageRequest req, String uid) throws Exception {
        if (uid == null) throw new SecurityException("unauthorized");
        if (req.getText() == null || req.getText().isBlank()) throw new IllegalArgumentException("text is required");
        try {
            Object snap = firestoreService.get("jobs", id);
            Class<?> docSnapClass = Class.forName("com.google.cloud.firestore.DocumentSnapshot");
            Boolean exists = (Boolean) docSnapClass.getMethod("exists").invoke(snap);
            if (!exists) throw new NoSuchElementException("not_found");
            @SuppressWarnings("unchecked")
            Map<String, Object> job = (Map<String, Object>) docSnapClass.getMethod("getData").invoke(snap);
            String clientId = Objects.toString(job.get("clientId"), null);
            String providerId = Objects.toString(job.get("assignedProviderId"), null);
            if (!uid.equals(clientId) && !uid.equals(providerId)) throw new SecurityException("forbidden");

            Map<String, Object> msg = new HashMap<>();
            msg.put("senderId", uid);
            msg.put("text", req.getText());
            msg.put("type", Optional.ofNullable(req.getType()).orElse("text"));
            msg.put("sentAt", Date.from(Instant.now()));
            Map<String, Object> ref = firestoreService.addToSubcollection("jobs", id, "messages", msg);
            msg.put("id", ref.get("id"));

            // Notify the other participant via FCM (best-effort)
            String recipient = uid.equals(clientId) ? providerId : clientId;
            if (recipient != null) {
                Map<String, String> payload = new HashMap<>();
                payload.put("type", "chat");
                payload.put("jobId", id);
                payload.put("messageId", String.valueOf(ref.get("id")));
                notificationService.sendToUser(recipient, payload, firestoreService);
            }
            return msg;
        } catch (ClassNotFoundException e) {
            throw new UnsupportedOperationException("Firebase SDK not available");
        }
    }

    public List<Map<String, Object>> listMessages(String id, String uid) throws Exception {
        if (uid == null) throw new SecurityException("unauthorized");
        try {
            Object snap = firestoreService.get("jobs", id);
            Class<?> docSnapClass = Class.forName("com.google.cloud.firestore.DocumentSnapshot");
            Boolean exists = (Boolean) docSnapClass.getMethod("exists").invoke(snap);
            if (!exists) throw new NoSuchElementException("not_found");
            @SuppressWarnings("unchecked")
            Map<String, Object> job = (Map<String, Object>) docSnapClass.getMethod("getData").invoke(snap);
            String clientId = Objects.toString(job.get("clientId"), null);
            String providerId = Objects.toString(job.get("assignedProviderId"), null);
            if (!uid.equals(clientId) && !uid.equals(providerId)) throw new SecurityException("forbidden");

            List<Map<String, Object>> msgs = firestoreService.listSubcollection("jobs", id, "messages");
            msgs.sort(Comparator.comparing(m -> Optional.ofNullable((Date) m.get("sentAt")).orElse(new Date(0))));
            return msgs;
        } catch (ClassNotFoundException e) {
            throw new UnsupportedOperationException("Firebase SDK not available");
        }
    }
}

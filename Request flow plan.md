### Goal
Implement the complete “Request a Service” flow in a Spring Boot backend, integrated with Firebase (Auth, Firestore, FCM, Storage), Stripe (Payments + Connect, optional Identity), and Google Maps Platform (Places/Geocoding/Directions). This blueprint is a step‑by‑step checklist with concrete setup actions, endpoints, data models, and sequencing.

---

### High‑level architecture (MVP)
- Mobile apps (Client + Provider) authenticate with Firebase Auth; they receive a Firebase ID token.
- Spring Boot REST API validates Firebase ID tokens with Firebase Admin SDK and authorizes access.
- Spring Boot persists domain data in Firestore (via Firebase Admin SDK). Optionally, store analytics/BI in a relational DB later.
- Stripe handles payment holds (manual capture) and payouts via Connect.
- FCM delivers push notifications to clients/providers.
- Google Maps Platform powers address autocomplete, geocoding, and (optional) ETA.

---

### Part 1 — External services: what to create and configure

#### 1) Firebase project setup
- Create a Firebase project (or select existing) in Firebase Console.
- Enable products:
    - Auth: enable providers you need now: `Email/Password` and `Phone`. Add `Google`/`Apple` later if desired.
    - Firestore: start in `Production` mode.
    - Cloud Storage: default bucket.
    - Cloud Messaging (FCM): no extra steps, just note the server key.
- Register your apps:
    - Android app: add package name; download `google-services.json` into your Android app.
    - iOS app: add bundle ID; download `GoogleService-Info.plist` into your iOS app.
- Create a service account for the backend:
    - Firebase Console → Project Settings → Service accounts → “Generate new private key” (JSON). Store as a secret in your backend environment (e.g., `GOOGLE_APPLICATION_CREDENTIALS` pointing to the JSON path, or load JSON from an env var/secret manager).
- Collect Firebase config values for mobile:
    - `apiKey`, `projectId`, `appId`, etc. These go into the Flutter client’s config (already typical if you integrated Auth).
- FCM keys:
    - Note the `Server key` for use by the backend to send notifications (Firebase Admin SDK also works without pasting keys if you use the service account).

Security reminders:
- Never commit the service account JSON to VCS.
- Restrict service account usage to your backend. Prefer a secret manager (GCP Secret Manager, AWS Secrets Manager, Vault).

#### 2) Stripe setup
- Create a Stripe account and switch to Test mode.
- Obtain API keys:
    - `STRIPE_SECRET_KEY` (backend only)
    - `STRIPE_PUBLISHABLE_KEY` (mobile app)
- Stripe Connect (Express) for provider payouts:
    - Enable Connect; configure branding and statements.
    - In the dashboard, create an “Account Link” flow for onboarding (you will do this via API at runtime).
- Webhook endpoint(s):
    - Add a test webhook endpoint, e.g., `https://api.serveme.example/webhooks/stripe`
    - Subscribe to events: `payment_intent.succeeded`, `payment_intent.payment_failed`, `payment_intent.amount_capturable_updated`, `charge.refunded`, `account.updated`, `checkout.session.completed` (if you use Checkout), `identity.verification_session.*` (if using Stripe Identity).
    - Copy the webhook `Signing secret` and store as `STRIPE_WEBHOOK_SECRET`.
- Optional: Stripe Identity
    - Enable Identity. You’ll create `VerificationSessions` for providers who must pass IDV before accepting jobs.

#### 3) Google Maps Platform setup
- Create a Google Cloud project (can be the same as Firebase if you like) and enable APIs:
    - `Places API` (for Autocomplete)
    - `Geocoding API` (address → lat/lng)
    - `Maps SDK for Android` / `iOS` (map rendering in mobile)
    - `Directions API` or `Distance Matrix API` (optional – ETA)
- Create two API keys:
    - Mobile key: restrict to Android/iOS app signatures and required APIs.
    - Server key: restrict by IP and required APIs, used by Spring Boot for geocoding/directions.

---

### Part 2 — Spring Boot project: configuration and dependencies

#### 4) Dependencies (Gradle/Maven)
- Core:
    - `spring-boot-starter-web`
    - `spring-boot-starter-validation`
    - `spring-boot-starter-security` (optional, if you want method security)
    - `jackson-databind`
- Firebase Admin SDK:
    - `com.google.firebase:firebase-admin`
- Stripe Java SDK:
    - `com.stripe:stripe-java`
- HTTP client:
    - `spring-boot-starter-webflux` (WebClient) or `OkHttp` for calling Google APIs
- Optional:
    - `spring-boot-starter-actuator` for health/metrics
    - Logging: `logback` (default), `slf4j`

#### 5) Configuration (application.yml)
Use environment variables in production; example:
```yaml
server:
  port: 8080

app:
  firebase:
    projectId: ${FIREBASE_PROJECT_ID}
    credentialsFile: ${GOOGLE_APPLICATION_CREDENTIALS:}
  stripe:
    secretKey: ${STRIPE_SECRET_KEY}
    webhookSecret: ${STRIPE_WEBHOOK_SECRET}
    applicationFeePercent: 15
  maps:
    serverApiKey: ${GOOGLE_MAPS_SERVER_KEY}
```

#### 6) Initialize Firebase Admin in Spring
```java
@Configuration
public class FirebaseConfig {
  @PostConstruct
  public void init() throws IOException {
    // Option A: GOOGLE_APPLICATION_CREDENTIALS env points to the JSON file
    if (FirebaseApp.getApps().isEmpty()) {
      FirebaseOptions options = FirebaseOptions.builder()
          .setCredentials(GoogleCredentials.getApplicationDefault())
          .build();
      FirebaseApp.initializeApp(options);
    }
  }
}
```

#### 7) Security: validate Firebase ID tokens on each request
Create a filter that reads `Authorization: Bearer <idToken>` and verifies it.
```java
@Component
public class FirebaseAuthFilter extends OncePerRequestFilter {
  @Override
  protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String header = request.getHeader("Authorization");
    if (header != null && header.startsWith("Bearer ")) {
      String idToken = header.substring(7);
      try {
        FirebaseToken decoded = FirebaseAuth.getInstance().verifyIdToken(idToken);
        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
            decoded.getUid(), null, List.of() // add roles based on your user record
        );
        SecurityContextHolder.getContext().setAuthentication(auth);
      } catch (FirebaseAuthException e) {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        return;
      }
    }
    chain.doFilter(request, response);
  }
}
```
Register the filter in your security configuration and secure routes.

#### 8) Firestore access from Spring
Use the Admin SDK to read/write collections.
```java
@Service
public class FirestoreService {
  private final Firestore db = FirestoreClient.getFirestore();

  public ApiFuture<DocumentReference> create(String collection, Map<String, Object> data) {
    return db.collection(collection).add(data);
  }

  public ApiFuture<WriteResult> set(String collection, String id, Map<String, Object> data) {
    return db.collection(collection).document(id).set(data, SetOptions.merge());
  }

  public DocumentSnapshot get(String collection, String id) throws Exception {
    return db.collection(collection).document(id).get().get();
  }

  public <T> T runTransaction(Function<Transaction, T> function) throws Exception {
    return db.runTransaction(function).get();
  }
}
```

---

### Part 3 — Data model (Firestore collections)
Keep it close to the earlier proposal. Collections: `users`, `providers`, `services`, `jobs`, `reviews`, and subcollection `jobs/{jobId}/messages`.

```json
users/{userId} {
  role: "client" | "provider",
  firstName, lastName, email, phone,
  photoUrl,
  defaultAddress: { line1, lat, lng, geohash },
  createdAt, updatedAt
}

providers/{providerId} {
  userId,
  serviceTypes: ["plumber", "cleaner"],
  isOnline: true,
  lat, lng, geohash,
  ratingAvg: 4.8, ratingCount: 123,
  stripe: { accountId, payoutsEnabled },
  verified: { identity: false },
  createdAt, updatedAt
}

services/{serviceType} {
  displayName: "Plumber",
  basePrice: 49.0,
  addOns: [{ id: "unclog_drain", label: "Unclog drain", price: 29.0 }],
  minRadiusKm: 5,
  maxRadiusKm: 20
}

jobs/{jobId} {
  serviceType: "plumber",
  clientId,
  status: "pending" | "assigned" | "enroute" | "arrived" | "in_progress" | "completed" | "canceled" | "expired",
  description,
  photos: [url],
  address: { line1, lat, lng, geohash },
  desiredTime: { type: "asap" | "scheduled", when: 1696287200 },
  price: { currency: "USD", subtotal: 49.0, fees: 4.0, total: 53.0 },
  payment: { paymentIntentId, status: "requires_capture" },
  assignedProviderId: null,
  createdAt, expiresAt,
  acceptedAt, startedAt, completedAt
}

jobs/{jobId}/messages/{messageId} {
  senderId,
  text,
  sentAt,
  type: "text" | "image"
}

reviews/{reviewId} {
  jobId, clientId, providerId,
  rating: 1..5, comment, createdAt
}
```

Geohash note: store `geohash` for both provider and job address to enable radius queries (either on client using Geo libraries, or server by range queries).

---

### Part 4 — REST API surface (Spring Boot)

#### 9) Endpoints
```http
POST   /api/v1/providers/onboarding-link        # create Stripe Connect onboarding link
GET    /api/v1/providers/me                     # provider profile, incl. payoutsEnabled
POST   /api/v1/providers/location               # update lat/lng + geohash + isOnline

GET    /api/v1/services                         # list service types + catalog prices

POST   /api/v1/jobs                             # client creates job (and triggers notifications)
GET    /api/v1/jobs?role=client|provider        # list my jobs
GET    /api/v1/jobs/{id}                        # get job detail
POST   /api/v1/jobs/{id}/accept                 # provider accept (first wins)
POST   /api/v1/jobs/{id}/status                 # provider updates status (enroute/arrived/complete)

POST   /api/v1/jobs/{id}/messages               # send message
GET    /api/v1/jobs/{id}/messages               # list messages

POST   /api/v1/payments/intent                  # create PaymentIntent on job creation (manual capture)
POST   /api/v1/payments/{paymentIntentId}/capture # capture on completion

POST   /webhooks/stripe                         # Stripe webhook
```

Add role checks (client vs provider) using decoded Firebase UID and your user record.

---

### Part 5 — Core flows and backend logic

#### 10) Provider onboarding (Stripe Connect)
- Preconditions: provider has a `users/{uid}` and `providers/{uid}` doc.
- Call Stripe to create or fetch a connected account for the provider:
```java
Account account = Account.create(AccountCreateParams.builder()
  .setType(AccountCreateParams.Type.EXPRESS)
  .build());
// save account.getId() in providers/{uid}.stripe.accountId
```
- Create onboarding link for provider app:
```java
AccountLink link = AccountLink.create(AccountLinkCreateParams.builder()
  .setAccount(accountId)
  .setRefreshUrl("https://serveme.example/connect/refresh")
  .setReturnUrl("serveme://connect/return")
  .setType(AccountLinkCreateParams.Type.ACCOUNT_ONBOARDING)
  .build());
// return link.getUrl() to the app to open in a webview or external browser
```
- After onboarding, Stripe will update `payouts_enabled`; you’ll receive `account.updated` webhooks. Update `providers/{uid}.stripe.payoutsEnabled=true` when eligible.

#### 11) Client creates job
- Request payload includes: `serviceType`, `description`, `photos` (uploaded to Firebase Storage by client, then share URLs), `address` (lat/lng), `desiredTime`, and selected add‑ons.
- Pricing: compute total from your `services` catalog.
- Payment hold: create a PaymentIntent with manual capture.
```java
PaymentIntentCreateParams params = PaymentIntentCreateParams.builder()
  .setAmount(amountInCents)
  .setCurrency("usd")
  .setCaptureMethod(PaymentIntentCreateParams.CaptureMethod.MANUAL)
  .setPaymentMethod(req.getPaymentMethodId()) // from client-side Stripe SDK
  .setConfirm(true)
  .setConfirmationMethod(PaymentIntentCreateParams.ConfirmationMethod.AUTOMATIC)
  .setDescription("ServeMe job hold")
  .build();
PaymentIntent intent = PaymentIntent.create(params);
```
- Persist `jobs/{jobId}` with `status=pending`, `paymentIntentId`, `expiresAt=now+10min`.
- Fan‑out to nearby providers (see §13).

#### 12) Atomic provider accept (first valid wins)
Use a Firestore transaction to claim the job.
```java
public Job acceptJob(String jobId, String providerUid) throws Exception {
  return firestoreService.runTransaction(tx -> {
    DocumentReference ref = db.collection("jobs").document(jobId);
    DocumentSnapshot snap = tx.get(ref).get();
    String status = snap.getString("status");
    if (!"pending".equals(status)) {
      throw new RuntimeException("already_taken");
    }
    tx.update(ref, Map.of(
      "status", "assigned",
      "assignedProviderId", providerUid,
      "acceptedAt", FieldValue.serverTimestamp()
    ));
    return null; // map to DTO after commit if needed
  });
}
```
Return 200 with updated job; if conflict, return 409 with code `already_taken`.

#### 13) Finding and notifying nearby providers
- Provider presence: providers update location via `/providers/location` while online. Store `lat`, `lng`, `geohash`, `isOnline`.
- On job creation:
    - Query providers by `isOnline=true` and `serviceTypes` containing `serviceType` within an initial radius (e.g., 5–10 km). For Firestore, implement geohash bounding box queries: compute range prefixes by radius and make ranged queries on `geohash`.
    - Select up to N (e.g., 10) candidate providers.
    - Push fan‑out:
        - Write a lightweight `pendingJobs` list in provider app via Firestore query (providers listen for `status=pending` + geofence), and
        - Send FCM notifications to those providers’ `fcmTokens`.
- Expand search if no acceptance in 30–60s: increase radius and repeat until timeout or max radius.

FCM send example with Admin SDK:
```java
Message message = Message.builder()
  .setToken(providerToken)
  .putData("type", "job_offer")
  .putData("jobId", jobId)
  .putData("serviceType", serviceType)
  .build();
String msgId = FirebaseMessaging.getInstance().send(message);
```

#### 14) Live tracking and status updates
- Provider app shares foreground location every 5–10 seconds:
    - Either update `providers/{uid}` location, or write to `jobLocations/{jobId}` (less noise globally).
- Client subscribes to job or location doc for real‑time position.
- Status transitions (enforced by backend):
    - `assigned -> enroute`
    - `enroute -> arrived`
    - `arrived -> in_progress`
    - `in_progress -> completed`
- Validate actor and transition legality in your `/jobs/{id}/status` endpoint.

#### 15) Completion and payment capture + payout
- On `completed` by provider:
    - Verify job state; then capture the PaymentIntent:
```java
PaymentIntent intent = PaymentIntent.retrieve(paymentIntentId);
PaymentIntent captured = intent.capture();
```
- Transfer provider share: for Connect Standard/Express, you can use `Transfer` against the charge or use `TransferData` on the PaymentIntent when creating it. MVP approach:
    - On create, set `application_fee_amount` and `transfer_data[ destination ] = providerAccountId` to automatically route funds when captured; otherwise, perform a separate `Transfer` after capture.
- Update job `status=completed`, write `completedAt`.

#### 16) Messaging
- Use `jobs/{jobId}/messages` subcollection.
- Backend can proxy writes or the app can write directly (simpler). For push notifications on new messages, either:
    - Mobile writes message → Cloud Function/Backend listens and sends FCM, or
    - Mobile posts to `/jobs/{id}/messages` endpoint → backend writes to Firestore and sends FCM.

#### 17) Reviews
- After completion, client posts rating/comment to `/reviews` with references to `jobId` and `providerId`.
- Update aggregate rating for provider in a transaction (recompute `ratingAvg`, `ratingCount`).

---

### Part 6 — Firebase Storage for photos
- Client uploads pre‑work and post‑work photos directly to Firebase Storage using client SDK.
- Store public-ish download URLs (or secured tokens) in the `jobs/{jobId}.photos` array.
- Storage rules (MVP idea): allow authenticated users to upload under `jobs/{jobId}/client/{uid}` if they own the job; similarly, providers can upload under `jobs/{jobId}/provider/{uid}` for after‑photos.

---

### Part 7 — Security and rules

#### 18) Firestore security (if mobiles read/write directly)
Sketch for rules (tune to your exact model):
```javascript
rules_version = '2';
service cloud.firestore {
  match /databases/{database}/documents {
    function isSignedIn() { return request.auth != null; }
    function uid() { return request.auth.uid; }

    match /users/{userId} {
      allow read: if isSignedIn();
      allow write: if isSignedIn() && uid() == userId;
    }

    match /providers/{providerId} {
      allow read: if isSignedIn();
      allow write: if isSignedIn() && uid() == resource.data.userId;
    }

    match /jobs/{jobId} {
      allow read: if isSignedIn() && (
        resource.data.clientId == uid() || resource.data.assignedProviderId == uid()
      );
      allow create: if isSignedIn();
      allow update: if isSignedIn() && (
        (uid() == resource.data.clientId && resource.data.status in ["pending", "assigned"]) ||
        (uid() == resource.data.assignedProviderId)
      );
    }

    match /jobs/{jobId}/messages/{messageId} {
      allow read, create: if isSignedIn() && (
        get(/databases/$(database)/documents/jobs/$(jobId)).data.clientId == uid() ||
        get(/databases/$(database)/documents/jobs/$(jobId)).data.assignedProviderId == uid()
      );
    }
  }
}
```

If you funnel all writes through Spring Boot, you can tighten rules to server‑only and use Admin SDK exclusively. For MVP, hybrid is OK: direct reads for speed, server writes for critical ops (accept/capture/assign).

---

### Part 8 — Google Maps usage in the flow

#### 19) Client side
- Address Autocomplete: use Places SDK in the Flutter app (`flutter_google_places`, or `google_maps_flutter` + Places API).
- On selection, resolve to `lat/lng` and store address + coordinates on the job.
- For map tracking, use `google_maps_flutter` to display provider marker.

#### 20) Server side (optional)
- Reverse geocoding or ETA calculation:
```java
WebClient client = WebClient.create("https://maps.googleapis.com/maps/api");
Mono<String> resp = client.get()
  .uri(uriBuilder -> uriBuilder
    .path("/distancematrix/json")
    .queryParam("origins", providerLat + "," + providerLng)
    .queryParam("destinations", jobLat + "," + jobLng)
    .queryParam("key", mapsServerApiKey)
    .build())
  .retrieve().bodyToMono(String.class);
```
Parse and return ETA if you choose to show it from the backend.

---

### Part 9 — Notifications (FCM)

#### 21) Token registration
- Mobile posts its FCM token to `/users/me/token` (or in user profile). Store the most recent token and a list of valid tokens if needed.

#### 22) Notification types
- Job offer to providers: `type=job_offer`
- Assignment to client: `type=job_assigned`
- Status updates: `type=job_status`
- New message: `type=chat`

Construct data messages with minimal payload and let the app fetch details on open.

---

### Part 10 — Error handling, idempotency, and observability

#### 23) Idempotency
- Payment and accept endpoints should be idempotent:
    - Accept: if already assigned to the same provider, return 200; if assigned to someone else, 409.
    - Capture: safe to retry by checking job/payment state.
- Use `Idempotency-Key` header for payment calls to Stripe if you might retry.

#### 24) Timeouts and expirations
- On job creation, set `expiresAt = now + 10 min`.
- A scheduled job (e.g., Cloud Scheduler calling an endpoint) or a background worker should mark `pending -> expired` if time passes with no acceptance.

#### 25) Logging and metrics
- Add structured logs with jobId/providerId.
- Expose `/actuator/health` and log Stripe webhook events.
- Optional: store key events into a `logs` collection for admin diagnostics.

---

### Part 11 — Concrete build plan (checklist)

Sprint 1 — Core data + request + accept
- [ ] Firebase Admin SDK initialized; Auth filter verifying ID tokens.
- [ ] Collections: `users`, `providers`, `services` (seed minimal catalog), `jobs`.
- [ ] Endpoint: `GET /services`.
- [ ] Endpoint: `POST /jobs` creates job, computes price, creates PaymentIntent hold, writes Firestore, sets TTL.
- [ ] Provider location update endpoint and online toggle.
- [ ] Nearby provider search + fan‑out via FCM and in‑app list.
- [ ] `POST /jobs/{id}/accept` transactional accept.

Sprint 2 — Status + tracking + payments
- [ ] Provider status transitions `enroute/arrived/in_progress/completed` with validation.
- [ ] Live location streaming (write to Firestore, subscribe in app).
- [ ] `POST /payments/{intentId}/capture` invoked on `completed`.
- [ ] Stripe webhook handler `/webhooks/stripe` updates payment state.

Sprint 3 — Messaging + reviews + onboarding
- [ ] `POST/GET /jobs/{id}/messages` + FCM chat notifications.
- [ ] Client `POST /reviews` and aggregate provider ratings.
- [ ] Stripe Connect onboarding link endpoint and webhook for `account.updated`.
- [ ] Optional: Stripe Identity verification flow for providers.

Polish
- [ ] Error codes & UX for race conditions and payment failures.
- [ ] Admin internal page: list recent jobs, force status, refund test.
- [ ] “Track_of the changes.md” and code comments on all new controllers/services.

---

### Part 12 — Minimal DTOs and controller sketches

```java
@Data
public class CreateJobRequest {
  private String serviceType;
  private String description;
  private List<String> photoUrls;
  private Address address;
  private DesiredTime desiredTime; // { type, when }
  private String paymentMethodId;  // Stripe PM from client
  private List<String> addOnIds;
}

@RestController
@RequestMapping("/api/v1/jobs")
@RequiredArgsConstructor
public class JobController {
  private final JobService jobService;

  @PostMapping
  public ResponseEntity<JobDto> create(@RequestBody @Valid CreateJobRequest req, Principal p) {
    return ResponseEntity.status(HttpStatus.CREATED).body(jobService.create(req, p.getName()));
  }

  @PostMapping("/{id}/accept")
  public ResponseEntity<JobDto> accept(@PathVariable String id, Principal p) {
    return ResponseEntity.ok(jobService.accept(id, p.getName()));
  }

  @PostMapping("/{id}/status")
  public ResponseEntity<JobDto> updateStatus(@PathVariable String id, @RequestBody UpdateStatusRequest req, Principal p) {
    return ResponseEntity.ok(jobService.updateStatus(id, req.getStatus(), p.getName()));
  }
}
```

---

### Part 13 — Environment variables to provision
- `FIREBASE_PROJECT_ID`
- `GOOGLE_APPLICATION_CREDENTIALS` (file path) or `FIREBASE_CREDENTIALS_JSON` (inline secret; then load manually)
- `STRIPE_SECRET_KEY`
- `STRIPE_PUBLISHABLE_KEY` (mobile)
- `STRIPE_WEBHOOK_SECRET`
- `GOOGLE_MAPS_SERVER_KEY`

Mobile apps also need:
- Firebase mobile config files (`google-services.json`, `GoogleService-Info.plist`)
- Google Maps mobile key (restricted to app signatures)

---

### Part 14 — Testing plan (end‑to‑end)
- Auth: sign in test client/provider via Firebase Auth; verify API tokens accepted by backend.
- Provider onboarding: go through Connect Express test flow; ensure `payoutsEnabled` updates.
- Job creation: create job with a test PaymentMethod (e.g., `pm_card_visa`) → check PaymentIntent in Stripe dashboard (requires_capture).
- Fan‑out: see providers receive FCM and in‑app pending list.
- Accept: accept from one provider; second provider should get a conflict.
- Tracking: simulate provider location updates; client sees map marker moving.
- Completion: provider completes → capture occurs; funds reflect in Stripe test dashboard.
- Review: client posts rating → provider aggregate updates.
- Webhooks: fire Stripe test webhooks; verify signatures and state updates.

---

### Part 15 — Notes on theming and comments
- Keep your Flutter UI consistent with existing theme/fonts/colors. Add meaningful comments to new widgets and blocs regarding:
    - When network calls start/stop and loading spinners
    - How streams/subscriptions are disposed
    - What each status transition means for the UI
- Maintain a `Track_of the changes.md` in the repo with dates, files touched, and purpose.

---

### Final takeaway
Follow this sequence: configure Firebase/Stripe/Google; wire Spring Boot auth via Firebase; define Firestore data; implement the REST endpoints for job lifecycle; integrate FCM notifications and Stripe payments; add location tracking, chat, and reviews; and test end‑to‑end with Stripe test data. This keeps the MVP coherent, secure, and observable while staying entirely in Spring Boot for backend logic.
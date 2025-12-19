# ServeMi

A modern service marketplace platform connecting clients with local service providers in real-time. Built with Flutter (mobile) and Spring Boot (backend), featuring live location tracking, instant job offers, and integrated payments.

## Overview

ServeMi is a two-sided marketplace that enables:
- **Clients** to request services (plumbing, cleaning, painting, etc.) and get matched with nearby providers
- **Providers** to receive job offers, manage availability, track earnings, and build their reputation

### Key Features

#### For Clients
- 🔍 Browse service catalog with pricing
- 📍 Location-based provider matching
- ⏱️ Real-time job tracking (pending → assigned → enroute → completed)
- 💳 Secure Paystack payment integration
- 💬 In-app messaging with providers
- ⭐ Review and rate completed services

#### For Providers
- 📱 Live job offer notifications via FCM
- 🗺️ Real-time location tracking and presence management
- 📊 Earnings dashboard with goals
- 🔐 KYC verification (Arya integration)
- 💰 Direct payouts via Paystack subaccounts
- 📅 Availability and service radius management

## Tech Stack

### Mobile (Flutter)
- **Framework**: Flutter 3.x with Material 3
- **State Management**: Provider pattern with ChangeNotifier
- **Networking**: Dio with cookie-based auth + JWT tokens
- **Storage**: FlutterSecureStorage (tokens), SharedPreferences (settings)
- **Maps**: Google Maps / Geolocator for location services
- **Push Notifications**: Firebase Cloud Messaging (FCM)

### Backend (Spring Boot)
- **Framework**: Spring Boot 3.x with Java 17+
- **Database**: Firestore (NoSQL document store)
- **Authentication**: JWT access tokens + HTTP-only refresh cookies
- **Payments**: Paystack API (South Africa)
- **Notifications**: Firebase Admin SDK for FCM
- **Location**: Geohash-based proximity search

## Architecture

### System Flow

```
┌─────────────┐         ┌─────────────┐         ┌─────────────┐
│   Client    │         │   Backend   │         │  Provider   │
│    App      │◄───────►│   (API)     │◄───────►│    App      │
└─────────────┘         └─────────────┘         └─────────────┘
                              │
                              ├──► Firestore
                              ├──► Paystack
                              └──► Firebase (FCM)
```

### Job Lifecycle

1. **Client creates job** → POST `/api/v1/jobs`
2. **Backend fans out** to nearby providers (FCM notifications)
3. **Provider accepts** → POST `/api/v1/jobs/{id}/accept`
4. **Status updates**: assigned → enroute → arrived → in_progress → completed
5. **Payment verification** via Paystack webhook
6. **Rating & review** after completion

## Getting Started

### Prerequisites

- Flutter SDK 3.10+
- Java 17+
- Firebase project (Firestore + FCM)
- Paystack account (for South Africa)
- Android Studio / VS Code
- Git

### Backend Setup

1. **Clone the repository**
   ```bash
   git clone https://github.com/yourusername/servemi.git
   cd serveme-api
   ```

2. **Configure Firebase**
   - Download `serviceAccountKey.json` from Firebase Console
   - Place in `src/main/resources/`
   - Set `GOOGLE_APPLICATION_CREDENTIALS` env var (optional)

3. **Set environment variables**
   ```bash
   export PAYSTACK_SECRET_KEY=sk_test_xxxxx
   export JWT_SECRET=your-secret-key
   export JWT_EXPIRATION=900000
   export REFRESH_TOKEN_EXPIRATION=604800000
   ```

4. **Run the application**
   ```bash
   ./mvnw spring-boot:run
   ```
   API will be available at `http://localhost:8080`

### Mobile Setup

1. **Navigate to mobile directory**
   ```bash
   cd ../mobile
   ```

2. **Install dependencies**
   ```bash
   flutter pub get
   ```

3. **Configure Firebase**
   - Add `google-services.json` (Android) to `android/app/`
   - Add `GoogleService-Info.plist` (iOS) to `ios/Runner/`

4. **Update API endpoint**
   Create `.env` file:
   ```
   API_BASE_URL=https://your-backend-url.com
   ```

5. **Run the app**
   ```bash
   flutter run
   ```

## API Documentation

### Authentication
```http
POST /api/auth/register
POST /api/auth/login
POST /api/auth/refresh
POST /api/auth/logout
```

### Jobs
```http
GET    /api/v1/jobs?role=client|provider
POST   /api/v1/jobs
GET    /api/v1/jobs/{id}
POST   /api/v1/jobs/{id}/accept
POST   /api/v1/jobs/{id}/status
```

### Providers
```http
GET    /api/v1/providers/me
POST   /api/v1/providers/onboarding
POST   /api/v1/providers/location
GET    /api/v1/providers/nearby?lat=&lng=&serviceType=
```

### Payments
```http
POST   /api/v1/payments/intent
POST   /api/v1/payments/verify
POST   /api/v1/providers/paystack/subaccount
GET    /api/v1/providers/paystack/account
```

## Project Structure

```
servemi/
├── backend/
│   ├── src/main/java/com/logicnativesolution/servemeapi/
│   │   ├── controller/      # REST endpoints
│   │   ├── service/         # Business logic
│   │   ├── model/           # Data models
│   │   ├── dto/             # Request/response objects
│   │   └── config/          # Security, Firebase, Paystack
│   └── src/main/resources/
│       └── application.yml
│
├── mobile/
│   ├── lib/
│   │   ├── api/            # API clients
│   │   ├── auth/           # Authentication logic
│   │   ├── view/           # Screens (client & provider)
│   │   ├── model/          # Data models
│   │   └── main.dart
│   ├── android/
│   └── ios/
│
└── README.md
```

## Key Integrations

### Paystack (Payments)
- Initialize payment intents on job creation
- Verify transactions via webhook or polling
- Provider payouts via subaccounts
- Bank list API for South Africa

### Firebase
- **Firestore**: Document storage for users, jobs, providers
- **FCM**: Push notifications for job offers and messages
- **Authentication**: Optional (currently using custom JWT)

### Arya (KYC)
- Identity verification flow integrated in both apps
- Required for provider approval
- Biometric and document verification

## Development

### Running Tests
```bash
# Backend
./mvnw test

# Mobile
flutter test
```

### Code Style
- **Java**: Google Java Style Guide
- **Dart**: Effective Dart + flutter_lints

### Git Workflow
1. Create feature branch from `develop`
2. Make changes and commit with descriptive messages
3. Push and create pull request
4. Code review and merge

## Deployment

### Backend (Production)
1. Build JAR: `./mvnw clean package`
2. Deploy to cloud provider (Google Cloud Run, AWS, etc.)
3. Set production environment variables
4. Configure Paystack webhooks to point to your domain

### Mobile (Release)
```bash
# Android
flutter build apk --release
flutter build appbundle --release

# iOS
flutter build ipa --release
```

## Security Considerations

- ✅ JWT tokens stored in secure storage
- ✅ HTTP-only refresh cookies
- ✅ CORS configured for production domains
- ✅ Rate limiting on sensitive endpoints
- ✅ Input validation on all API requests
- ✅ Firestore security rules enforced
- ⚠️ Always use HTTPS in production
- ⚠️ Rotate secrets regularly


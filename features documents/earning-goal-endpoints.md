# Feature: Provider Earning Goal (Server Contract)

Last updated: 2025-10-16

## Summary
This document defines the minimal backend endpoints and data model required to replace mocked earning goal data in the Provider dashboard. It enables providers to set and read their earning goal so the client can display progress in the Earnings card and update the goal from the UI.

## Endpoints
All endpoints are authenticated and operate on the current authenticated provider (no explicit providerId required).

1) GET /api/v1/providers/earning-goal
- Returns the current earning goal for the authenticated provider.
- 200 Response body:
  {
    "amount": 60000,             // integer minor units (e.g., cents)
    "currency": "zar",          // lowercase ISO currency code
    "period": "week",           // "week" | "month"
    "startDate": "2025-10-13"   // optional ISO date when the goal period starts (YYYY-MM-DD)
  }
- 204 No Content if the provider has not set a goal yet (client will show "Set goal").
- 404 may also be used to indicate no goal yet; the client tolerates 204 or 404.

2) PUT /api/v1/providers/earning-goal
- Creates or updates the earning goal for the authenticated provider.
- Request body:
  {
    "amount": 60000,             // required, integer minor units (>= 0)
    "currency": "zar",          // required, lowercase ISO currency
    "period": "week",           // required, one of: week, month
    "startDate": "2025-10-13"   // optional YYYY-MM-DD; if omitted, server may set to start of current period
  }
- Responses:
  - 200 OK with the saved resource (same shape as GET) on update
  - 201 Created with the saved resource on first creation
  - 400 Bad Request on validation failure (see Validation)

## Validation
- amount: integer, >= 0 (minor units). Reject non-integers and negatives.
- currency: string, 3–5 chars, lowercase. Prefer ISO 4217 codes. Server may coerce to lowercase.
- period: enum in { "week", "month" }.
- startDate: optional; if provided, validate format YYYY-MM-DD and that date is within a reasonable window (± 2 years).

## Persistence Model (suggested)
- Collection: providers/{providerId}/settings
- Document: earningGoal
- Document body:
  {
    amount: number,             // integer minor units
    currency: string,           // e.g., "zar"
    period: string,             // "week" | "month"
    startDate: string|null,     // ISO date or null
    updatedAt: timestamp,
    updatedBy: uid
  }

## Security
- Require authenticated provider role to read/write own earning goal.
- Deny access to other users.
- Admins may read/write for support purposes (optional).

## Error Model
Return structured errors consistent with existing endpoints (see Paystack notes) to help the client format messages:
{
  "message": "Validation failed: amount must be >= 0",
  "field": "amount",                // optional, field with an error
  "code": "invalid_amount",         // optional, short code
  "upstream": { ... },                // optional, if applicable
  "stage": "validation"             // optional
}

## Examples
- GET 200
{
  "amount": 75000,
  "currency": "zar",
  "period": "week",
  "startDate": "2025-10-13"
}

- PUT Request
{
  "amount": 60000,
  "currency": "zar",
  "period": "month"
}

- PUT 201/200 Response (echo saved)
{
  "amount": 60000,
  "currency": "zar",
  "period": "month",
  "startDate": "2025-10-01"
}

## Frontend Wiring Notes (Flutter)
- The client will call:
  - GET /api/v1/providers/earning-goal on dashboard init to show current goal.
  - PUT /api/v1/providers/earning-goal when the provider saves a new/updated goal.
- Client expects minor units (int). It will convert user-entered major units (e.g., 600.00 ZAR) to minor units before sending.
- If GET returns 204/404, the UI shows "Set goal" and progress bar hidden until goal is set.

## Changelog
- 2025-10-16: Initial server contract added for Earning Goal.
-- ======================
-- Roles
-- ======================
CREATE TABLE roles (
   id   UUID PRIMARY KEY,
   name VARCHAR(10) NOT NULL
);

-- ======================
-- Users
-- ======================
CREATE TABLE users (
   id            UUID PRIMARY KEY,
   role_id       UUID REFERENCES roles(id) ON DELETE CASCADE,
   id_number     VARCHAR(13) NOT NULL,
   first_name    VARCHAR(50) NOT NULL,
   last_name     VARCHAR(50) NOT NULL,
   email         VARCHAR(255) UNIQUE NOT NULL,
   phone_number  VARCHAR(10) UNIQUE NOT NULL,
   date_of_birth DATE NOT NULL,
   gender        VARCHAR(10) NOT NULL,
   password      VARCHAR(255) NOT NULL
);

-- ======================
-- Profiles
-- ======================
CREATE TABLE profiles (
    id         UUID PRIMARY KEY,
    user_id    UUID UNIQUE REFERENCES users(id) ON DELETE CASCADE,
    bio        TEXT,
    experience VARCHAR(255),
    rating     INT CHECK (rating >= 0 AND rating <= 5)
);

-- ======================
-- Addresses
-- ======================
CREATE TABLE addresses (
    id          UUID PRIMARY KEY,
    user_id     UUID REFERENCES users(id) ON DELETE CASCADE,
    street      VARCHAR(255),
    city        VARCHAR(100),
    postal_code VARCHAR(20)
);

-- ======================
-- Categories
-- ======================
CREATE TABLE categories (
    id   UUID PRIMARY KEY,
    name VARCHAR(100) UNIQUE NOT NULL
);

-- ======================
-- Services
-- ======================
CREATE TABLE services (
    id          UUID PRIMARY KEY,
    category_id UUID REFERENCES categories(id) ON DELETE SET NULL,
    name        VARCHAR(150) NOT NULL,
    description TEXT
);

-- ======================
-- Bookings
-- ======================
CREATE TABLE bookings (
    id             UUID PRIMARY KEY,
    client_id      UUID REFERENCES users(id) ON DELETE CASCADE,
    provider_id    UUID REFERENCES users(id) ON DELETE CASCADE,
    service_id     UUID REFERENCES services(id) ON DELETE CASCADE,
    scheduled_date TIMESTAMP NOT NULL,
    status         VARCHAR(50) NOT NULL
);

-- ======================
-- Payments
-- ======================
CREATE TABLE payments (
    id         UUID PRIMARY KEY,
    booking_id UUID REFERENCES bookings(id) ON DELETE CASCADE,
    amount     DECIMAL(10,2) NOT NULL,
    method     VARCHAR(50),
    status     VARCHAR(50)
);

-- ======================
-- Reviews
-- ======================
CREATE TABLE reviews (
    id          UUID PRIMARY KEY,
    reviewer_id UUID REFERENCES users(id) ON DELETE CASCADE,
    reviewed_id UUID REFERENCES users(id) ON DELETE CASCADE,
    rating      INT CHECK (rating >= 0 AND rating <= 5),
    comment     TEXT
);

-- ======================
-- Availability
-- ======================
CREATE TABLE availability (
    id          UUID PRIMARY KEY,
    provider_id UUID REFERENCES users(id) ON DELETE CASCADE,
    day_of_week VARCHAR(20),
    start_time  TIME,
    end_time    TIME
);

-- ======================
-- Notifications
-- ======================
CREATE TABLE notifications (
    id      UUID PRIMARY KEY,
    user_id UUID REFERENCES users(id) ON DELETE CASCADE,
    message TEXT NOT NULL,
    type    VARCHAR(50),
    status  VARCHAR(50)
);

-- ======================
-- Service Pricing
-- ======================
CREATE TABLE service_pricing (
    id          UUID PRIMARY KEY,
    provider_id UUID REFERENCES users(id) ON DELETE CASCADE,
    service_id  UUID REFERENCES services(id) ON DELETE CASCADE,
    price       DECIMAL(10,2) NOT NULL,
    unit        VARCHAR(50)
);

-- ======================
-- Disputes
-- ======================
CREATE TABLE disputes (
  id          UUID PRIMARY KEY,
  booking_id  UUID REFERENCES bookings(id) ON DELETE CASCADE,
  description TEXT,
  status      VARCHAR(50)
);

-- ======================
-- Verifications
-- ======================
CREATE TABLE verifications (
   id            UUID PRIMARY KEY,
   user_id       UUID REFERENCES users(id) ON DELETE CASCADE,
   document_type VARCHAR(100),
   document_url  VARCHAR(255),
   status        VARCHAR(50)
);

-- ======================
-- Service Areas
-- ======================
CREATE TABLE service_areas (
    id          UUID PRIMARY KEY,
    provider_id UUID REFERENCES users(id) ON DELETE CASCADE,
    city        VARCHAR(100),
    region      VARCHAR(100)
);

-- ======================
-- Messages
-- ======================
CREATE TABLE messages (
    id          UUID PRIMARY KEY,
    sender_id   UUID REFERENCES users(id) ON DELETE CASCADE,
    receiver_id UUID REFERENCES users(id) ON DELETE CASCADE,
    booking_id  UUID REFERENCES bookings(id) ON DELETE SET NULL,
    content     TEXT NOT NULL,
    timestamp   TIMESTAMP DEFAULT NOW()
);

-- ======================
-- Audit Logs
-- ======================
CREATE TABLE audit_logs (
    id        UUID PRIMARY KEY,
    user_id   UUID REFERENCES users(id) ON DELETE SET NULL,
    action    VARCHAR(150),
    entity    VARCHAR(100),
    timestamp TIMESTAMP DEFAULT NOW()
);
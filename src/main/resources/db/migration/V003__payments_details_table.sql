CREATE TABLE payments_details
(
    id               UUID         NOT NULL,
    user_id          UUID         NOT NULL,
    card_number      VARCHAR(255) NOT NULL UNIQUE,
    account_number   VARCHAR(255) NOT NULL UNIQUE,
    bank_name        VARCHAR(255) NOT NULL,
    card_holder_name VARCHAR(255) NOT NULL,
    expiry_date      date         NOT NULL,
    cvv              VARCHAR(255) NOT NULL UNIQUE,
    CONSTRAINT pk_payments_details PRIMARY KEY (id)
);

ALTER TABLE payments_details
    ADD CONSTRAINT FK_PAYMENTS_DETAILS_ON_USER FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE;
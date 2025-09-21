CREATE TABLE documents
(
    id                    UUID NOT NULL,
    user_id               UUID,
    front_id_image        BYTEA,
    back_id_image         BYTEA,
    face_image_holding_id BYTEA,
    status                BOOLEAN,
    CONSTRAINT pk_documents PRIMARY KEY (id)
);

ALTER TABLE documents
    ADD CONSTRAINT uc_documents_user UNIQUE (user_id);

ALTER TABLE documents
    ADD CONSTRAINT FK_DOCUMENTS_ON_USER FOREIGN KEY (user_id) REFERENCES users (id);
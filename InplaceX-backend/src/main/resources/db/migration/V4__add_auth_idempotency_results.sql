CREATE TABLE auth_idempotency_results (
    operation VARCHAR(32) NOT NULL,
    actor_key VARCHAR(128) NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    request_fingerprint VARCHAR(64) NOT NULL,
    state VARCHAR(16) NOT NULL CHECK (state IN ('completed')),
    response_json TEXT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (operation, actor_key, idempotency_key)
);

CREATE INDEX idx_auth_idempotency_expiry ON auth_idempotency_results(expires_at);

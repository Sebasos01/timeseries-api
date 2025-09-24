-- Placeholder migration retained to avoid Flyway checksum mismatches for existing environments.
CREATE TABLE IF NOT EXISTS flyway_baseline (
    id SERIAL PRIMARY KEY,
    created_at TIMESTAMPTZ DEFAULT now()
);

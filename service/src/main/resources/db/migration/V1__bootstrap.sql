-- Placeholder migration to validate Flyway wiring.
CREATE TABLE IF NOT EXISTS flyway_baseline (
    id SERIAL PRIMARY KEY,
    created_at TIMESTAMPTZ DEFAULT now()
);

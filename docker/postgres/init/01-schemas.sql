-- One Postgres server, two schemas with separate owners.
--
-- The design doc (Key design decisions) puts Camunda's secondary storage on the
-- same server as the migration ledger to avoid running two database engines.
-- Schema separation is the boundary that makes that safe: the migration app must
-- never read or write Camunda's exported projection, and the engine must never
-- see the ledger.

-- Camunda's exported projection (secondary storage). The engine owns it.
CREATE SCHEMA IF NOT EXISTS camunda;

-- The migration ledger. The migration app owns it.
CREATE SCHEMA IF NOT EXISTS migration;

-- Engine account: camunda schema only.
CREATE USER camunda WITH PASSWORD 'camunda';
GRANT ALL PRIVILEGES ON SCHEMA camunda TO camunda;
ALTER USER camunda SET search_path TO camunda;

-- Migration app account: migration schema only. Deliberately NOT granted on the
-- camunda schema -- a bug in the migration app must not be able to write to the
-- engine's projection.
GRANT ALL PRIVILEGES ON SCHEMA migration TO migration;
ALTER USER migration SET search_path TO migration;

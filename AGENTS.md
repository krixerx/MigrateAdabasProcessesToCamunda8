# Repository Guidelines

## Project Structure & Module Organization

This proof of concept infers in-flight process state from synthetic Adabas exports and migrates cases to Camunda 8.

- `migration-app/`: Java 25 Spring Boot CLI, built with Maven. Production packages under `src/main/java/com/poc/migration/` cover extraction, classification, loading, reconciliation, and the ledger.
- `migration-app/src/main/resources/`: application configuration and SQL schema. Tests mirror production packages under `src/test/java/`.
- `processes/`: BPMN workflow and DMN classification table.
- `fixtures/`: synthetic CSV exports, freeze manifest, and independently written expected outcomes.
- `docker/` and `docker-compose.yml`: local PostgreSQL and Camunda stack.
- `docs/`: design rationale and demo instructions. Read `docs/designs/adabas-camunda8-inflight-migration-poc.md` before changing behavior.

## Build, Test, and Development Commands

Run from the repository root with Java 25, Maven, and Docker available:

- `mvn -f migration-app/pom.xml package`: compile, run tests, and build the executable JAR.
- `mvn -f migration-app/pom.xml test`: run the full test suite; engine tests start disposable containers.
- `mvn -f migration-app/pom.xml test "-Dtest=DecisionTableDomainTest"`: check deployed DMN behavior.
- `docker compose up -d`: start the local services.

Follow `docs/demo-spec.md` to deploy the models, then run:

```sh
java -jar migration-app/target/migration-app-0.1.0-SNAPSHOT.jar classify fixtures/adabas-export.csv fixtures/manifest.csv fixtures/expected-outcomes.csv
```

The CLI also supports `load` and `reconcile`; consult the demo for their sequencing.

## Coding Style & Naming Conventions

Use UTF-8, four-space Java indentation, `PascalCase` class names, `camelCase` methods and fields, and `UPPER_SNAKE_CASE` constants. Follow existing package boundaries and constructor injection. Explain domain assumptions in comments. No formatter or linter is configured.

## Testing Guidelines

Use JUnit Jupiter, AssertJ, and Camunda Process Test with Testcontainers. Name test classes `*Test` and mirror the affected package. Unit tests require no services. No numeric Java coverage threshold is configured; preserve the DMN domain test's explicit 96-combination partition and fixture expectations. Add regression cases for changed classification or ledger behavior.

## Commit & Pull Request Guidelines

This checkout has no Git metadata, so existing commit conventions cannot be established. Use concise imperative subjects, such as `Fix cutoff boundary classification`. PRs should explain behavior changes, link relevant issues or design decisions, and report validation commands and results. Include model screenshots when they clarify BPMN or DMN changes.

## Configuration & Migration Invariants

Keep fixtures synthetic and credentials local. Preserve the isolated process ID `learner-permit-migration` and configured ports. Derive cutoffs from the manifest's `freezeDate`, never the current clock. Persist ledger intent before engine calls.

# PlanPilot — Adaptive Academic Scheduler

PlanPilot is a Java 17 Maven project for the EECS3311 course project. This stage
contains only the core domain model described in the Stage 1 design report.

## Project Structure

- `src/main/java/com/planpilot/domain/` — domain entities and enums
- `src/test/java/com/planpilot/domain/` — JUnit domain tests
- `docs:stage1/` — Stage 1 design report supplied with the project

Services, scheduling algorithms, user interfaces, and AI integration are reserved
for later stages.

## Build and Test

Use JDK 17 and Maven, then run:

```sh
mvn test
```

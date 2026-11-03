# PlanPilot — Adaptive Academic Scheduler

PlanPilot is a Java 17 Maven project for the EECS3311 course project. It provides
course and task management, deterministic schedule planning, progress tracking,
CLI and JavaFX interfaces, and an extensible LLM integration layer.

## Project Structure

- `src/main/java/com/planpilot/` — domain, services, controllers, interfaces, and AI adapters
- `src/test/java/com/planpilot/` — JUnit tests mirroring the production packages
- `docs/stage1/` — Stage 1 design report supplied with the project

The project includes deterministic planning services, CLI and JavaFX interfaces,
and a Gemini client adapter. User-facing AI workflows are implemented separately
from deterministic schedule generation.

## Gemini Configuration

Copy `.env.example` to `.env`, then replace the placeholder with your local key:

```sh
cp .env.example .env
```

Set `GEMINI_API_KEY` in `.env` or in the process environment. Process environment
values take precedence. The local `.env` file is ignored by Git; never commit a
real API key. The adapter uses `gemini-3.5-flash-lite` through the Gemini REST API.

After configuring the key, run this opt-in live check to verify the complete Java
client path:

```sh
mvn -q compile exec:java \
  -Dexec.mainClass=com.planpilot.ai.GeminiConnectionCheck
```

A successful check prints `Gemini Java integration verified: PLANPILOT_OK`. This
command makes one live API request; the normal unit test suite does not.

When Gemini is configured, `PlanPilotController.requestEffortSuggestion(...)`
combines completed similar-task history with an AI recommendation. It returns a
suggestion only and never changes the task's user-selected estimate automatically.

`PlanPilotController.parseNaturalLanguageTask(...)` converts free-form text into
validated `ParsedTaskData`. The result is only a proposal: the caller must show it
for review and explicitly create a task after the user confirms or edits it.

`PlanPilotController.explainSchedule(...)` sends Gemini a read-only JSON snapshot
of the current schedule, task priority factors, and availability. The explanation
cannot modify or regenerate the deterministic schedule.

## Build and Test

Use JDK 17 and Maven, then run:

```sh
mvn test
```

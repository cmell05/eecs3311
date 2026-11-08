# PlanPilot — Adaptive Academic Scheduler

PlanPilot is a Java 17 Maven project for the EECS3311 course project. It provides
course and task management, deterministic schedule planning, progress tracking,
CLI and JavaFX interfaces, and an extensible LLM integration layer.

## Requirements and first run

- **JDK 17** (including `javac`), with `JAVA_HOME` and `PATH` pointing to it.
- **Apache Maven 3.9 or later**, available as `mvn` on your terminal's `PATH`.
- A graphical desktop for the JavaFX GUI. The CLI can run without a desktop.
- Internet access for Maven's first dependency download. AI actions additionally
  need internet access and a local Gemini API key; deterministic features work
  without a key.

Check the installed tools:

```sh
java -version
javac -version
mvn -version
```

Clone the repository and run commands from its root (the folder containing
`pom.xml`):

```sh
git clone https://github.com/cmell05/eecs3311.git planpilot
cd planpilot
mvn clean test
```

Maven downloads JavaFX, Jackson, dotenv-java, JUnit, and build plugins using the
versions in `pom.xml`. No separate JavaFX SDK or database installation is needed.
Tests use fake LLM clients/transports and never call the live Gemini API.

Launch either interface:

```sh
# Graphical interface
mvn javafx:run

# Command-line interface
mvn -q compile exec:java -Dexec.mainClass=com.planpilot.cli.PlanPilotCLI
```

A first launch starts with an empty workspace. Configure Gemini below to enable
AI task entry, effort estimation, schedule explanations, and AI advice.

## Implemented features

| Stage 1 ID | Feature | Deterministic / AI behavior |
|---|---|---|
| F01 | Course management | Add, list, edit, and delete courses; unique codes and linked-task deletion guards. |
| F02 | Task management | Academic and General tasks, validation, editing/deletion, and status groups. |
| F03 | Natural-language task entry | Gemini proposes task fields; review and explicit save are required. |
| F04 | Availability and commitments | Manage available intervals and fixed commitments; reject invalid/contradictory intervals. |
| F05 | Task prioritization | Strategy calculates scores from urgency, effort, difficulty, importance, progress, and academic weight. |
| F06 | Study schedule generation | Deterministic allocation, session splitting, preferences, breaks, deadline bounds, and conflict checks. |
| F07 | Conflict and workload recovery | Deterministic deficit detection and user-applied recovery; optional Gemini advice. |
| F08 | Progress tracking | Confirm, partially complete, skip, infer, and correct sessions without double-counting. |
| F09 | Assisted replanning | Outdated-state warnings and explicit replanning; optional AI recommendation. |
| F10 | Adaptive effort estimation | Similar completed-task history plus Gemini; Tasks-page review before applying/saving. |
| F11 | Schedule decision explanation | Gemini explains captured scheduling evidence and compares current priorities without changing the plan. |

Local workspace persistence supports both interfaces. Courses, tasks, sessions,
availability, preferences, and history are restored after restarting.

## Project Structure

- `src/main/java/com/planpilot/` — domain, services, controllers, interfaces, and AI adapters
- `src/test/java/com/planpilot/` — JUnit tests mirroring the production packages
- `src/main/resources/com/planpilot/gui/planpilot.css` — JavaFX styling
- `pom.xml` — Java 17 compiler settings, dependencies, and launch/test plugins
- `.env.example` — safe local AI configuration template
- `docs/stage1/` — Stage 1 design report supplied with the project
- [Workflow verification guide](docs/testing/workflow-verification.md) — manual acceptance checks

The project includes deterministic planning services, CLI and JavaFX interfaces,
and a Gemini client adapter. User-facing AI workflows are implemented separately
from deterministic schedule generation.

## Gemini Configuration

Copy `.env.example` to `.env`, then replace the placeholder with your local key:

```sh
cp -n .env.example .env
```

If `.env` already exists, edit it rather than replacing it. Set `GEMINI_API_KEY`
in `.env` or in the process environment. Process environment values take precedence. The application explicitly loads `.env` through
dotenv-java from the working directory. The local `.env` file is ignored by Git;
never commit a real API key or expose it in recordings. The adapter uses
`gemini-3.5-flash-lite` through the Gemini REST API.

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

For Stage 1 **F11 — Schedule Decision Explanation**, new schedules also retain
immutable generation evidence: the exact priority scores and six factor
contributions, original task inputs, allocation ranks and tie-break rules,
preferences, available/fixed intervals, usable-slot order, session duration
constraints, and reasons for unscheduled work. The planner calculates each score
once at the generation time and uses that same score to order tasks. MEDIUM
importance alone does not mean equal calculated priority. Allocation order can
differ from chronological session order when preferred periods and deadlines
require fallback slots.

The evidence is persisted with the schedule and is supplied to explanation,
recovery, and replan advice. Subsequent edits retain the original evidence and
mark the plan outdated; Gemini is instructed to distinguish original decisions
from current task fields. Older plans without evidence still load, but their
original rationale cannot be reconstructed reliably. Explicitly Generate or Replan
once to capture evidence; asking for an explanation never regenerates a plan.

Even older plans receive a fresh, read-only comparison of current tasks: deadline
proximity, remaining work, difficulty, importance, progress, and assessment weight.
This supports “what should I focus on now?” without pretending to reconstruct the
original placement. Gemini is instructed to answer in ordinary student-facing
language and distinguish current advice from historical reasons. Responses are
checked for exposed internal fields or raw data; one rewrite with the same facts
is allowed before showing a clear error rather than a technical draft. No advice
request changes task data, saved schedules, deadlines, or progress.

After deterministic generation reports a `ScheduleInfeasibleException`,
`requestRecoverySuggestions(...)` can produce a validated `RecoveryPlan`. Gemini
may explain options such as extending the window or adding availability, but the
user must choose and apply any change explicitly.

For an existing plan, `requestReplanRecommendation()` summarizes the changed
workload and recommends one validated `ReplanOption`. The recommendation is
advisory; only a separate user-approved `requestReplan(...)` changes the schedule.

## Run the CLI

Launch the menu-driven CLI with:

```sh
mvn -q compile exec:java \
  -Dexec.mainClass=com.planpilot.cli.PlanPilotCLI
```

AI menu actions use the same controller facade as deterministic actions. Parsed
tasks, effort changes, recovery actions, and replanning remain subject to explicit
user confirmation. Course, task, and availability identifiers are generated by
the application. When choosing a course, task, or study block, select its
numbered list entry; the labels show recognizable names, so users do not need to
enter internal IDs.

Use numbered choices rather than internal IDs. Dates use `YYYY-MM-DD`; date/time
uses `YYYY-MM-DDTHH:MM`, using 24-hour time. Always choose future dates for new study sessions. Invalid fields are
retried. Type `/cancel` at a prompt to return to the main menu; ending input exits
cleanly. Blank answers keep the displayed default when editing.

A typical workflow is **2** add course (academic work only), **4** add tasks,
**5** add Available time or fixed commitments, **6** generate a plan, **7** inspect
sessions, and **8** record/confirm/correct progress. **9** shows task totals and
saved session history, distinguishing inferred hours from confirmed completion.
Saved sessions can be corrected even before generating a new plan after restart.

Use **16** to explicitly replan remaining work in the current window, or **6**
to choose a different window. **17** edits maximum session length and breaks in
minutes and the preferred study period. Changes never regenerate a plan
without a user action. **14** manages existing entries and lists availability.
After an infeasible plan, choose a recovery action or keep the partial plan;
AI recovery advice is optional (recovery choice **4**) and is never requested
automatically. AI menu items **10–13** need local Gemini configuration; normal
planning and progress work without it. Courses, tasks, availability, preferences,
the active plan, and progress history are saved locally and restored after restart.

## Run the GUI

Launch the JavaFX application with:

```sh
mvn javafx:run
```

The Tasks page can optionally use Gemini to fill the task form from a short
description; review the fields and select **Add Task** to save it. The **AI Tools**
page directly displays schedule explanations, workload recovery advice,
and user-approved replanning recommendations without a collapsed tools panel.
Gemini requests run in the background so the JavaFX interface remains responsive.
Course, task, and availability IDs are generated automatically and kept out of the forms. Course
and task selectors display recognizable names instead of internal identifiers.

## Example workflow

For a reproducible manual example, let **D** be tomorrow and **E** the following
day. Replace these labels with actual dates in the form or CLI; they are not
literal accepted inputs.

1. Add course `EECS3311` with name `Software Design`.
2. Add an Academic task `Design report`: deadline **E at 17:00**, estimated hours
   **2**, difficulty **3**, importance **MEDIUM**, assessment weight **20** (20%),
   type **ASSIGNMENT**, linked to EECS3311. A General task omits academic fields.
3. Add **Available time** on **D, 09:00–13:00**. A fixed commitment must not
   overlap that available entry; split available time around commitments.
4. Generate a schedule for **D through E**. Inspect two hours of study sessions
   placed within the supplied availability before the deadline, with breaks
   between sessions according to preferences.
5. In Progress, select a one-hour session and record **Partial: 0.5**. Expect
   **0.5 confirmed hours** and **1.5 task hours remaining**. The session moves to
   History, and the schedule becomes outdated.
6. Use **Replan remaining work** explicitly. Reported history stays available
   for corrections; new sessions cover the remaining work if it fits.
7. Close and reopen the same workspace to verify persistence.

With Gemini configured, try **Fill form with AI** on Tasks, or ask AI Tools:
“Which task should I focus on now, considering deadlines and remaining work?”
Review every proposal before saving or applying it.

## AI Tools controls

The question box is used only by **Explain Schedule**. Other buttons do not
need a typed prompt.

| Control | How to use it |
|---|---|
| Explain Schedule | Generate a schedule, type a question, then click. Restored, partial, and outdated schedules can be explained; older plans may lack their original recorded rationale. |
| Suggest Workload Recovery | After an infeasible plan, click for optional Gemini advice. Review any proposed action before applying. |
| Review Recovery Actions | After an infeasible plan, click to choose recovery manually. This works without Gemini or while an AI request is pending. |
| Recommend Replan Option | With an existing plan, click for an advisory recommendation. It does not regenerate the plan. |
| Apply Recommended Replan | Review a valid recommendation, then explicitly apply it. Planning changes invalidate older recommendations. |

Explain becomes clickable when a schedule exists; missing questions or AI
configuration are reported on click. Other AI actions also require configured
Gemini and an idle AI worker. Manual recovery does not require AI. A timeout or
unusable AI response preserves task data and leaves manual recovery available.

## Adaptive effort estimation on Tasks (F10)

While creating or editing a task, use **Estimate Effort with AI** beside
**Estimated hours**. New tasks need valid required form fields, including a
positive manual estimate; the temporary task used for the request is not saved.
For a selected saved task, estimates use that task's saved details and history.
Save edits first if you want them reflected in the request.

Review the displayed suggestion, then choose **Use Suggested Estimate** to fill
the hours field or **Keep Current Estimate** to discard it. Applying a suggestion
only edits the form: **Add Task** or **Update** is still required to save. Changes
to the form or selected task invalidate old suggestions and late responses.

Without matching confirmed history, the existing general AI fallback remains
available and is labelled non-personalized. The manual estimate stays unchanged
until explicitly applied. Network failures retain both the form and saved
estimate. Effort estimation is no longer duplicated in AI Tools; the CLI effort
command remains available.

## Build and Test

Use JDK 17 and Maven, then run:

```sh
mvn clean test
```

This compiles the Java sources, copies resources into `target/`, and runs JUnit.
The Maven GUI/CLI launch commands use the compiled classes and resolved
dependencies. No prebuilt JAR is required to run the source submission.

## Progress tracking and local history

Tasks are grouped into **In progress**, **Not started**, **Completed**, and
**Skipped** sections with counts. Expand a section and select a task to edit it.

Progress opens on **To confirm**, with sessions grouped by task. Confirmed
Completed, Partially Completed, and Skipped sessions move to **History**;
remaining task effort is preserved. Tentative inferred outcomes stay in
**To confirm** until confirmed or corrected. Open **History**, expand a task,
and select a session to correct its result. The correction ledger shown in the
editor belongs to that selected session. Task bars and estimates are available
under the expandable **Task progress** summary above the session tabs.

The Progress page shows planned hours, confirmed completion, remaining effort,
completed/incomplete tasks, and elapsed sessions awaiting confirmation. Task bars
show confirmed hours. Remaining effort also includes tentative inferred hours,
which are labelled separately and can be corrected at any time.

The GUI checks elapsed, unreported sessions on startup and every 30 seconds; the
CLI checks between menu actions. No language model is involved. Confirm Completed,
enter actual hours from zero through the session duration, or mark Skipped. A
correction replaces that session's contribution rather than adding it again.
Workload changes mark the current schedule outdated; replanning requires a user action.
Reported sessions and their correction history remain available after regeneration.

Local progress is saved atomically to `.planpilot/progress.json`, which is ignored
by Git. It includes task/course snapshots, original estimates, tracked sessions,
and the inferred/confirmed correction ledger, and is restored on the next launch.
Deleted tasks remain archived rather than reappearing in the active task list.
The same file also saves standalone courses, all availability/commitment entries,
scheduling preferences, and the active schedule with its planning window, session
IDs, CURRENT/OUTDATED state, and any infeasibility warning. Both GUI and CLI
restore that workspace without generating a new plan. Each successful application
change is saved automatically; no separate Save command is needed.

Existing version 1 progress files load without being rewritten. Their task,
course, session, and correction history is retained when the next change upgrades
the file to version 2. Missing or malformed data produces an error instead of
silently clearing the file. `-Dplanpilot.progress.file=/path/to/workspace.json`
selects a different local workspace.

Skipping an unreported session or recording zero actual hours also marks the
active schedule outdated because the session outcome changed, even when total
completed effort stays zero. Repeating an identical result does not add effort
or invalidate a freshly replanned schedule again.

Schedule and Progress show numbered session cards with task names, dates, times,
durations, and outcomes. A task keeps the same colour across both pages. Schedule
shows upcoming sessions first; previously reported sessions are in a collapsed
history section. Replanning applies the selected dates and can retain identical
times when the workload and availability have not changed.

New study sessions are scheduled only at or after the current local time. When
seconds have elapsed within a minute, scheduling starts no earlier than the next
whole minute. Expired availability is excluded from workload capacity, and past
deadlines or expired planning windows produce an explanation. Historical reported
sessions remain preserved for progress corrections; they are not newly scheduled
work. Tests inject fixed clocks to verify these rules without relying on today's date.

## Recovery and task deferral (F07)

After an infeasible plan, choose **Resolve unscheduled work** on Schedule. Review
one of these actions and select **Apply change and replan**:

- **Add available time:** supply the date and start/end times.
- **Extend the planning window:** choose a later end date. Availability and task
  deadlines are not automatically extended.
- **Leave a selected task out of this plan:** select a lower-priority task. Its
  deadline, progress, and remaining effort stay intact. It is deferred until the
  day after the current window ends, stays visible in Tasks, and is saved locally.
- **Keep the current partial plan:** accept the current constraints without regeneration.

AI Tools offers **Review Recovery Actions** to open the same dialog directly,
without an AI request. **Suggest Workload Recovery** optionally requests Gemini
advice and opens the dialog after a usable response. Advice alone never applies
a change. If an accepted change still cannot fit all work,
the revised partial plan and remaining deficit are shown. Changed planning data
requires a fresh generation/replan before an older recovery choice can be applied.
Select a deferred task and use **Include deferred task again** to resume it, then
replan explicitly. Deferral never changes a deadline; the task can become overdue.

The CLI offers recovery immediately after an infeasible plan, and menu **15**
reopens recovery. Menu **14** provides course/task/availability edit and deletion,
availability listing, and resuming deferred tasks. GUI deletion asks for confirmation.
Course deletion is blocked while any active academic task references it, including
completed or deferred tasks. Reassign or delete those tasks first. Task deletion
keeps reported progress history archived. Task edits preserve recorded progress;
an estimate below completed effort is rejected.


## Using a separate demo workspace

The default save file is `.planpilot/progress.json`. To keep demo data separate,
choose a different path. Use the same path to share the data between interfaces,
and close one instance before opening the other against that file.

```sh
# GUI on macOS/Linux; JVM options are inherited by the JavaFX launcher
JAVA_TOOL_OPTIONS='-Dplanpilot.progress.file=.planpilot/demo.json' mvn javafx:run

# CLI using the same workspace
mvn -q compile exec:java \
  -Dexec.mainClass=com.planpilot.cli.PlanPilotCLI \
  -Dplanpilot.progress.file=.planpilot/demo.json
```

For the GUI on PowerShell, set `$env:JAVA_TOOL_OPTIONS` to
`-Dplanpilot.progress.file=.planpilot/demo.json` before running `mvn javafx:run`;
remove that environment override afterward if you want to use the default file.
Actual workspace files are local data, not required repository files. No initial
save file, login, or database is needed to run a fresh installation.

## Architecture and design patterns

Both interfaces use `PlanPilotController` rather than calling services directly.
The application factory wires services, persistence, observers, strategy, and the
optional Gemini adapter. The AI workflows retrieve relevant task/history data,
calculate deterministic summaries, validate structured model proposals, and route
explicitly accepted recovery or replanning choices through application services.
The resulting schedule is validated again before display.

| Pattern | Implemented roles |
|---|---|
| MVC | GUI/CLI views, `PlanPilotController`, and domain/service models separate interaction from application behavior. |
| Facade | `PlanPilotController` coordinates course, task, availability, schedule, progress, and AI operations. |
| Strategy | `PriorityStrategy` and `BalancedPriorityStrategy` supply interchangeable priority evaluation to `SchedulePlanner`. |
| Adapter | `GeminiLLMClient` implements provider-independent `LLMClient`; `PlanningAgent` and task parsing use that abstraction. |
| Observer | `PlanningEventPublisher` notifies `PlanningObserver`; `PlanPilotGUI` refreshes views and outdated-state guidance. |

The JSON journal stores local workspace snapshots atomically. AI receives relevant
application context and returns proposals or explanations; deterministic services
validate and apply user-approved operations.

## Troubleshooting and limitations

| Problem | Check |
|---|---|
| `mvn: command not found` | Install Maven and add its `bin` directory to `PATH`; check `mvn -version`. |
| Java release/compiler error | `mvn -version` must use JDK 17. A Java runtime alone is insufficient; check `javac -version` and `JAVA_HOME`. |
| First build cannot download dependencies | Maven needs access to its configured artifact repository. Offline mode works only after dependencies are cached. |
| JavaFX cannot initialize a screen | Run the GUI in a graphical desktop session. Use the CLI for a headless environment. |
| AI is unavailable | Check `GEMINI_API_KEY` locally, launch from the repository root, restart after configuration, and use the opt-in connection check. Do not print the key. |
| Gemini is temporarily unavailable or times out | Retry later. Transient HTTP failures have bounded retries. Manual recovery and deterministic scheduling remain available. |
| No work fits | Check future dates, task deadlines, planning window, AVAILABLE intervals, deferrals, session lengths, and breaks. Read the reported deficit before choosing recovery. |
| Recovery is unavailable | Generate/replan an infeasible schedule with the current data first. Changed constraints invalidate previous recovery advice. |
| Save file cannot be restored | The file is kept unchanged. Preserve it for investigation or use a separate workspace path rather than clearing personal data. |

- Storage is local to one workspace file; it is intended for one running instance
  per file, with no account system or multiuser synchronization.
- The priority-first greedy planner does not search every alternative arrangement.
  Deficit warnings report work the planner could not allocate under the supplied
  constraints; users must explicitly choose recovery or replanning.
- Inferred completion is tentative. Correct it when planned work did not happen.
  Reported hours are bounded by the session duration and task estimate.
- AI output and service availability vary. Task proposals require review; general
  estimates without matching history are labelled non-personalized.
- Original placement explanations require generation evidence. Older saved plans
  can receive current priority comparisons, with explicit regeneration needed to
  capture exact new placement reasons.

## Project documentation and demonstrations

The [Stage 1 design report](docs/stage1/PlanPilot%20-%20Stage%201%20Design%20Report.pdf)
and [workflow verification guide](docs/testing/workflow-verification.md) are
included. The Stage 2 implementation report and its feature-by-feature GUI/CLI
video links are maintained separately by the project author and should be added
for the final submission. This README does not claim those artifacts are already
present. Reviewers can run the application and create the example data above
without access to the author's personal workspace or credentials.

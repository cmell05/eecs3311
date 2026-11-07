# Workflow Verification

Verification date: November 5, 2026. No production data or API keys belong in this document.

## Completed checks

- Real CLI launcher: academic/general task creation, availability, schedule generation, partial progress, and explicit replanning passed using isolated temporary journals.
- Real CLI edge cases: duplicate course code, invalid effort/difficulty/weight, reversed intervals, cancellation, linked-course deletion protection, and confirmed deletion passed.
- Real CLI recovery: a two-hour task with one hour available reported a one-hour deficit. Adding time and explicitly confirming recovery produced a revised plan.
- Automated controller checks cover the shared GUI/CLI application path, including fixed commitments, preference fallback, corrections, inference, preserved history, and read-only AI advice.
- AI context regression tests verify the latest session outcome, actual partial hours, confirmed baselines, inferred totals, elapsed unreported sessions, and only the remaining time of upcoming/ongoing sessions. Tests use fake LLM transport and never call Gemini.

## Checks requiring a local desktop/network

JavaFX launch was attempted with a writable native-library cache. This environment exposes no desktop screen, so rendering fails in `Screen.getMainScreen`. Button interactions and visual layout remain unverified here.

The separate live Gemini connection check was attempted but failed with `UnresolvedAddressException`. This is a local network/DNS limitation; no HTTP authentication response was received.

Run locally from the repository root:

```sh
mvn javafx:run
mvn -q compile exec:java -Dexec.mainClass=com.planpilot.ai.GeminiConnectionCheck
```

## Desktop acceptance flow

Use future dates if November 6–7 have already passed. For an isolated journal, prefix the GUI command with:

```sh
JAVA_TOOL_OPTIONS='-Dplanpilot.progress.file=.planpilot/gui-acceptance.json' mvn javafx:run
```

1. Add EECS3311. Attempt the same code with different capitalization; it must be rejected. Dismiss the error; the main window must remain open.
2. Add a two-hour Academic task due November 7, linked to EECS3311. Enter assessment weight `20` for 20%. Switch to General: academic fields must disappear. Add a one-hour General task.
3. Add Available time November 6, 09:00–13:00, and a WORK commitment 13:00–14:00. Generate for November 6. Expect three planned hours, with no overlaps or work after deadlines.
4. In Progress, select a one-hour session and record PARTIAL `0.5`. Confirm task totals change and the schedule becomes outdated without regenerating.
5. Correct the same session to COMPLETED twice, then SKIPPED. Its contribution must be one hour, one hour, then zero. Invalid partial hours must be rejected.
6. Explicitly replan. Reported sessions must remain historical; new allocations must cover remaining effort.
7. On Tasks, request an effort estimate without applying it; the hours and saved task must stay unchanged. In AI Tools, ask about confirmed versus inferred progress. Request a replan recommendation; declining must preserve the current plan.
8. Add more workload than fits. Review recovery advice, cancel an action, then explicitly add time and apply recovery. Verify cancellation changes nothing and accepted recovery shows the new plan or an honest remaining deficit.
9. Attempt to delete a linked course; it must be blocked. Delete/reassign its tasks before retrying.
10. For inference, schedule a short session near the current time, wait until its end plus 30 seconds, and check that it is labelled tentative. Correct it to SKIPPED; its inferred contribution must disappear.

## Restart acceptance check (added November 7)

Add a course with no tasks, an academic/general task, available time and a fixed
commitment. Change preferences, generate a schedule, and mark an unreported
session Skipped or Partial with zero hours. Confirm OUTDATED appears without
regeneration. Close and relaunch using the same workspace path. Courses, task
relationships, availability, preferences, planning dates, session identities,
progress/history, and the outdated warning must return. Replan explicitly;
reported history must remain correctable. Infeasible plans must retain their
warnings and recovery options across restart. Existing version 1 progress files
are upgraded on the next successful change without discarding history.


## F11 scheduling-decision evidence

Generate a fresh schedule before checking this change. Ask why two tasks with
MEDIUM importance were ordered differently, and which one to focus on. The
explanation should distinguish importance from the recorded calculated score and
compare the actual factor contributions. An earlier session alone must not be
claimed as proof of higher calculated priority.

Edit a task's deadline or effort without replanning and ask again. The explanation
must distinguish the generation-time inputs/scores from current data and the
outdated plan. Restart and repeat: the recorded evidence must remain identical.
Older plans without evidence must report that limitation rather than invent an
original calculation. Asking questions must not change tasks, schedule IDs,
deadlines, or progress. Fake-client regression tests verify these supplied facts;
actual Gemini wording still needs a local application check.


## Readable explanations and older schedules

Restart the application after installing these changes. A plan saved before
recorded decision evidence was added can still be used to ask which task is due
sooner and what to focus on now. The response should compare the supplied current
urgency and workload, without naming internal fields, flags, classes, or raw data.
It must distinguish current advice from an original placement it cannot verify.
Generate or replan explicitly once for an exact explanation of new placements.

Regression tests cover the reported Chemistry/EECS chronology, current urgency
comparisons when both importance levels are MEDIUM, changed workload without
regeneration, one bounded rewrite of a technical draft, rejection of repeated
technical drafts, and preservation of legitimate technical task names. Tests
verify inputs and presentation handling with fake clients; they do not certify
live Gemini wording or modify personal workspace data.


## F10 Tasks form acceptance flow

1. Start a new task, fill its required fields and manual estimated hours, then
   select **Estimate Effort with AI**. Review the suggestion; no task should exist
   until **Add Task** is selected.
2. Choose **Keep Current Estimate**: the manual hours must stay unchanged. Request
   again and choose **Use Suggested Estimate**: only the field should change.
   Save explicitly to create the task.
3. Select an existing task, request an estimate, and apply it. Re-selecting or
   clearing the form before Update must show that the saved estimate was unchanged.
   Saved-task estimates use saved details; save edits first to include them.
4. With no matching confirmed completed history, check that the general suggestion
   is labelled non-personalized and never overwrites the manual field by itself.
5. With a completed similar task, check that the estimate is labelled as based on
   history. Inferred-only completion must not qualify as confirmed history.
6. Change fields or switch tasks while a request is running; its old response must
   not become applicable. Invalid new-task fields must be rejected before a request.
7. With Gemini unavailable, confirm a readable error and unchanged manual/saved
   hours. Check AI Tools directly shows schedule advice, recovery, and replan
   controls with no expansion toggle and no duplicate effort panel.

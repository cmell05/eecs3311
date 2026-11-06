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
7. In AI Tools, request an effort suggestion without applying it. Ask about confirmed versus inferred progress. Request a replan recommendation; declining must preserve the current plan.
8. Add more workload than fits. Review recovery advice, cancel an action, then explicitly add time and apply recovery. Verify cancellation changes nothing and accepted recovery shows the new plan or an honest remaining deficit.
9. Attempt to delete a linked course; it must be blocked. Delete/reassign its tasks before retrying.
10. For inference, schedule a short session near the current time, wait until its end plus 30 seconds, and check that it is labelled tentative. Correct it to SKIPPED; its inferred contribution must disappear.

Availability, preferences, and the active planning window are still not persisted across restarts. Persistence expansion is outside this verification change.

package com.planpilot.gui;

import com.planpilot.domain.*;
import com.planpilot.event.PlanningEventPublisher;
import com.planpilot.service.ProgressService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.*;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class PlanningViewGroupsTest {
    private final LocalDate day = LocalDate.of(2026, 11, 6);
    private final Clock clock = Clock.fixed(day.atTime(12, 0).toInstant(ZoneOffset.UTC), ZoneOffset.UTC);
    @TempDir Path directory;

    private Task task(String id, String title, double hours) {
        return new GeneralTask(id, title, day.plusDays(1).atTime(17, 0), hours, 3, PriorityLevel.MEDIUM);
    }

    private StudyBlock session(String id, Task task, int hour) {
        return new StudyBlock(id, task, day.atTime(hour, 0), day.atTime(hour + 1, 0));
    }

    @Test void tasksAreSeparatedByTheirActualStatusAndOrderedByDeadline() {
        Task fresh = task("new", "New", 2);
        Task underway = task("working", "Working", 2); underway.setCompletedHours(.5);
        Task completed = task("done", "Done", 1); completed.setCompletedHours(1);
        Task skipped = task("skip", "Skipped", 1); skipped.markSkipped();
        Task urgent = task("urgent", "Urgent", 2); urgent.setDeadline(day.atTime(17, 0));
        var groups = PlanningViewGroups.tasksByStatus(List.of(completed, fresh, skipped, underway, urgent));
        assertEquals(List.of(underway), groups.get(TaskStatus.IN_PROGRESS));
        assertEquals(List.of(urgent, fresh), groups.get(TaskStatus.NOT_STARTED));
        assertEquals(List.of(completed), groups.get(TaskStatus.COMPLETED));
        assertEquals(List.of(skipped), groups.get(TaskStatus.SKIPPED));
        assertEquals(5, groups.values().stream().mapToInt(List::size).sum());
    }

    @Test void confirmedCompletedPartialAndSkippedSessionsLeaveTheMainView() {
        Task task = task("T", "Design", 4);
        var planned = session("planned", task, 14);
        var complete = session("complete", task, 7); complete.markCompleted();
        var partial = session("partial", task, 8); partial.markPartial();
        var skipped = session("skip", task, 9); skipped.markSkipped();
        List<StudyBlock> sessions = List.of(planned, skipped, partial, complete);
        assertEquals(List.of(planned), PlanningViewGroups.sessionsForView(sessions, false));
        assertEquals(List.of(complete, partial, skipped), PlanningViewGroups.sessionsForView(sessions, true));
        assertEquals(4, sessions.size(), "Grouping must not remove saved sessions");
    }

    @Test void tentativeCompletedAndPartialSessionsRemainAwaitingConfirmation() {
        Task task = task("T", "Design", 4);
        var complete = session("complete", task, 7); complete.markCompleted(); complete.markInferred();
        var partial = session("partial", task, 8); partial.markPartial(); partial.markInferred();
        List<StudyBlock> sessions = List.of(partial, complete);
        assertEquals(List.of(complete, partial), PlanningViewGroups.sessionsForView(sessions, false));
        assertTrue(PlanningViewGroups.sessionsForView(sessions, true).isEmpty());
        complete.markCompleted();
        assertEquals(List.of(partial), PlanningViewGroups.sessionsForView(sessions, false));
        assertEquals(List.of(complete), PlanningViewGroups.sessionsForView(sessions, true));
    }

    @Test void taskGroupingUsesIdentityEvenWhenTitlesMatchOrObjectsAreEdited() {
        Task general = task("general", "Report", 4);
        Task edited = task("general", "Renamed report", 4);
        Task academic = new AcademicTask("academic", "Report", general.getDeadline(), 4, 3, PriorityLevel.MEDIUM,
                new Course("EECS3311", "Design"), 20, AcademicTaskType.ASSIGNMENT);
        var late = session("late", edited, 15);
        var early = session("early", general, 9);
        var other = session("other", academic, 10);
        var groups = PlanningViewGroups.sessionsByTask(List.of(late, other, early));
        assertEquals(2, groups.size());
        assertEquals(List.of(early, late), groups.get("general"));
        assertEquals(List.of(other), groups.get("academic"));
    }

    @Test void correctingHistoryChangesEffortWithoutReturningReportedSessionsToPending() {
        var progress = new ProgressService(new PlanningEventPublisher(), clock, null);
        Task task = task("T", "Design", 2);
        var first = session("first", task, 9);
        var second = session("second", task, 10);
        progress.trackSessions(List.of(first, second));
        progress.recordProgress(first, ProgressStatus.COMPLETED, 1);
        assertEquals(TaskStatus.IN_PROGRESS, task.getStatus());
        assertEquals(List.of(second), PlanningViewGroups.sessionsForView(progress.getSessions(), false));
        progress.recordProgress(first, ProgressStatus.PARTIAL, .25);
        assertEquals(.25, progress.getConfirmedHours(task));
        assertEquals(1.75, task.getRemainingHours());
        progress.recordProgress(first, ProgressStatus.SKIPPED, 0);
        assertEquals(2, task.getRemainingHours());
        assertEquals(TaskStatus.NOT_STARTED, task.getStatus(), "Skipping a session does not skip the entire task");
        assertEquals(List.of(first), PlanningViewGroups.sessionsForView(progress.getSessions(), true));
        progress.recordProgress(first, ProgressStatus.COMPLETED, 1);
        progress.recordProgress(first, ProgressStatus.COMPLETED, 1);
        assertEquals(1, progress.getConfirmedHours(task));
        progress.recordProgress(second, ProgressStatus.COMPLETED, 1);
        assertTrue(PlanningViewGroups.sessionsForView(progress.getSessions(), false).isEmpty());
        assertEquals(List.of(task), PlanningViewGroups.tasksByStatus(List.of(task)).get(TaskStatus.COMPLETED));
    }

    @Test void historyRemainsCorrectableAfterRestartAndGroupingDoesNotChangeTheLedger() {
        Path journal = directory.resolve("progress.json");
        var progress = new ProgressService(new PlanningEventPublisher(), clock, journal);
        Task task = task("T", "Design", 2);
        var block = session("first", task, 9);
        progress.recordProgress(block, ProgressStatus.PARTIAL, .5);
        var restored = new ProgressService(new PlanningEventPublisher(), clock, journal);
        var history = PlanningViewGroups.sessionsForView(restored.getSessions(), true);
        assertEquals(1, history.size());
        restored.recordProgress(history.get(0), ProgressStatus.SKIPPED, 0);
        assertEquals(2, history.get(0).getTask().getRemainingHours());
        assertTrue(PlanningViewGroups.sessionsForView(restored.getSessions(), false).isEmpty());
        assertEquals(2, restored.getHistory().size());
        var groups = PlanningViewGroups.sessionsByTask(history);
        assertEquals(1, groups.get("T").size());
        assertEquals(2, restored.getHistory().size());
    }

    @Test void emptyViewsHaveNoSessionsAndAlwaysExposeAllTaskStatusGroups() {
        assertEquals(4, PlanningViewGroups.tasksByStatus(List.of()).size());
        assertTrue(PlanningViewGroups.sessionsForView(List.of(), false).isEmpty());
        assertTrue(PlanningViewGroups.sessionsForView(List.of(), true).isEmpty());
        assertTrue(PlanningViewGroups.sessionsByTask(List.of()).isEmpty());
    }

    @Test void eachSessionAppearsInExactlyOneView() {
        Task task = task("T", "Design", 4);
        var pending = session("pending", task, 14);
        var inferred = session("inferred", task, 7); inferred.markCompleted(); inferred.markInferred();
        var skipped = session("skip", task, 8); skipped.markSkipped();
        List<StudyBlock> sessions = List.of(pending, inferred, skipped);
        List<StudyBlock> union = Stream.concat(PlanningViewGroups.sessionsForView(sessions, false).stream(),
                PlanningViewGroups.sessionsForView(sessions, true).stream()).toList();
        assertEquals(sessions.size(), union.size());
        assertEquals(sessions.size(), union.stream().map(StudyBlock::getId).distinct().count());
    }
}

package com.planpilot.controller;

import com.planpilot.ai.PlanningAgent;
import com.planpilot.ai.ParsedTaskData;
import com.planpilot.ai.RecoveryAction;
import com.planpilot.ai.RecoveryPlan;
import com.planpilot.ai.ReplanRecommendation;
import com.planpilot.domain.AvailabilityBlock;
import com.planpilot.domain.AvailabilityType;
import com.planpilot.domain.AcademicTask;
import com.planpilot.domain.AcademicTaskType;
import com.planpilot.domain.Course;
import com.planpilot.domain.GeneralTask;
import com.planpilot.domain.PriorityLevel;
import com.planpilot.domain.ProgressStatus;
import com.planpilot.domain.Schedule;
import com.planpilot.domain.ScheduleStatus;
import com.planpilot.domain.StudyBlock;
import com.planpilot.domain.StudyPeriod;
import com.planpilot.domain.Task;
import com.planpilot.domain.UserPreferences;
import com.planpilot.service.AvailabilityService;
import com.planpilot.service.ConflictDetector;
import com.planpilot.service.CourseService;
import com.planpilot.service.EffortEstimator;
import com.planpilot.service.ProgressService;
import com.planpilot.service.ReplanOption;
import com.planpilot.service.SchedulePlanner;
import com.planpilot.service.ScheduleService;
import com.planpilot.service.ScheduleInfeasibleException;
import com.planpilot.service.TaskService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PlanPilotControllerTest {
    private PlanPilotController controller;
    private LocalDate date;
    private LocalDateTime start;

    @BeforeEach
    void setUp() {
        date = LocalDate.of(2026, 10, 28);
        start = date.atTime(9, 0);

        CourseService courseService = new CourseService();
        TaskService taskService = new TaskService(courseService);
        AvailabilityService availabilityService = new AvailabilityService();
        ProgressService progressService = new ProgressService();
        SchedulePlanner schedulePlanner = new SchedulePlanner(
                task -> 1.0, new ConflictDetector(), java.time.Clock.fixed(java.time.Instant.parse("2026-10-01T00:00:00Z"), java.time.ZoneOffset.UTC));
        ScheduleService scheduleService = new ScheduleService(
                taskService, availabilityService, schedulePlanner,
                new UserPreferences(60, StudyPeriod.ANY, 0));

        controller = new PlanPilotController(
                courseService, taskService, availabilityService,
                scheduleService, progressService);
    }

    @Test
    void delegatesCourseAndTaskManagement() {
        Course course = new Course("C1", "EECS3311", "Software Design");
        Task task = task("T1", 1);

        assertSame(course, controller.addCourse(course));
        assertSame(task, controller.addTask(task));
        assertEquals(List.of(course), controller.getCourses());
        assertEquals(List.of(task), controller.getOpenTasks());
        assertEquals(List.of(task), controller.getTasks());
    }

    @Test
    void protectsAndSynchronizesReferencedCourses() {
        Course original = controller.addCourse(
                new Course("C1", "EECS3311", "Software Design"));
        AcademicTask academicTask = new AcademicTask(
                "T1", "Project", start.plusDays(2), 4, 4,
                PriorityLevel.HIGH, original, 30, AcademicTaskType.PROJECT);
        controller.addTask(academicTask);

        assertThrows(IllegalStateException.class,
                () -> controller.deleteCourse(original.getId()));

        Course replacement = new Course(
                "C1", "EECS3311", "Software Design Project");
        controller.updateCourse(replacement);

        assertSame(replacement, academicTask.getCourse());
        controller.deleteTask(academicTask.getId());
        controller.deleteCourse(original.getId());
        assertEquals(List.of(), controller.getCourses());
    }

    @Test
    void delegatesAvailabilityAndScheduleGeneration() {
        Task task = task("T1", 1);
        AvailabilityBlock availability = new AvailabilityBlock(
                "A1", start, start.plusHours(2), AvailabilityType.AVAILABLE);
        controller.addTask(task);
        controller.addAvailability(availability);

        Schedule schedule = controller.generateSchedule(date, date);

        assertEquals(List.of(availability),
                controller.getAvailability(start, start.plusHours(3)));
        assertEquals(1, schedule.getStudyBlocks().size());
        assertEquals(task, schedule.getStudyBlocks().get(0).getTask());
    }

    @Test
    void delegatesProgressUpdates() {
        Task task = task("T1", 2);
        StudyBlock block = new StudyBlock(
                "B1", task, start, start.plusHours(1));

        controller.updateProgress(block, ProgressStatus.PARTIAL, 0.5);

        assertEquals(0.5, task.getCompletedHours(), 0.0001);
        assertEquals(1, controller.getProgressHistory(task).size());
    }

    @Test
    void supportsCompletedAndSkippedProgressCorrectionsThroughFacade() {
        Task task = task("T1", 2);
        StudyBlock block = new StudyBlock(
                "B1", task, start, start.plusHours(1));

        controller.updateProgress(
                block, ProgressStatus.COMPLETED, block.durationHours());
        controller.updateProgress(block, ProgressStatus.SKIPPED, 0);

        assertEquals(0, task.getCompletedHours(), 0.0001);
        assertEquals(ProgressStatus.SKIPPED, block.getProgressStatus());
        assertEquals(2, controller.getProgressHistory(task).size());
    }

    @Test
    void exposesPartialScheduleAndDeficitWhenGenerationIsInfeasible() {
        controller.addTask(task("T1", 2));
        controller.addAvailability(new AvailabilityBlock(
                "A1", start, start.plusHours(1), AvailabilityType.AVAILABLE));

        ScheduleInfeasibleException exception = assertThrows(
                ScheduleInfeasibleException.class,
                () -> controller.generateSchedule(date, date));

        assertEquals(1, exception.getPartialSchedule().getStudyBlocks().size());
        assertEquals(1, exception.getWorkloadDeficitHours(), 0.0001);
    }

    @Test
    void delegatesReplanningRequest() {
        controller.addTask(task("T1", 1));
        controller.addAvailability(new AvailabilityBlock(
                "A1", start, start.plusHours(2), AvailabilityType.AVAILABLE));
        Schedule schedule = controller.generateSchedule(date, date);

        Schedule replanned = controller.requestReplan(
                ReplanOption.KEEP_CURRENT_SCHEDULE);

        assertSame(schedule, replanned);
    }

    @Test
    void delegatesUserPreferenceManagement() {
        UserPreferences preferences = new UserPreferences(
                45, StudyPeriod.EVENING, 10);

        controller.updateUserPreferences(preferences);

        UserPreferences saved = controller.getUserPreferences();
        assertEquals(45, saved.getMaxSessionLength());
        assertEquals(StudyPeriod.EVENING, saved.getPreferredStudyPeriod());
        assertEquals(10, saved.getBreakLength());
    }

    @Test
    void taskAndAvailabilityMutationsMarkCurrentScheduleOutdated() {
        controller.addTask(task("T1", 1));
        controller.addAvailability(new AvailabilityBlock(
                "A1", start, start.plusHours(2), AvailabilityType.AVAILABLE));
        Schedule firstSchedule = controller.generateSchedule(date, date);

        controller.addTask(task("T2", 1));

        assertEquals(ScheduleStatus.OUTDATED, firstSchedule.getStatus());

        Schedule secondSchedule = controller.generateSchedule(date, date);
        controller.updateAvailability(new AvailabilityBlock(
                "A1", start, start.plusHours(3), AvailabilityType.AVAILABLE));

        assertEquals(ScheduleStatus.OUTDATED, secondSchedule.getStatus());
    }

    @Test
    void progressMutationMarksCurrentScheduleOutdated() {
        controller.addTask(task("T1", 2));
        controller.addAvailability(new AvailabilityBlock(
                "A1", start, start.plusHours(2), AvailabilityType.AVAILABLE));
        Schedule schedule = controller.generateSchedule(date, date);
        StudyBlock block = schedule.getStudyBlocks().get(0);

        controller.updateProgress(block, ProgressStatus.PARTIAL, 0.5);

        assertEquals(ScheduleStatus.OUTDATED, schedule.getStatus());
    }

    @Test
    void exposesNotReadyIntegrationPointsWithoutFakeResponses() {
        Task task = task("T1", 1);

        assertThrows(UnsupportedOperationException.class,
                () -> controller.requestEffortSuggestion(task));
        assertThrows(UnsupportedOperationException.class,
                () -> controller.parseNaturalLanguageTask("Assignment due tomorrow"));
        assertThrows(UnsupportedOperationException.class,
                () -> controller.explainSchedule("Why is this task first?"));
    }

    @Test
    void delegatesEffortSuggestionWithoutChangingTask() {
        CourseService courseService = new CourseService();
        TaskService taskService = new TaskService(courseService);
        AvailabilityService availabilityService = new AvailabilityService();
        ProgressService progressService = new ProgressService();
        SchedulePlanner schedulePlanner = new SchedulePlanner(
                task -> 1.0, new ConflictDetector(), java.time.Clock.fixed(java.time.Instant.parse("2026-10-01T00:00:00Z"), java.time.ZoneOffset.UTC));
        ScheduleService scheduleService = new ScheduleService(
                taskService, availabilityService, schedulePlanner,
                new UserPreferences(60, StudyPeriod.ANY, 0));
        EffortEstimator estimator = new EffortEstimator(new PlanningAgent(
                (systemInstruction, userPrompt) ->
                        "{\"estimatedHours\":3.5}"));
        PlanPilotController aiController = new PlanPilotController(
                courseService, taskService, availabilityService,
                scheduleService, progressService, estimator);
        Task task = task("T1", 2);

        double suggestion = aiController.requestEffortSuggestion(task);

        assertEquals(3.5, suggestion, 0.0001);
        assertEquals(2, task.getEstimatedHours(), 0.0001);
    }

    @Test
    void delegatesNaturalLanguageParsingWithoutSavingProposal() {
        CourseService courseService = new CourseService();
        TaskService taskService = new TaskService(courseService);
        AvailabilityService availabilityService = new AvailabilityService();
        ProgressService progressService = new ProgressService();
        SchedulePlanner schedulePlanner = new SchedulePlanner(
                task -> 1.0, new ConflictDetector(), java.time.Clock.fixed(java.time.Instant.parse("2026-10-01T00:00:00Z"), java.time.ZoneOffset.UTC));
        ScheduleService scheduleService = new ScheduleService(
                taskService, availabilityService, schedulePlanner,
                new UserPreferences(60, StudyPeriod.ANY, 0));
        PlanningAgent planningAgent = new PlanningAgent(
                (systemInstruction, userPrompt) -> """
                        {"title":"Read chapter 4",
                        "deadline":"2026-11-06T18:00:00",
                        "estimatedHours":2,"taskKind":"GENERAL"}
                        """);
        PlanPilotController aiController = new PlanPilotController(
                courseService, taskService, availabilityService,
                scheduleService, progressService, null, planningAgent);

        ParsedTaskData proposal = aiController.parseNaturalLanguageTask(
                "Read chapter 4 by Friday at 6 PM");

        assertEquals("Read chapter 4", proposal.title());
        assertEquals(2, proposal.estimatedHours(), 0.0001);
        assertEquals(List.of(), aiController.getTasks());
    }

    @Test
    void explainsCurrentScheduleWithoutChangingIt() {
        CourseService courseService = new CourseService();
        TaskService taskService = new TaskService(courseService);
        AvailabilityService availabilityService = new AvailabilityService();
        ProgressService progressService = new ProgressService();
        SchedulePlanner schedulePlanner = new SchedulePlanner(
                task -> 1.0, new ConflictDetector(), java.time.Clock.fixed(java.time.Instant.parse("2026-10-01T00:00:00Z"), java.time.ZoneOffset.UTC));
        ScheduleService scheduleService = new ScheduleService(
                taskService, availabilityService, schedulePlanner,
                new UserPreferences(60, StudyPeriod.ANY, 0));
        PlanningAgent planningAgent = new PlanningAgent(
                (systemInstruction, userPrompt) ->
                        "It was placed in the available morning slot.");
        PlanPilotController aiController = new PlanPilotController(
                courseService, taskService, availabilityService,
                scheduleService, progressService, null, planningAgent);
        Task task = task("T1", 1);
        aiController.addTask(task);
        aiController.addAvailability(new AvailabilityBlock(
                "A1", start, start.plusHours(2),
                AvailabilityType.AVAILABLE));
        Schedule schedule = aiController.generateSchedule(date, date);
        List<StudyBlock> originalBlocks = schedule.getStudyBlocks();

        String explanation = aiController.explainSchedule(
                "Why was this task scheduled in the morning?");

        assertEquals("It was placed in the available morning slot.",
                explanation);
        assertSame(schedule, scheduleService.getCurrentSchedule());
        assertEquals(originalBlocks, schedule.getStudyBlocks());
        assertEquals(ScheduleStatus.CURRENT, schedule.getStatus());
    }

    @Test
    void suggestsRecoveryWithoutApplyingChanges() {
        CourseService courseService = new CourseService();
        TaskService taskService = new TaskService(courseService);
        AvailabilityService availabilityService = new AvailabilityService();
        ProgressService progressService = new ProgressService();
        ScheduleService scheduleService = new ScheduleService(
                taskService, availabilityService,
                new SchedulePlanner(task -> 1.0, new ConflictDetector(), java.time.Clock.fixed(java.time.Instant.parse("2026-10-01T00:00:00Z"), java.time.ZoneOffset.UTC)),
                new UserPreferences(60, StudyPeriod.ANY, 0));
        PlanningAgent planningAgent = new PlanningAgent(
                (systemInstruction, userPrompt) -> """
                        {"summary":"One hour cannot fit.","suggestions":[
                        {"action":"EXTEND_PLANNING_WINDOW",
                        "explanation":"Add another planning day."}]}
                        """);
        PlanPilotController aiController = new PlanPilotController(
                courseService, taskService, availabilityService,
                scheduleService, progressService, null, planningAgent);
        Task task = task("T1", 2);
        LocalDateTime originalDeadline = task.getDeadline();
        aiController.addTask(task);
        aiController.addAvailability(new AvailabilityBlock(
                "A1", start, start.plusHours(1),
                AvailabilityType.AVAILABLE));
        ScheduleInfeasibleException infeasibility = assertThrows(
                ScheduleInfeasibleException.class,
                () -> aiController.generateSchedule(date, date));

        RecoveryPlan recovery = aiController.requestRecoverySuggestions(
                infeasibility);

        assertEquals(RecoveryAction.EXTEND_PLANNING_WINDOW,
                recovery.suggestions().get(0).action());
        assertSame(infeasibility.getPartialSchedule(),
                scheduleService.getCurrentSchedule());
        assertEquals(originalDeadline, task.getDeadline());
        assertEquals(2, task.getRemainingHours(), 0.0001);
    }

    @Test
    void recommendsReplanningWithoutApplyingIt() {
        CourseService courseService = new CourseService();
        TaskService taskService = new TaskService(courseService);
        AvailabilityService availabilityService = new AvailabilityService();
        ProgressService progressService = new ProgressService();
        ScheduleService scheduleService = new ScheduleService(
                taskService, availabilityService,
                new SchedulePlanner(task -> 1.0, new ConflictDetector(), java.time.Clock.fixed(java.time.Instant.parse("2026-10-01T00:00:00Z"), java.time.ZoneOffset.UTC)),
                new UserPreferences(60, StudyPeriod.ANY, 0));
        PlanningAgent planningAgent = new PlanningAgent(
                (systemInstruction, userPrompt) -> """
                        {"impactSummary":"The schedule is outdated.",
                        "recommendedOption":"REPLAN_ENTIRE_WINDOW",
                        "rationale":"A new task must be incorporated."}
                        """);
        PlanPilotController aiController = new PlanPilotController(
                courseService, taskService, availabilityService,
                scheduleService, progressService, null, planningAgent);
        aiController.addTask(task("T1", 1));
        aiController.addAvailability(new AvailabilityBlock(
                "A1", start, start.plusHours(3),
                AvailabilityType.AVAILABLE));
        Schedule original = aiController.generateSchedule(date, date);
        aiController.addTask(task("T2", 1));

        ReplanRecommendation recommendation =
                aiController.requestReplanRecommendation();

        assertEquals(ReplanOption.REPLAN_ENTIRE_WINDOW,
                recommendation.recommendedOption());
        assertSame(original, scheduleService.getCurrentSchedule());
        assertEquals(ScheduleStatus.OUTDATED, original.getStatus());
        assertEquals(1, original.getStudyBlocks().size());
    }

    private Task task(String id, double hours) {
        return new GeneralTask(
                id, "Task " + id, start.plusDays(1), hours,
                3, PriorityLevel.MEDIUM);
    }
}

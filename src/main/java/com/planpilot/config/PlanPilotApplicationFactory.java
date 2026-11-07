package com.planpilot.config;

import com.planpilot.ai.GeminiLLMClient;
import com.planpilot.ai.PlanningAgent;
import com.planpilot.controller.PlanPilotController;
import com.planpilot.domain.StudyPeriod;
import com.planpilot.domain.UserPreferences;
import com.planpilot.event.PlanningEventPublisher;
import com.planpilot.service.AvailabilityService;
import com.planpilot.service.ConflictDetector;
import com.planpilot.service.CourseService;
import com.planpilot.service.EffortEstimator;
import com.planpilot.service.ProgressService;
import com.planpilot.service.SchedulePlanner;
import com.planpilot.service.ScheduleService;
import com.planpilot.service.TaskService;
import com.planpilot.strategy.BalancedPriorityStrategy;

public final class PlanPilotApplicationFactory {
    private static final int DEFAULT_MAX_SESSION_MINUTES = 60;
    private static final int DEFAULT_BREAK_MINUTES = 10;

    private PlanPilotApplicationFactory() {
    }

    public static PlanPilotApplicationContext createDefault() {
        return create(java.nio.file.Path.of(System.getProperty("planpilot.progress.file", ".planpilot/progress.json")),
                java.time.Clock.systemDefaultZone());
    }

    public static PlanPilotApplicationContext create(java.nio.file.Path journal, java.time.Clock clock) {
        java.util.Objects.requireNonNull(journal, "Workspace file cannot be null.");
        java.util.Objects.requireNonNull(clock, "Clock cannot be null.");
        PlanningEventPublisher publisher = new PlanningEventPublisher();
        CourseService courseService = new CourseService();
        TaskService taskService = new TaskService(courseService, publisher);
        AvailabilityService availabilityService = new AvailabilityService();
        ProgressService progressService = new ProgressService(publisher, clock, journal);
        var workspace = progressService.getRestoredWorkspace();
        workspace.ifPresent(saved -> {
            saved.courses().forEach(courseService::addCourse);
            saved.availability().forEach(availabilityService::addAvailability);
        });
        for (var task : progressService.getRestoredTasks()) {
            if (task instanceof com.planpilot.domain.AcademicTask academic
                    && courseService.getCourses().stream().noneMatch(c -> c.getId().equals(academic.getCourse().getId()))) {
                if (workspace.isPresent())
                    throw new IllegalStateException("Saved academic task references a missing course; the workspace has been kept unchanged.");
                courseService.addCourse(academic.getCourse());
            }
            taskService.createTask(task);
        }
        SchedulePlanner schedulePlanner = new SchedulePlanner(
                new BalancedPriorityStrategy(clock), new ConflictDetector(), clock);
        ScheduleService scheduleService = new ScheduleService(
                taskService, availabilityService, schedulePlanner,
                workspace.map(com.planpilot.service.WorkspaceSnapshot::preferences).orElseGet(() -> new UserPreferences(
                        DEFAULT_MAX_SESSION_MINUTES,
                        StudyPeriod.ANY,
                        DEFAULT_BREAK_MINUTES)),
                publisher);
        workspace.ifPresent(scheduleService::restoreWorkspace);
        progressService.setWorkspaceSnapshotSupplier(() -> scheduleService.snapshot(courseService.getCourses()));
        AiComponents aiComponents = createAiComponents();
        PlanPilotController controller = new PlanPilotController(
                courseService, taskService, availabilityService,
                scheduleService, progressService,
                aiComponents.effortEstimator(), aiComponents.planningAgent());
        return new PlanPilotApplicationContext(controller, publisher);
    }

    private static AiComponents createAiComponents() {
        try {
            GeminiLLMClient client = new GeminiLLMClient(
                    GeminiConfiguration.load());
            PlanningAgent planningAgent = new PlanningAgent(client);
            return new AiComponents(
                    planningAgent, new EffortEstimator(planningAgent));
        } catch (IllegalStateException exception) {
            return new AiComponents(null, null);
        }
    }

    private record AiComponents(
            PlanningAgent planningAgent,
            EffortEstimator effortEstimator) {
    }
}

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
        java.time.Clock clock = java.time.Clock.systemDefaultZone();
        PlanningEventPublisher publisher = new PlanningEventPublisher();
        CourseService courseService = new CourseService();
        TaskService taskService = new TaskService(courseService, publisher);
        AvailabilityService availabilityService = new AvailabilityService();
        ProgressService progressService = new ProgressService(publisher, clock,
                        java.nio.file.Path.of(System.getProperty("planpilot.progress.file", ".planpilot/progress.json")));
        for (var task : progressService.getRestoredTasks()) {
            if (task instanceof com.planpilot.domain.AcademicTask academic
                    && courseService.getCourses().stream().noneMatch(c -> c.getId().equals(academic.getCourse().getId()))) {
                courseService.addCourse(academic.getCourse());
            }
            taskService.createTask(task);
        }
        SchedulePlanner schedulePlanner = new SchedulePlanner(
                new BalancedPriorityStrategy(clock), new ConflictDetector(), clock);
        ScheduleService scheduleService = new ScheduleService(
                taskService, availabilityService, schedulePlanner,
                new UserPreferences(
                        DEFAULT_MAX_SESSION_MINUTES,
                        StudyPeriod.ANY,
                        DEFAULT_BREAK_MINUTES),
                publisher);
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

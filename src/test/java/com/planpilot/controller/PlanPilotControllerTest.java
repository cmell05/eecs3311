package com.planpilot.controller;

import com.planpilot.domain.AvailabilityBlock;
import com.planpilot.domain.AvailabilityType;
import com.planpilot.domain.Course;
import com.planpilot.domain.GeneralTask;
import com.planpilot.domain.PriorityLevel;
import com.planpilot.domain.ProgressStatus;
import com.planpilot.domain.Schedule;
import com.planpilot.domain.StudyBlock;
import com.planpilot.domain.StudyPeriod;
import com.planpilot.domain.Task;
import com.planpilot.domain.UserPreferences;
import com.planpilot.service.AvailabilityService;
import com.planpilot.service.ConflictDetector;
import com.planpilot.service.CourseService;
import com.planpilot.service.ProgressService;
import com.planpilot.service.ReplanOption;
import com.planpilot.service.SchedulePlanner;
import com.planpilot.service.ScheduleService;
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
                task -> 1.0, new ConflictDetector());
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
    void exposesNotReadyIntegrationPointsWithoutFakeResponses() {
        Task task = task("T1", 1);

        assertThrows(UnsupportedOperationException.class,
                () -> controller.requestEffortSuggestion(task));
        assertThrows(UnsupportedOperationException.class,
                () -> controller.parseNaturalLanguageTask("Assignment due tomorrow"));
        assertThrows(UnsupportedOperationException.class,
                () -> controller.explainSchedule("Why is this task first?"));
    }

    private Task task(String id, double hours) {
        return new GeneralTask(
                id, "Task " + id, start.plusDays(1), hours,
                3, PriorityLevel.MEDIUM);
    }
}

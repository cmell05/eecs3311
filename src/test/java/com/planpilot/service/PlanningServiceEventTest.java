package com.planpilot.service;

import com.planpilot.domain.AvailabilityBlock;
import com.planpilot.domain.AvailabilityType;
import com.planpilot.domain.GeneralTask;
import com.planpilot.domain.PriorityLevel;
import com.planpilot.domain.ProgressStatus;
import com.planpilot.domain.Schedule;
import com.planpilot.domain.ScheduleStatus;
import com.planpilot.domain.StudyBlock;
import com.planpilot.domain.StudyPeriod;
import com.planpilot.domain.Task;
import com.planpilot.domain.UserPreferences;
import com.planpilot.event.PlanningEventPublisher;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PlanningServiceEventTest {
    private final LocalDate date = LocalDate.of(2026, 10, 28);
    private final LocalDateTime start = date.atTime(9, 0);

    @Test
    void taskMutationsNotifyObservers() {
        PlanningEventPublisher publisher = new PlanningEventPublisher();
        int[] notifications = subscribeCounter(publisher);
        TaskService taskService = new TaskService(new CourseService(), publisher);
        Task task = task("T1", 2);

        taskService.createTask(task);
        taskService.updateTask(task("T1", 3));
        taskService.deleteTask("T1");

        assertEquals(3, notifications[0]);
    }

    @Test
    void progressMutationNotifiesWithoutRegeneratingSchedule() {
        PlanningEventPublisher publisher = new PlanningEventPublisher();
        int[] notifications = subscribeCounter(publisher);
        ProgressService progressService = new ProgressService(publisher);
        Task task = task("T1", 2);
        StudyBlock block = new StudyBlock(
                "B1", task, start, start.plusHours(1));

        progressService.recordProgress(block, ProgressStatus.PARTIAL, 0.5);

        assertEquals(1, notifications[0]);
        assertEquals(0.5, task.getCompletedHours(), 0.0001);
    }

    @Test
    void scheduleGenerationAndOutdatedStateNotifyObservers() {
        PlanningEventPublisher publisher = new PlanningEventPublisher();
        int[] notifications = subscribeCounter(publisher);
        CourseService courseService = new CourseService();
        TaskService taskService = new TaskService(courseService);
        taskService.createTask(task("T1", 1));
        AvailabilityService availabilityService = new AvailabilityService();
        availabilityService.addAvailability(new AvailabilityBlock(
                "A1", start, start.plusHours(2), AvailabilityType.AVAILABLE));
        SchedulePlanner planner = new SchedulePlanner(
                task -> 1.0, new ConflictDetector());
        ScheduleService scheduleService = new ScheduleService(
                taskService, availabilityService, planner,
                new UserPreferences(60, StudyPeriod.ANY, 0), publisher);

        Schedule schedule = scheduleService.generateSchedule(date, date);
        scheduleService.markScheduleOutdated();

        assertEquals(2, notifications[0]);
        assertEquals(ScheduleStatus.OUTDATED, schedule.getStatus());
    }

    private int[] subscribeCounter(PlanningEventPublisher publisher) {
        int[] notifications = {0};
        publisher.subscribe(() -> notifications[0]++);
        return notifications;
    }

    private Task task(String id, double hours) {
        return new GeneralTask(
                id, "Task " + id, start.plusDays(1), hours,
                3, PriorityLevel.MEDIUM);
    }
}

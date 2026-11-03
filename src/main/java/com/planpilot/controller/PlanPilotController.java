package com.planpilot.controller;

import com.planpilot.ai.ParsedTaskData;
import com.planpilot.ai.PlanningAgent;
import com.planpilot.ai.RecoveryPlan;
import com.planpilot.domain.AvailabilityBlock;
import com.planpilot.domain.Course;
import com.planpilot.domain.ProgressRecord;
import com.planpilot.domain.ProgressStatus;
import com.planpilot.domain.Schedule;
import com.planpilot.domain.StudyBlock;
import com.planpilot.domain.Task;
import com.planpilot.domain.UserPreferences;
import com.planpilot.service.AvailabilityService;
import com.planpilot.service.CourseService;
import com.planpilot.service.EffortEstimator;
import com.planpilot.service.ProgressService;
import com.planpilot.service.ReplanOption;
import com.planpilot.service.ScheduleService;
import com.planpilot.service.ScheduleInfeasibleException;
import com.planpilot.service.TaskService;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

public class PlanPilotController {
    private final CourseService courseService;
    private final TaskService taskService;
    private final AvailabilityService availabilityService;
    private final ScheduleService scheduleService;
    private final ProgressService progressService;
    private final EffortEstimator effortEstimator;
    private final PlanningAgent planningAgent;

    public PlanPilotController(
            CourseService courseService,
            TaskService taskService,
            AvailabilityService availabilityService,
            ScheduleService scheduleService,
            ProgressService progressService) {
        this(courseService, taskService, availabilityService, scheduleService,
                progressService, null, null);
    }

    public PlanPilotController(
            CourseService courseService,
            TaskService taskService,
            AvailabilityService availabilityService,
            ScheduleService scheduleService,
            ProgressService progressService,
            EffortEstimator effortEstimator) {
        this(courseService, taskService, availabilityService, scheduleService,
                progressService, effortEstimator, null);
    }

    public PlanPilotController(
            CourseService courseService,
            TaskService taskService,
            AvailabilityService availabilityService,
            ScheduleService scheduleService,
            ProgressService progressService,
            EffortEstimator effortEstimator,
            PlanningAgent planningAgent) {
        this.courseService = Objects.requireNonNull(
                courseService, "Course service cannot be null.");
        this.taskService = Objects.requireNonNull(
                taskService, "Task service cannot be null.");
        this.availabilityService = Objects.requireNonNull(
                availabilityService, "Availability service cannot be null.");
        this.scheduleService = Objects.requireNonNull(
                scheduleService, "Schedule service cannot be null.");
        this.progressService = Objects.requireNonNull(
                progressService, "Progress service cannot be null.");
        this.effortEstimator = effortEstimator;
        this.planningAgent = planningAgent;
    }

    public Course addCourse(Course courseData) {
        return courseService.addCourse(courseData);
    }

    public void updateCourse(Course course) {
        courseService.updateCourse(course);
        taskService.updateCourseReferences(course);
    }

    public void deleteCourse(String courseId) {
        if (taskService.hasTasksForCourse(courseId)) {
            throw new IllegalStateException(
                    "Cannot delete a course while academic tasks reference it.");
        }
        courseService.deleteCourse(courseId);
    }

    public List<Course> getCourses() {
        return courseService.getCourses();
    }

    public Task addTask(Task taskData) {
        Task created = taskService.createTask(taskData);
        scheduleService.markScheduleOutdatedIfPresent();
        return created;
    }

    public void updateTask(Task task) {
        taskService.updateTask(task);
        scheduleService.markScheduleOutdatedIfPresent();
    }

    public void deleteTask(String taskId) {
        taskService.deleteTask(taskId);
        scheduleService.markScheduleOutdatedIfPresent();
    }

    public List<Task> getOpenTasks() {
        return taskService.getOpenTasks();
    }

    public List<Task> getTasks() {
        return taskService.getTasks();
    }

    public void addAvailability(AvailabilityBlock block) {
        availabilityService.addAvailability(block);
        scheduleService.markScheduleOutdatedIfPresent();
    }

    public void updateAvailability(AvailabilityBlock block) {
        availabilityService.updateAvailability(block);
        scheduleService.markScheduleOutdatedIfPresent();
    }

    public void deleteAvailability(String blockId) {
        availabilityService.deleteAvailability(blockId);
        scheduleService.markScheduleOutdatedIfPresent();
    }

    public List<AvailabilityBlock> getAvailability(
            LocalDateTime start, LocalDateTime end) {
        return availabilityService.getAvailability(start, end);
    }

    public Schedule generateSchedule(LocalDate start, LocalDate end) {
        return scheduleService.generateSchedule(start, end);
    }

    public void updateProgress(
            StudyBlock block, ProgressStatus status, double hours) {
        progressService.recordProgress(block, status, hours);
        scheduleService.markScheduleOutdatedIfPresent();
    }

    public void inferProgress(StudyBlock block) {
        progressService.inferProgress(block);
        scheduleService.markScheduleOutdatedIfPresent();
    }

    public List<ProgressRecord> getProgressHistory(Task task) {
        return progressService.getHistoryForTask(task);
    }

    public Schedule requestReplan(ReplanOption option) {
        return scheduleService.replan(option);
    }

    public UserPreferences getUserPreferences() {
        return scheduleService.getPreferences();
    }

    public void updateUserPreferences(UserPreferences preferences) {
        scheduleService.updatePreferences(preferences);
    }

    public void markScheduleOutdated() {
        scheduleService.markScheduleOutdated();
    }

    public double requestEffortSuggestion(Task task) {
        Objects.requireNonNull(task, "Task cannot be null.");
        if (effortEstimator == null) {
            throw new UnsupportedOperationException(
                    "Effort estimation is not configured.");
        }
        return effortEstimator.estimateEffort(
                task, progressService.getHistory());
    }

    public ParsedTaskData parseNaturalLanguageTask(String text) {
        requireText(text, "Task description");
        if (planningAgent == null) {
            throw new UnsupportedOperationException(
                    "Natural-language task parsing is not configured.");
        }
        return planningAgent.parseTask(text);
    }

    public String explainSchedule(String question) {
        requireText(question, "Schedule question");
        if (planningAgent == null) {
            throw new UnsupportedOperationException(
                    "Schedule explanations are not configured.");
        }
        Schedule schedule = scheduleService.getCurrentSchedule();
        LocalDateTime windowStart = schedule.getStartDate().atStartOfDay();
        LocalDateTime windowEnd = schedule.getEndDate()
                .plusDays(1).atStartOfDay();
        return planningAgent.explainSchedule(
                question,
                schedule,
                availabilityService.getAvailability(windowStart, windowEnd));
    }

    public RecoveryPlan requestRecoverySuggestions(
            ScheduleInfeasibleException infeasibility) {
        Objects.requireNonNull(infeasibility,
                "Schedule infeasibility cannot be null.");
        if (planningAgent == null) {
            throw new UnsupportedOperationException(
                    "Workload recovery suggestions are not configured.");
        }
        Schedule partialSchedule = infeasibility.getPartialSchedule();
        LocalDateTime windowStart = partialSchedule.getStartDate()
                .atStartOfDay();
        LocalDateTime windowEnd = partialSchedule.getEndDate()
                .plusDays(1).atStartOfDay();
        return planningAgent.suggestRecovery(
                infeasibility.getWorkloadDeficitHours(),
                partialSchedule,
                taskService.getOpenTasks(),
                availabilityService.getAvailability(windowStart, windowEnd));
    }

    private static void requireText(String value, String fieldName) {
        Objects.requireNonNull(value, fieldName + " cannot be null.");
        if (value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " cannot be blank.");
        }
    }
}

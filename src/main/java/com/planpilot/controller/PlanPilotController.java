package com.planpilot.controller;

import com.planpilot.ai.ParsedTaskData;
import com.planpilot.ai.PlanningAgent;
import com.planpilot.ai.RecoveryPlan;
import com.planpilot.ai.ReplanRecommendation;
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
import com.planpilot.service.RecoveryRequest;
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
        Course course = courseService.addCourse(courseData);
        checkpointWorkspace();
        return course;
    }

    public void updateCourse(Course course) {
        courseService.updateCourse(course);
        taskService.updateCourseReferences(course);
        progressService.registerTasks(taskService.getTasks());
        checkpointWorkspace();
    }

    public void deleteCourse(String courseId) {
        if (taskService.hasTasksForCourse(courseId)) {
            throw new IllegalStateException(
                    "Cannot delete a course while academic tasks reference it.");
        }
        courseService.deleteCourse(courseId);
        checkpointWorkspace();
    }

    public List<Course> getCourses() {
        return courseService.getCourses();
    }

    public Task addTask(Task taskData) {
        Task created = taskService.createTask(taskData);
        progressService.registerTasks(java.util.List.of(created));
        scheduleService.markScheduleOutdatedIfPresent();
        checkpointWorkspace();
        return created;
    }

    public void updateTask(Task task) {
        taskService.updateTask(task);
        progressService.registerTasks(taskService.getTasks());
        scheduleService.markScheduleOutdatedIfPresent();
        checkpointWorkspace();
    }

    public void deleteTask(String taskId) {
        taskService.deleteTask(taskId);
        progressService.archiveTask(taskId);
        scheduleService.markScheduleOutdatedIfPresent();
        checkpointWorkspace();
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
        checkpointWorkspace();
    }

    public void updateAvailability(AvailabilityBlock block) {
        availabilityService.updateAvailability(block);
        scheduleService.markScheduleOutdatedIfPresent();
        checkpointWorkspace();
    }

    public void deleteAvailability(String blockId) {
        availabilityService.deleteAvailability(blockId);
        scheduleService.markScheduleOutdatedIfPresent();
        checkpointWorkspace();
    }

    public List<AvailabilityBlock> getAvailability(
            LocalDateTime start, LocalDateTime end) {
        return availabilityService.getAvailability(start, end);
    }

    public Schedule generateSchedule(LocalDate start, LocalDate end) {
        refreshProgress();
        scheduleService.retainSessions(progressService.getSessions());
        try {
            Schedule schedule = scheduleService.generateSchedule(start, end);
            progressService.trackSessions(schedule.getStudyBlocks());
            checkpointWorkspace();
            return schedule;
        } catch (ScheduleInfeasibleException failure) {
            progressService.trackSessions(failure.getPartialSchedule().getStudyBlocks());
            checkpointWorkspace();
            throw failure;
        }
    }

    public void updateProgress(
            StudyBlock block, ProgressStatus status, double hours) {
        progressService.registerTasks(taskService.getTasks());
        StudyBlock previous = progressService.getSessions().stream()
                .filter(saved -> saved.getId().equals(block.getId())).findFirst().orElse(block);
        double before = previous.getTask().getCompletedHours();
        ProgressStatus previousStatus = previous.getProgressStatus();
        boolean wasInferred = previous.isInferred();
        progressService.recordProgress(block, status, hours);
        if (before != block.getTask().getCompletedHours() || previousStatus != block.getProgressStatus()
                || wasInferred != block.isInferred()) scheduleService.markScheduleOutdatedIfPresent();
        checkpointWorkspace();
    }

    public void inferProgress(StudyBlock block) {
        double before = block.getTask().getCompletedHours();
        ProgressStatus previousStatus = block.getProgressStatus();
        progressService.inferProgress(block);
        if (before != block.getTask().getCompletedHours() || previousStatus != block.getProgressStatus())
            scheduleService.markScheduleOutdatedIfPresent();
        checkpointWorkspace();
    }

    public void refreshProgress() {
        progressService.registerTasks(taskService.getTasks());
        if (progressService.inferPastSessions()) {
            scheduleService.markScheduleOutdatedIfPresent();
            checkpointWorkspace();
        }
    }

    public List<StudyBlock> getProgressSessions() {
        progressService.registerTasks(taskService.getTasks());
        return progressService.getSessions();
    }

    public double getConfirmedHours(Task task) { return progressService.getConfirmedHours(task); }
    public double getInferredHours(Task task) { return progressService.getInferredHours(task); }
    public double getOriginalEstimate(Task task) { return progressService.getOriginalEstimate(task); }

    public List<ProgressRecord> getProgressHistory(Task task) {
        return progressService.getHistoryForTask(task);
    }

    private void checkpointWorkspace() {
        progressService.registerTasks(taskService.getTasks());
        progressService.checkpoint();
    }

    public java.util.Optional<ScheduleInfeasibleException> getCurrentInfeasibility() {
        return scheduleService.getCurrentInfeasibility();
    }

    public java.util.Optional<Schedule> getCurrentSchedule() {
        return scheduleService.findCurrentSchedule();
    }

    public Schedule requestReplan(ReplanOption option) {
        if (option == ReplanOption.KEEP_CURRENT_SCHEDULE) return scheduleService.replan(option);
        refreshProgress();
        scheduleService.retainSessions(progressService.getSessions());
        try {
            Schedule schedule = scheduleService.replan(option);
            progressService.trackSessions(schedule.getStudyBlocks());
            checkpointWorkspace();
            return schedule;
        } catch (ScheduleInfeasibleException failure) {
            progressService.trackSessions(failure.getPartialSchedule().getStudyBlocks());
            checkpointWorkspace();
            throw failure;
        }
    }

    public void resumeTask(String taskId) {
        Task task = taskService.getTasks().stream().filter(t -> t.getId().equals(taskId))
                .findFirst().orElseThrow(() -> new IllegalArgumentException("Select an existing task."));
        task.setDeferredUntil(null);
        updateTask(task);
    }

    /** Apply only an explicit, validated choice; AI suggestions alone never call this. */
    public Schedule applyRecovery(RecoveryRequest request) {
        Objects.requireNonNull(request, "Review a recovery action first.");
        Schedule partial = scheduleService.requireRecoverySchedule(request.scheduleId());
        LocalDate start = partial.getStartDate();
        LocalDate end = partial.getEndDate();
        switch (request.action()) {
            case KEEP_CURRENT_CONSTRAINTS -> { return partial; }
            case EXTEND_PLANNING_WINDOW -> {
                if (!request.newEndDate().isAfter(end))
                    throw new IllegalArgumentException("The new end date must be later than the current planning end date.");
                end = request.newEndDate();
            }
            case ADD_AVAILABILITY -> {
                AvailabilityBlock block = request.availability();
                if (!block.getStart().isBefore(end.plusDays(1).atStartOfDay())
                        || !block.getEnd().isAfter(start.atStartOfDay()))
                    throw new IllegalArgumentException("Add Available time inside the current planning window.");
                addAvailability(block);
            }
            case DEFER_LOWER_PRIORITY_TASK -> {
                Task task = taskService.getOpenTasks().stream().filter(t -> t.getId().equals(request.taskId()))
                        .findFirst().orElseThrow(() -> new IllegalArgumentException("Select an unfinished task."));
                if (task.getDeferredUntil() != null && !task.getDeferredUntil().isBefore(end.plusDays(1).atStartOfDay()))
                    throw new IllegalArgumentException("This task is already deferred from this window.");
                task.setDeferredUntil(end.plusDays(1).atStartOfDay());
                updateTask(task);
            }
        }
        return generateSchedule(start, end);
    }

    public UserPreferences getUserPreferences() {
        return scheduleService.getPreferences();
    }

    public void updateUserPreferences(UserPreferences preferences) {
        scheduleService.updatePreferences(preferences);
        checkpointWorkspace();
    }

    public boolean isAiAvailable() {
        return planningAgent != null && effortEstimator != null;
    }

    public void markScheduleOutdated() {
        scheduleService.markScheduleOutdated();
        checkpointWorkspace();
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
                availabilityService.getAvailability(windowStart, windowEnd), progressService.getHistory());
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
                availabilityService.getAvailability(windowStart, windowEnd), progressService.getHistory());
    }

    public ReplanRecommendation requestReplanRecommendation() {
        if (planningAgent == null) {
            throw new UnsupportedOperationException(
                    "Replanning recommendations are not configured.");
        }
        Schedule schedule = scheduleService.getCurrentSchedule();
        LocalDateTime windowStart = schedule.getStartDate().atStartOfDay();
        LocalDateTime windowEnd = schedule.getEndDate()
                .plusDays(1).atStartOfDay();
        return planningAgent.suggestReplanningOption(
                schedule,
                taskService.getOpenTasks(),
                availabilityService.getAvailability(windowStart, windowEnd),
                scheduleService.getPreferences(), progressService.getHistory());
    }

    private static void requireText(String value, String fieldName) {
        Objects.requireNonNull(value, fieldName + " cannot be null.");
        if (value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " cannot be blank.");
        }
    }
}

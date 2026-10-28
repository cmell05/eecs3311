package com.planpilot.service;

import com.planpilot.domain.AcademicTask;
import com.planpilot.domain.Task;
import com.planpilot.event.PlanningEventPublisher;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public class TaskService {
    private final CourseService courseService;
    private final PlanningEventPublisher eventPublisher;
    private final Map<String, Task> tasks = new LinkedHashMap<>();

    public TaskService(CourseService courseService) {
        this(courseService, new PlanningEventPublisher());
    }

    public TaskService(
            CourseService courseService, PlanningEventPublisher eventPublisher) {
        this.courseService = Objects.requireNonNull(
                courseService, "Course service cannot be null.");
        this.eventPublisher = Objects.requireNonNull(
                eventPublisher, "Event publisher cannot be null.");
    }

    public Task createTask(Task taskData) {
        validateTask(taskData);
        if (tasks.containsKey(taskData.getId())) {
            throw new IllegalArgumentException(
                    "A task with id " + taskData.getId() + " already exists.");
        }
        tasks.put(taskData.getId(), taskData);
        eventPublisher.notifyObservers();
        return taskData;
    }

    public void updateTask(Task task) {
        validateTask(task);
        requireExistingTask(task.getId());
        tasks.put(task.getId(), task);
        eventPublisher.notifyObservers();
    }

    public void deleteTask(String taskId) {
        requireText(taskId, "Task id");
        requireExistingTask(taskId);
        tasks.remove(taskId);
        eventPublisher.notifyObservers();
    }

    public List<Task> getOpenTasks() {
        return tasks.values().stream()
                .filter(task -> task.getRemainingHours() > 0)
                .toList();
    }

    private void validateTask(Task task) {
        Objects.requireNonNull(task, "Task cannot be null.");
        requireText(task.getId(), "Task id");
        requireText(task.getTitle(), "Task title");
        Objects.requireNonNull(task.getDeadline(), "Task deadline cannot be null.");
        if (!Double.isFinite(task.getEstimatedHours()) || task.getEstimatedHours() <= 0) {
            throw new IllegalArgumentException("Estimated effort must be a positive finite value.");
        }

        if (task instanceof AcademicTask academicTask) {
            validateAcademicCourse(academicTask);
        }
    }

    private void validateAcademicCourse(AcademicTask task) {
        if (task.getCourse() == null
                || !courseService.containsCourse(task.getCourse().getId())) {
            throw new IllegalArgumentException(
                    "Academic tasks must reference an existing course.");
        }
    }

    private void requireExistingTask(String taskId) {
        if (!tasks.containsKey(taskId)) {
            throw new IllegalArgumentException("No task exists with id " + taskId + ".");
        }
    }

    private static void requireText(String value, String fieldName) {
        Objects.requireNonNull(value, fieldName + " cannot be null.");
        if (value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " cannot be blank.");
        }
    }
}

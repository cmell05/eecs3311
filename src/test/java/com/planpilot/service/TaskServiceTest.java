package com.planpilot.service;

import com.planpilot.domain.AcademicTask;
import com.planpilot.domain.AcademicTaskType;
import com.planpilot.domain.Course;
import com.planpilot.domain.GeneralTask;
import com.planpilot.domain.PriorityLevel;
import com.planpilot.domain.Task;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskServiceTest {
    private CourseService courseService;
    private TaskService taskService;
    private Course course;
    private LocalDateTime deadline;

    @BeforeEach
    void setUp() {
        courseService = new CourseService();
        course = courseService.addCourse(
                new Course("C1", "EECS3311", "Software Design"));
        taskService = new TaskService(courseService);
        deadline = LocalDateTime.of(2026, 11, 1, 23, 59);
    }

    @Test
    void createsAcademicAndGeneralTasks() {
        Task academicTask = academicTask("T1", course);
        Task generalTask = generalTask("T2", "Interview preparation");

        assertSame(academicTask, taskService.createTask(academicTask));
        assertSame(generalTask, taskService.createTask(generalTask));
        assertEquals(List.of(academicTask, generalTask), taskService.getOpenTasks());
    }

    @Test
    void updatesExistingTask() {
        taskService.createTask(generalTask("T1", "Initial title"));
        Task replacement = generalTask("T1", "Updated title");

        taskService.updateTask(replacement);

        assertSame(replacement, taskService.getOpenTasks().get(0));
        assertEquals("Updated title", taskService.getOpenTasks().get(0).getTitle());
    }

    @Test
    void deletesExistingTask() {
        taskService.createTask(generalTask("T1", "Interview preparation"));

        taskService.deleteTask("T1");

        assertTrue(taskService.getOpenTasks().isEmpty());
    }

    @Test
    void rejectsAcademicTaskWithUnknownCourse() {
        Course unknownCourse = new Course("C2", "EECS3221", "Operating Systems");

        assertThrows(IllegalArgumentException.class,
                () -> taskService.createTask(academicTask("T1", unknownCourse)));
    }

    @Test
    void rejectsInvalidEffortAndInvalidOperations() {
        Task invalidEffort = new GeneralTask(
                "T1", "Invalid", deadline, Double.NaN, 2, PriorityLevel.LOW);
        Task validTask = generalTask("T2", "Valid");
        taskService.createTask(validTask);

        assertThrows(IllegalArgumentException.class,
                () -> taskService.createTask(invalidEffort));
        assertThrows(IllegalArgumentException.class,
                () -> new GeneralTask("T3", "Negative", deadline, -1, 2, PriorityLevel.LOW));
        assertThrows(IllegalArgumentException.class,
                () -> taskService.createTask(validTask));
        assertThrows(IllegalArgumentException.class,
                () -> taskService.updateTask(generalTask("missing", "Unknown")));
        assertThrows(IllegalArgumentException.class,
                () -> taskService.deleteTask("missing"));
    }

    @Test
    void getOpenTasksExcludesCompletedTasks() {
        Task openTask = generalTask("T1", "Open");
        Task completedTask = generalTask("T2", "Completed");
        completedTask.markCompleted();
        taskService.createTask(openTask);
        taskService.createTask(completedTask);

        assertEquals(List.of(openTask), taskService.getOpenTasks());
    }

    private AcademicTask academicTask(String id, Course taskCourse) {
        return new AcademicTask(
                id, "Assignment", deadline, 4, 3, PriorityLevel.HIGH,
                taskCourse, 15, AcademicTaskType.ASSIGNMENT);
    }

    private GeneralTask generalTask(String id, String title) {
        return new GeneralTask(id, title, deadline, 2, 2, PriorityLevel.MEDIUM);
    }
}

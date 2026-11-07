package com.planpilot.domain;

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;

public class AcademicTask extends Task {
    private Course course;
    private double assessmentWeight;
    private AcademicTaskType taskType;

    public AcademicTask(String title, LocalDateTime deadline,
                        double estimatedHours, int difficulty,
                        PriorityLevel userImportance, Course course,
                        double assessmentWeight, AcademicTaskType taskType) {
        this(UUID.randomUUID().toString(), title, deadline, estimatedHours,
                difficulty, userImportance, course, assessmentWeight, taskType);
    }

    public AcademicTask(String id, String title, LocalDateTime deadline,
                        double estimatedHours, int difficulty,
                        PriorityLevel userImportance, Course course,
                        double assessmentWeight, AcademicTaskType taskType) {
        super(id, title, deadline, estimatedHours, difficulty, userImportance);
        setCourse(course);
        setAssessmentWeight(assessmentWeight);
        setTaskType(taskType);
    }

    public Course getCourse() { return course; }
    public double getAssessmentWeight() { return assessmentWeight; }
    public AcademicTaskType getTaskType() { return taskType; }

    public void setCourse(Course course) {
        this.course = Objects.requireNonNull(course, "Course cannot be null.");
    }

    public void setAssessmentWeight(double assessmentWeight) {
        if (!Double.isFinite(assessmentWeight) || assessmentWeight < 0 || assessmentWeight > 100) {
            throw new IllegalArgumentException("Assessment weight must be a finite value between 0 and 100.");
        }
        this.assessmentWeight = assessmentWeight;
    }

    public void setTaskType(AcademicTaskType taskType) {
        this.taskType = Objects.requireNonNull(taskType, "Task type cannot be null.");
    }
}

package com.planpilot.strategy;

import com.planpilot.domain.AcademicTask;
import com.planpilot.domain.AcademicTaskType;
import com.planpilot.domain.Course;
import com.planpilot.domain.GeneralTask;
import com.planpilot.domain.PriorityLevel;
import com.planpilot.domain.Task;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertTrue;

class BalancedPriorityStrategyTest {
    private final LocalDateTime now = LocalDateTime.of(2026, 10, 25, 12, 0);
    private final BalancedPriorityStrategy strategy = new BalancedPriorityStrategy(
            Clock.fixed(Instant.parse("2026-10-25T12:00:00Z"), ZoneOffset.UTC));

    @Test
    void urgentTaskScoresHigherThanDistantEquivalentTask() {
        Task urgent = generalTask("T1", now.plusDays(1));
        Task distant = generalTask("T2", now.plusDays(14));

        assertTrue(strategy.calculatePriority(urgent)
                > strategy.calculatePriority(distant));
    }

    @Test
    void higherAssessmentWeightIncreasesAcademicPriority() {
        Course course = new Course("C1", "EECS3311", "Software Design");
        Task lowWeight = academicTask("T1", course, 10);
        Task highWeight = academicTask("T2", course, 35);

        assertTrue(strategy.calculatePriority(highWeight)
                > strategy.calculatePriority(lowWeight));
    }

    @Test
    void generalTaskCanBeScoredWithoutAcademicData() {
        double score = strategy.calculatePriority(generalTask("T1", now.plusDays(3)));

        assertTrue(Double.isFinite(score));
        assertTrue(score > 0);
    }

    private GeneralTask generalTask(String id, LocalDateTime deadline) {
        return new GeneralTask(
                id, "General task", deadline, 4, 3, PriorityLevel.MEDIUM);
    }

    private AcademicTask academicTask(String id, Course course, double weight) {
        return new AcademicTask(
                id, "Academic task", now.plusDays(5), 4, 3,
                PriorityLevel.MEDIUM, course, weight, AcademicTaskType.ASSIGNMENT);
    }
}

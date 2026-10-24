package com.planpilot.domain;

import java.util.ArrayList;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

public class Schedule {
    private final String id;
    private final LocalDate startDate;
    private final LocalDate endDate;
    private final List<StudyBlock> studyBlocks = new ArrayList<>();
    private ScheduleStatus status;

    public Schedule(String id, LocalDate startDate, LocalDate endDate) {
        this.id = requireText(id);
        this.startDate = Objects.requireNonNull(startDate, "Start date cannot be null.");
        this.endDate = Objects.requireNonNull(endDate, "End date cannot be null.");
        if (endDate.isBefore(startDate)) {
            throw new IllegalArgumentException("End date cannot be before start date.");
        }
        this.status = ScheduleStatus.CURRENT;
    }

    public String getId() { return id; }
    public LocalDate getStartDate() { return startDate; }
    public LocalDate getEndDate() { return endDate; }
    public ScheduleStatus getStatus() { return status; }

    public void addStudyBlock(StudyBlock block) {
        studyBlocks.add(Objects.requireNonNull(block, "Study block cannot be null."));
    }

    public List<StudyBlock> getStudyBlocks() {
        return List.copyOf(studyBlocks);
    }

    public void markOutdated() {
        status = ScheduleStatus.OUTDATED;
    }

    private static String requireText(String value) {
        Objects.requireNonNull(value, "Schedule id cannot be null.");
        if (value.isBlank()) {
            throw new IllegalArgumentException("Schedule id cannot be blank.");
        }
        return value.trim();
    }
}

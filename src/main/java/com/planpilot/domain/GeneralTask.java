package com.planpilot.domain;

import java.time.LocalDateTime;
import java.util.UUID;

public class GeneralTask extends Task {
    public GeneralTask(String title, LocalDateTime deadline,
                       double estimatedHours, int difficulty,
                       PriorityLevel userImportance) {
        this(UUID.randomUUID().toString(), title, deadline, estimatedHours,
                difficulty, userImportance);
    }

    public GeneralTask(String id, String title, LocalDateTime deadline,
                       double estimatedHours, int difficulty,
                       PriorityLevel userImportance) {
        super(id, title, deadline, estimatedHours, difficulty, userImportance);
    }
}

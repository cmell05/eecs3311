package com.planpilot.domain;

import java.time.LocalDateTime;

public class GeneralTask extends Task {
    public GeneralTask(String id, String title, LocalDateTime deadline,
                       double estimatedHours, int difficulty,
                       PriorityLevel userImportance) {
        super(id, title, deadline, estimatedHours, difficulty, userImportance);
    }
}

package com.planpilot.service;

import com.planpilot.domain.Schedule;

public class ScheduleInfeasibleException extends IllegalStateException {
    private final Schedule partialSchedule;
    private final double workloadDeficitHours;

    public ScheduleInfeasibleException(
            Schedule partialSchedule, double workloadDeficitHours) {
        super("Schedule is infeasible; " + workloadDeficitHours
                + " hours of work could not be scheduled.");
        this.partialSchedule = partialSchedule;
        this.workloadDeficitHours = workloadDeficitHours;
    }

    public Schedule getPartialSchedule() {
        return partialSchedule;
    }

    public double getWorkloadDeficitHours() {
        return workloadDeficitHours;
    }
}

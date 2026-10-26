package com.planpilot.domain;

import java.util.Objects;

public class UserPreferences {
    private int maxSessionLength;
    private StudyPeriod preferredStudyPeriod;
    private int breakLength;

    public UserPreferences(int maxSessionLength, StudyPeriod preferredStudyPeriod,
                           int breakLength) {
        setMaxSessionLength(maxSessionLength);
        setPreferredStudyPeriod(preferredStudyPeriod);
        setBreakLength(breakLength);
    }

    public int getMaxSessionLength() { return maxSessionLength; }
    public StudyPeriod getPreferredStudyPeriod() { return preferredStudyPeriod; }
    public int getBreakLength() { return breakLength; }

    public void setMaxSessionLength(int maxSessionLength) {
        if (maxSessionLength <= 0) {
            throw new IllegalArgumentException("Maximum session length must be positive.");
        }
        this.maxSessionLength = maxSessionLength;
    }

    public void setPreferredStudyPeriod(StudyPeriod preferredStudyPeriod) {
        this.preferredStudyPeriod = Objects.requireNonNull(
                preferredStudyPeriod, "Preferred study period cannot be null.");
    }

    public void setBreakLength(int breakLength) {
        if (breakLength < 0) {
            throw new IllegalArgumentException("Break length cannot be negative.");
        }
        this.breakLength = breakLength;
    }
}

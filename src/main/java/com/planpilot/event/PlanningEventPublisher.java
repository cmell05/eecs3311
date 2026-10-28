package com.planpilot.event;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class PlanningEventPublisher {
    private final List<PlanningObserver> observers = new ArrayList<>();

    public void subscribe(PlanningObserver observer) {
        Objects.requireNonNull(observer, "Observer cannot be null.");
        if (!observers.contains(observer)) {
            observers.add(observer);
        }
    }

    public void unsubscribe(PlanningObserver observer) {
        Objects.requireNonNull(observer, "Observer cannot be null.");
        observers.remove(observer);
    }

    public void notifyObservers() {
        for (PlanningObserver observer : List.copyOf(observers)) {
            observer.onPlanningStateChanged();
        }
    }
}

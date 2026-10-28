package com.planpilot.event;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PlanningEventPublisherTest {
    @Test
    void subscribedObserverReceivesNotification() {
        PlanningEventPublisher publisher = new PlanningEventPublisher();
        int[] notifications = {0};
        publisher.subscribe(() -> notifications[0]++);

        publisher.notifyObservers();

        assertEquals(1, notifications[0]);
    }

    @Test
    void unsubscribedObserverNoLongerReceivesNotifications() {
        PlanningEventPublisher publisher = new PlanningEventPublisher();
        int[] notifications = {0};
        PlanningObserver observer = () -> notifications[0]++;
        publisher.subscribe(observer);
        publisher.unsubscribe(observer);

        publisher.notifyObservers();

        assertEquals(0, notifications[0]);
    }

    @Test
    void duplicateSubscriptionDoesNotDuplicateNotification() {
        PlanningEventPublisher publisher = new PlanningEventPublisher();
        int[] notifications = {0};
        PlanningObserver observer = () -> notifications[0]++;
        publisher.subscribe(observer);
        publisher.subscribe(observer);

        publisher.notifyObservers();

        assertEquals(1, notifications[0]);
    }
}

package com.planpilot.service;

import com.planpilot.domain.*;
import com.planpilot.event.PlanningEventPublisher;
import java.time.*;
import java.nio.file.Path;
import java.util.*;

/** Deterministic ledger: only the latest record for each session contributes effort. */
public class ProgressService {
    private static final double EPSILON = 0.000001;
    private final List<ProgressRecord> history = new ArrayList<>();
    private final Map<String, Double> baselines = new LinkedHashMap<>();
    private final Map<String, Double> originalEstimates = new LinkedHashMap<>();
    private final Map<String, StudyBlock> sessions = new LinkedHashMap<>();
    private final Set<String> archivedTasks = new HashSet<>();
    private final Map<String, Task> tasks = new LinkedHashMap<>();
    private final PlanningEventPublisher eventPublisher;
    private final Clock clock;
    private final Path journal;

    public ProgressService() { this(new PlanningEventPublisher()); }
    public ProgressService(PlanningEventPublisher publisher) { this(publisher, Clock.systemDefaultZone(), null); }
    public ProgressService(PlanningEventPublisher publisher, Clock clock, Path journal) {
        this.eventPublisher = Objects.requireNonNull(publisher);
        this.clock = Objects.requireNonNull(clock);
        this.journal = journal;
        if (journal != null) {
            ProgressJournal.load(journal, tasks, sessions, history, baselines, originalEstimates, archivedTasks);
            tasks.values().forEach(this::recalculate);
        }
    }

    public List<Task> getRestoredTasks() { return tasks.values().stream().filter(t -> !archivedTasks.contains(t.getId())).toList(); }

    public void archiveTask(String taskId) { archivedTasks.add(taskId); save(); }

    public void registerTasks(List<Task> currentTasks) {
        for (Task task : currentTasks) {
            tasks.put(task.getId(), task);
            baselines.putIfAbsent(task.getId(), task.getCompletedHours());
            originalEstimates.putIfAbsent(task.getId(), task.getEstimatedHours());
            sessions.values().stream().filter(b -> b.getTask().getId().equals(task.getId()))
                    .forEach(b -> b.rebindTask(task));
            recalculate(task);
        }
    }

    public void trackSessions(List<StudyBlock> blocks) {
        Set<String> incoming = new HashSet<>();
        blocks.forEach(b -> incoming.add(b.getId()));
        sessions.values().removeIf(b -> b.getProgressStatus() == ProgressStatus.PLANNED && !incoming.contains(b.getId()));
        for (StudyBlock block : blocks) {
            block.rebindTask(tasks.getOrDefault(block.getTask().getId(), block.getTask()));
            registerTasks(List.of(block.getTask()));
            sessions.put(block.getId(), block);
        }
        save();
    }

    public List<StudyBlock> getSessions() {
        return sessions.values().stream().sorted(Comparator.comparing(StudyBlock::getStart)
                .thenComparing(StudyBlock::getId)).toList();
    }

    public void recordProgress(StudyBlock block, ProgressStatus status, double hours) {
        record(block, status, hours, false);
    }

    private void record(StudyBlock block, ProgressStatus status, double hours, boolean inferred) {
        Objects.requireNonNull(block); Objects.requireNonNull(status);
        if (!Double.isFinite(hours) || hours < 0 || hours > block.durationHours())
            throw new IllegalArgumentException("Completed hours must be between zero and the session duration.");
        if (status == ProgressStatus.PLANNED
                || (status == ProgressStatus.COMPLETED && Math.abs(hours - block.durationHours()) > EPSILON)
                || (status == ProgressStatus.SKIPPED && hours != 0))
            throw new IllegalArgumentException("Hours do not match the selected progress status.");
        StudyBlock existing = sessions.get(block.getId());
        if (existing != null && (!existing.getTask().getId().equals(block.getTask().getId())
                || !existing.getStart().equals(block.getStart()) || !existing.getEnd().equals(block.getEnd()))) {
            throw new IllegalArgumentException("An existing session's task and time interval cannot change.");
        }
        Task task = tasks.getOrDefault(block.getTask().getId(), block.getTask());
        block.rebindTask(task);
        registerTasks(List.of(task));
        ProgressRecord previous = latest(block.getId());
        if (previous != null && previous.getStatus() == status && previous.getCompletedHours() == hours
                && previous.isInferred() == inferred) {
            applyStatus(block, status, inferred);
            sessions.put(block.getId(), block);
            save();
            return;
        }
        double baseline = baselines.getOrDefault(task.getId(), task.getCompletedHours());
        double total = baseline + latestRecords().values().stream()
                .filter(r -> r.getTask().getId().equals(task.getId()) && !r.getStudyBlock().getId().equals(block.getId()))
                .mapToDouble(ProgressRecord::getCompletedHours).sum() + hours;
        if (total > task.getEstimatedHours() + EPSILON)
            throw new IllegalArgumentException("Progress cannot exceed the task's estimated hours.");
        baselines.putIfAbsent(task.getId(), baseline);
        applyStatus(block, status, inferred);
        sessions.put(block.getId(), block);
        history.add(new ProgressRecord(UUID.randomUUID().toString(), task, block,
                LocalDateTime.now(clock), hours, status, inferred));
        recalculate(task);
        save();
        eventPublisher.notifyObservers();
    }

    private void applyStatus(StudyBlock block, ProgressStatus status, boolean inferred) {
        switch (status) {
            case COMPLETED -> block.markCompleted();
            case PARTIAL -> block.markPartial();
            case SKIPPED -> block.markSkipped();
            default -> throw new IllegalArgumentException("Select a recorded progress status.");
        }
        if (inferred) block.markInferred();
    }

    public void inferProgress(StudyBlock block) {
        Objects.requireNonNull(block);
        if (block.getEnd().isAfter(LocalDateTime.now(clock)))
            throw new IllegalArgumentException("Only elapsed sessions can have inferred progress.");
        if (latest(block.getId()) == null) record(block, ProgressStatus.COMPLETED, block.durationHours(), true);
    }

    /** Infer only elapsed, unreported sessions. Never override a user's correction. */
    public boolean inferPastSessions() {
        boolean changed = false;
        for (StudyBlock block : getSessions()) {
            if (latest(block.getId()) == null && !block.getEnd().isAfter(LocalDateTime.now(clock))) {
                double before = block.getTask().getCompletedHours();
                double hours = Math.min(block.durationHours(), block.getTask().getRemainingHours());
                record(block, hours == block.durationHours() ? ProgressStatus.COMPLETED : ProgressStatus.PARTIAL, hours, true);
                changed |= before != block.getTask().getCompletedHours();
            }
        }
        return changed;
    }

    public void updateRemainingEffort(Task task) {
        recalculate(task); save(); eventPublisher.notifyObservers();
    }

    private void recalculate(Task task) {
        double total = baselines.getOrDefault(task.getId(), task.getCompletedHours())
                + latestRecords().values().stream().filter(r -> r.getTask().getId().equals(task.getId()))
                .mapToDouble(ProgressRecord::getCompletedHours).sum();
        task.setCompletedHours(Math.min(task.getEstimatedHours(), total));
    }

    public double getConfirmedHours(Task task) {
        return baselines.getOrDefault(task.getId(), 0.0) + latestRecords().values().stream()
                .filter(r -> r.getTask().getId().equals(task.getId()) && !r.isInferred())
                .mapToDouble(ProgressRecord::getCompletedHours).sum();
    }
    public double getOriginalEstimate(Task task) { return originalEstimates.getOrDefault(task.getId(), task.getEstimatedHours()); }
    public double getInferredHours(Task task) {
        return latestRecords().values().stream().filter(r -> r.getTask().getId().equals(task.getId()) && r.isInferred())
                .mapToDouble(ProgressRecord::getCompletedHours).sum();
    }
    public List<ProgressRecord> getHistoryForTask(Task task) {
        return history.stream().filter(r -> r.getTask().getId().equals(task.getId())).toList();
    }
    public List<ProgressRecord> getHistory() { return List.copyOf(history); }
    private ProgressRecord latest(String id) { return latestRecords().get(id); }
    private Map<String, ProgressRecord> latestRecords() {
        Map<String, ProgressRecord> result = new LinkedHashMap<>();
        history.forEach(r -> result.put(r.getStudyBlock().getId(), r)); return result;
    }
    public void checkpoint() { save(); }

    private void save() {
        if (journal != null) ProgressJournal.save(journal, tasks, sessions, history, baselines, originalEstimates, archivedTasks);
    }
}

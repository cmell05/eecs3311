package com.planpilot.gui;

import com.planpilot.domain.ProgressStatus;
import com.planpilot.domain.StudyBlock;
import com.planpilot.domain.Task;
import com.planpilot.domain.TaskStatus;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Presentation groups only; changing a view never changes task or session state. */
final class PlanningViewGroups {
    private PlanningViewGroups() { }

    static Map<TaskStatus, List<Task>> tasksByStatus(List<Task> tasks) {
        Map<TaskStatus, List<Task>> groups = new LinkedHashMap<>();
        for (TaskStatus status : List.of(TaskStatus.IN_PROGRESS, TaskStatus.NOT_STARTED,
                TaskStatus.COMPLETED, TaskStatus.SKIPPED)) {
            groups.put(status, tasks.stream().filter(task -> task.getStatus() == status)
                    .sorted(Comparator.comparing(Task::getDeadline).thenComparing(Task::getTitle)
                            .thenComparing(Task::getId)).toList());
        }
        return groups;
    }

    static boolean needsConfirmation(StudyBlock block) {
        // A tentative completion must remain actionable until the user confirms or corrects it.
        return block.isInferred() || block.getProgressStatus() == ProgressStatus.PLANNED;
    }

    static List<StudyBlock> sessionsForView(List<StudyBlock> sessions, boolean history) {
        return sessions.stream().filter(block -> needsConfirmation(block) != history)
                .sorted(Comparator.comparing(StudyBlock::getStart).thenComparing(StudyBlock::getId)).toList();
    }

    static Map<String, List<StudyBlock>> sessionsByTask(List<StudyBlock> sessions) {
        Map<String, List<StudyBlock>> groups = new LinkedHashMap<>();
        sessions.stream().sorted(Comparator.comparing(StudyBlock::getStart).thenComparing(StudyBlock::getId))
                .map(block -> block.getTask().getId()).distinct()
                .forEach(id -> groups.put(id, sessions.stream().filter(block -> block.getTask().getId().equals(id))
                        .sorted(Comparator.comparing(StudyBlock::getStart).thenComparing(StudyBlock::getId)).toList()));
        return groups;
    }
}

package com.planpilot.service;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.planpilot.domain.*;
import java.io.IOException;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.util.*;

/** Local progress snapshots, including task/session identity needed to restore corrections. */
final class ProgressJournal {
    private static final ObjectMapper JSON = new ObjectMapper();
    private ProgressJournal() {}

    static void save(Path path, Map<String, Task> tasks, Map<String, StudyBlock> sessions,
                     List<ProgressRecord> history, Map<String, Double> baselines, Map<String, Double> originals, Set<String> archivedTasks) {
        ObjectNode root = JSON.createObjectNode(); root.put("version", 1);
        ArrayNode taskNodes = root.putArray("tasks");
        for (Task task : tasks.values()) {
            ObjectNode n = taskNodes.addObject();
            n.put("id", task.getId()); n.put("archived", archivedTasks.contains(task.getId())); n.put("title", task.getTitle()); n.put("deadline", task.getDeadline().toString());
            if (task.getDeferredUntil() != null) n.put("deferredUntil", task.getDeferredUntil().toString());
            n.put("estimate", task.getEstimatedHours()); n.put("difficulty", task.getDifficulty());
            n.put("importance", task.getUserImportance().name());
            n.put("baseline", baselines.getOrDefault(task.getId(), task.getCompletedHours()));
            n.put("original", originals.getOrDefault(task.getId(), task.getEstimatedHours()));
            if (task instanceof AcademicTask academic) {
                n.put("courseId", academic.getCourse().getId()); n.put("courseCode", academic.getCourse().getCode());
                n.put("courseName", academic.getCourse().getName()); n.put("weight", academic.getAssessmentWeight());
                n.put("type", academic.getTaskType().name());
            }
        }
        ArrayNode blockNodes = root.putArray("sessions");
        for (StudyBlock block : sessions.values()) {
            ObjectNode n = blockNodes.addObject(); n.put("id", block.getId()); n.put("taskId", block.getTask().getId());
            n.put("start", block.getStart().toString()); n.put("end", block.getEnd().toString());
            n.put("status", block.getProgressStatus().name()); n.put("inferred", block.isInferred());
        }
        ArrayNode records = root.putArray("records");
        for (ProgressRecord record : history) {
            ObjectNode n = records.addObject(); n.put("id", record.getId()); n.put("sessionId", record.getStudyBlock().getId());
            n.put("at", record.getRecordedAt().toString()); n.put("hours", record.getCompletedHours());
            n.put("status", record.getStatus().name()); n.put("inferred", record.isInferred());
        }
        Path temp = null;
        try {
            Path target = path.toAbsolutePath(); Files.createDirectories(target.getParent());
            temp = Files.createTempFile(target.getParent(), "progress-", ".tmp");
            JSON.writerWithDefaultPrettyPrinter().writeValue(temp.toFile(), root);
            try { Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException e) { Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING); }
        } catch (IOException e) { throw new IllegalStateException("Unable to save local progress history.", e); }
        finally { if (temp != null) try { Files.deleteIfExists(temp); } catch (IOException ignored) {} }
    }

    static void load(Path path, Map<String, Task> tasks, Map<String, StudyBlock> sessions,
                     List<ProgressRecord> history, Map<String, Double> baselines, Map<String, Double> originals, Set<String> archivedTasks) {
        if (!Files.exists(path)) return;
        try {
            JsonNode root = JSON.readTree(path.toFile());
            if (root.path("version").asInt() != 1) throw new IOException("Unsupported progress journal version");
            Map<String, Course> courses = new HashMap<>();
            for (JsonNode n : root.path("tasks")) {
                String id = n.get("id").asText(); String title = n.get("title").asText();
                LocalDateTime deadline = LocalDateTime.parse(n.get("deadline").asText());
                double hours = n.get("estimate").asDouble(); int difficulty = n.get("difficulty").asInt();
                PriorityLevel importance = PriorityLevel.valueOf(n.get("importance").asText());
                Task task;
                if (n.has("courseId")) {
                    Course course = courses.computeIfAbsent(n.get("courseId").asText(), key ->
                            new Course(key, n.get("courseCode").asText(), n.get("courseName").asText()));
                    task = new AcademicTask(id, title, deadline, hours, difficulty, importance, course,
                            n.get("weight").asDouble(), AcademicTaskType.valueOf(n.get("type").asText()));
                } else task = new GeneralTask(id, title, deadline, hours, difficulty, importance);
                if (n.hasNonNull("deferredUntil")) task.setDeferredUntil(LocalDateTime.parse(n.get("deferredUntil").asText()));
                if (n.path("archived").asBoolean()) archivedTasks.add(id);
                tasks.put(id, task); baselines.put(id, n.get("baseline").asDouble()); originals.put(id, n.get("original").asDouble());
            }
            for (JsonNode n : root.path("sessions")) {
                StudyBlock block = new StudyBlock(n.get("id").asText(), Objects.requireNonNull(tasks.get(n.get("taskId").asText())),
                        LocalDateTime.parse(n.get("start").asText()), LocalDateTime.parse(n.get("end").asText()));
                switch (ProgressStatus.valueOf(n.get("status").asText())) {
                    case COMPLETED -> block.markCompleted(); case PARTIAL -> block.markPartial();
                    case SKIPPED -> block.markSkipped(); default -> { }
                }
                if (n.path("inferred").asBoolean()) block.markInferred();
                sessions.put(block.getId(), block);
            }
            for (JsonNode n : root.path("records")) {
                StudyBlock block = Objects.requireNonNull(sessions.get(n.get("sessionId").asText()));
                history.add(new ProgressRecord(n.get("id").asText(), block.getTask(), block,
                        LocalDateTime.parse(n.get("at").asText()), n.get("hours").asDouble(),
                        ProgressStatus.valueOf(n.get("status").asText()), n.path("inferred").asBoolean()));
            }
        } catch (IOException | RuntimeException e) {
            throw new IllegalStateException("Unable to restore local progress history; the journal has been kept unchanged.", e);
        }
    }
}

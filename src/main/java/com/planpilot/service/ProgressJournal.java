package com.planpilot.service;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.planpilot.domain.*;
import java.io.IOException;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.LocalDate;
import java.util.*;

/** Local progress snapshots, including task/session identity needed to restore corrections. */
final class ProgressJournal {
    private static final ObjectMapper JSON = new ObjectMapper();
    private ProgressJournal() {}

    static void save(Path path, Map<String, Task> tasks, Map<String, StudyBlock> sessions,
                     List<ProgressRecord> history, Map<String, Double> baselines, Map<String, Double> originals, Set<String> archivedTasks, WorkspaceSnapshot workspace) {
        ObjectNode root = JSON.createObjectNode(); root.put("version", 2);
        ArrayNode taskNodes = root.putArray("tasks");
        for (Task task : tasks.values()) {
            ObjectNode n = taskNodes.addObject();
            n.put("id", task.getId()); n.put("archived", archivedTasks.contains(task.getId())); n.put("title", task.getTitle()); n.put("deadline", task.getDeadline().toString());
            if (task.getDeferredUntil() != null) n.put("deferredUntil", task.getDeferredUntil().toString());
            n.put("estimate", task.getEstimatedHours()); n.put("difficulty", task.getDifficulty());
            n.put("importance", task.getUserImportance().name()); n.put("status", task.getStatus().name());
            n.put("baseline", baselines.getOrDefault(task.getId(), task.getCompletedHours()));
            n.put("original", originals.getOrDefault(task.getId(), task.getEstimatedHours()));
            if (task instanceof AcademicTask academic) {
                n.put("courseId", academic.getCourse().getId()); n.put("courseCode", academic.getCourse().getCode());
                n.put("courseName", academic.getCourse().getName()); n.put("weight", academic.getAssessmentWeight());
                n.put("type", academic.getTaskType().name());
            }
        }
        ArrayNode blockNodes = root.putArray("sessions");
        // Schedule and progress must reference the same saved session identities, even during regeneration.
        Map<String, StudyBlock> savedSessions = new LinkedHashMap<>(sessions);
        if (workspace != null && workspace.schedule() != null)
            workspace.schedule().getStudyBlocks().forEach(block -> savedSessions.putIfAbsent(block.getId(), block));
        for (StudyBlock block : savedSessions.values()) {
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
        if (workspace != null) writeWorkspace(root.putObject("workspace"), workspace);
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

    static WorkspaceSnapshot load(Path path, Map<String, Task> tasks, Map<String, StudyBlock> sessions,
                     List<ProgressRecord> history, Map<String, Double> baselines, Map<String, Double> originals, Set<String> archivedTasks) {
        if (!Files.exists(path)) return null;
        try {
            JsonNode root = JSON.readTree(path.toFile());
            int version = root.path("version").asInt();
            if (version != 1 && version != 2) throw new IOException("Unsupported progress journal version");
            if (!root.path("tasks").isArray() || !root.path("sessions").isArray() || !root.path("records").isArray())
                throw new IOException("Incomplete progress journal");
            Map<String, Course> courses = new LinkedHashMap<>();
            if (root.has("workspace")) {
                for (JsonNode n : root.path("workspace").required("courses")) {
                    Course course = new Course(n.required("id").asText(), n.required("code").asText(), n.required("name").asText());
                    if (courses.putIfAbsent(course.getId(), course) != null) throw new IOException("Duplicate course identity");
                }
            }
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
                if (n.has("status") && TaskStatus.valueOf(n.get("status").asText()) == TaskStatus.SKIPPED) task.markSkipped();
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
            return root.has("workspace") ? readWorkspace(root.get("workspace"), courses, sessions) : null;
        } catch (IOException | RuntimeException e) {
            throw new IllegalStateException("Unable to restore local progress history; the journal has been kept unchanged.", e);
        }
    }

    private static void writeWorkspace(ObjectNode node, WorkspaceSnapshot workspace) {
        ArrayNode courses = node.putArray("courses");
        for (Course course : workspace.courses()) {
            ObjectNode n = courses.addObject();
            n.put("id", course.getId()); n.put("code", course.getCode()); n.put("name", course.getName());
        }
        ArrayNode availability = node.putArray("availability");
        for (AvailabilityBlock block : workspace.availability()) {
            ObjectNode n = availability.addObject(); n.put("id", block.getId());
            n.put("start", block.getStart().toString()); n.put("end", block.getEnd().toString());
            n.put("type", block.getType().name());
        }
        UserPreferences preferences = Objects.requireNonNull(workspace.preferences());
        ObjectNode prefs = node.putObject("preferences");
        prefs.put("maxSessionLength", preferences.getMaxSessionLength());
        prefs.put("breakLength", preferences.getBreakLength());
        prefs.put("preferredStudyPeriod", preferences.getPreferredStudyPeriod().name());
        if (workspace.schedule() != null) {
            Schedule schedule = workspace.schedule();
            ObjectNode n = node.putObject("schedule");
            n.put("id", schedule.getId()); n.put("start", schedule.getStartDate().toString());
            n.put("end", schedule.getEndDate().toString()); n.put("status", schedule.getStatus().name());
            schedule.getDecisionEvidence().ifPresent(evidence -> n.set("decisionEvidence", JSON.valueToTree(evidence)));
            ArrayNode ids = n.putArray("sessionIds");
            schedule.getStudyBlocks().forEach(block -> ids.add(block.getId()));
        }
        node.put("workloadDeficitHours", workspace.workloadDeficitHours());
        node.put("infeasibilityReason", workspace.infeasibilityReason());
    }

    private static WorkspaceSnapshot readWorkspace(JsonNode node, Map<String, Course> knownCourses,
                                                    Map<String, StudyBlock> sessions) throws IOException {
        List<Course> courses = new ArrayList<>();
        for (JsonNode n : node.required("courses")) courses.add(Objects.requireNonNull(knownCourses.get(n.required("id").asText())));
        List<AvailabilityBlock> availability = new ArrayList<>();
        for (JsonNode n : node.required("availability")) {
            availability.add(new AvailabilityBlock(n.required("id").asText(),
                    LocalDateTime.parse(n.required("start").asText()), LocalDateTime.parse(n.required("end").asText()),
                    AvailabilityType.valueOf(n.required("type").asText())));
        }
        JsonNode prefs = node.required("preferences");
        UserPreferences preferences = new UserPreferences(prefs.required("maxSessionLength").asInt(),
                StudyPeriod.valueOf(prefs.required("preferredStudyPeriod").asText()), prefs.required("breakLength").asInt());
        Schedule schedule = null;
        if (node.hasNonNull("schedule")) {
            JsonNode n = node.get("schedule");
            schedule = new Schedule(n.required("id").asText(), LocalDate.parse(n.required("start").asText()),
                    LocalDate.parse(n.required("end").asText()));
            if (n.hasNonNull("decisionEvidence"))
                schedule.setDecisionEvidence(JSON.treeToValue(n.get("decisionEvidence"), SchedulingEvidence.class));
            Set<String> ids = new HashSet<>();
            for (JsonNode id : n.required("sessionIds")) {
                if (!ids.add(id.asText())) throw new IllegalArgumentException("Duplicate schedule session");
                schedule.addStudyBlock(Objects.requireNonNull(sessions.get(id.asText()), "Missing saved schedule session"));
            }
            if (ScheduleStatus.valueOf(n.required("status").asText()) == ScheduleStatus.OUTDATED) schedule.markOutdated();
        }
        return new WorkspaceSnapshot(courses, availability, preferences, schedule,
                node.required("workloadDeficitHours").asDouble(), node.path("infeasibilityReason").asText(""));
    }

}

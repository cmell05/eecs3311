package com.planpilot.ai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.planpilot.domain.AcademicTask;
import com.planpilot.domain.AvailabilityBlock;
import com.planpilot.domain.ProgressStatus;
import com.planpilot.domain.ProgressRecord;
import com.planpilot.domain.Schedule;
import com.planpilot.domain.PriorityAssessment;
import com.planpilot.domain.StudyBlock;
import com.planpilot.domain.Task;
import com.planpilot.domain.UserPreferences;
import com.planpilot.service.ReplanOption;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.OptionalDouble;

public final class PlanningAgent {
    private static final double MAX_SUGGESTED_HOURS = 1000.0;

    private final LLMClient llmClient;
    private final ObjectMapper objectMapper;
    private final TaskParser taskParser;
    private final Clock clock;

    public PlanningAgent(LLMClient llmClient) {
        this(llmClient, new ObjectMapper(), Clock.systemDefaultZone());
    }

    PlanningAgent(LLMClient llmClient, ObjectMapper objectMapper) {
        this(llmClient, objectMapper, Clock.systemDefaultZone());
    }

    PlanningAgent(
            LLMClient llmClient, ObjectMapper objectMapper, Clock clock) {
        this.llmClient = Objects.requireNonNull(
                llmClient, "LLM client cannot be null.");
        this.objectMapper = Objects.requireNonNull(
                objectMapper, "Object mapper cannot be null.");
        this.taskParser = new TaskParser(objectMapper);
        this.clock = Objects.requireNonNull(clock, "Clock cannot be null.");
    }

    public double suggestEffort(
            Task task, OptionalDouble historicalAverage, int sampleCount) {
        Objects.requireNonNull(task, "Task cannot be null.");
        Objects.requireNonNull(
                historicalAverage, "Historical average cannot be null.");
        if (sampleCount < 0
                || (historicalAverage.isPresent() && sampleCount == 0)) {
            throw new IllegalArgumentException(
                    "Historical sample count is inconsistent.");
        }

        String response = llmClient.generateText(
                systemInstruction(), createEffortPrompt(
                        task, historicalAverage, sampleCount));
        return parseSuggestedHours(response);
    }

    public ParsedTaskData parseTask(String text) {
        requireText(text, "Task description");
        String response = llmClient.generateText(
                taskParsingSystemInstruction(), createTaskParsingPrompt(text));
        return taskParser.parse(response);
    }

    public String explainSchedule(
            String question,
            Schedule schedule,
            List<AvailabilityBlock> availability) {
        return explainSchedule(question, schedule, availability, progressContext(null), null);
    }

    public String explainSchedule(
            String question,
            Schedule schedule,
            List<AvailabilityBlock> availability,
            List<ProgressRecord> history) {
        return explainSchedule(question, schedule, availability, progressContext(
                        Objects.requireNonNull(history, "Progress history cannot be null.")), null);
    }

    public String explainSchedule(
            String question, Schedule schedule, List<AvailabilityBlock> availability,
            List<ProgressRecord> history, PriorityAssessment currentPriorities) {
        return explainSchedule(question, schedule, availability,
                progressContext(Objects.requireNonNull(history, "Progress history cannot be null.")),
                Objects.requireNonNull(currentPriorities, "Current priorities cannot be null."));
    }

    private String explainSchedule(
            String question,
            Schedule schedule,
            List<AvailabilityBlock> availability,
            ProgressContext progress, PriorityAssessment currentPriorities) {
        requireText(question, "Schedule question");
        Objects.requireNonNull(schedule, "Schedule cannot be null.");
        Objects.requireNonNull(availability, "Availability cannot be null.");
        availability.forEach(block -> Objects.requireNonNull(
                block, "Availability cannot contain null blocks."));

        String context = createScheduleExplanationPrompt(question, schedule, availability, progress, currentPriorities);
        String response = requireExplanation(llmClient.generateText(scheduleExplanationSystemInstruction(), context));
        java.util.Set<String> fieldNames = explanationFieldNames(context);
        if (exposesImplementationDetails(response, fieldNames)) {
            // One bounded rewrite using the same facts; never substitute an invented explanation.
            response = requireExplanation(llmClient.generateText(scheduleExplanationSystemInstruction(),
                    context + "\nRewrite this draft directly for a student, using only the facts above. "
                    + "Remove implementation terminology, field names, raw data and boolean flags. "
                    + "Keep supported task names, dates, comparisons and advice; do not describe the rewrite.\nDraft:\n" + response));
            if (exposesImplementationDetails(response, fieldNames))
                throw new LLMClientException("The explanation could not be presented clearly. Please try again.");
        }
        return response;
    }

    private String requireExplanation(String response) {
        if (response == null || response.isBlank())
            throw new LLMClientException("The LLM returned an empty schedule explanation.");
        return response.trim();
    }

    private java.util.Set<String> explanationFieldNames(String context) {
        var names = new java.util.HashSet<String>();
        try { collectExplanationFieldNames(objectMapper.readTree(context), names); }
        catch (JsonProcessingException failure) { throw new LLMClientException("Unable to prepare the schedule explanation.", failure); }
        return names;
    }

    private void collectExplanationFieldNames(JsonNode node, java.util.Set<String> names) {
        if (node.isObject()) node.fields().forEachRemaining(field -> {
            if (field.getKey().matches("[a-z]+[A-Z].*")) names.add(field.getKey());
            collectExplanationFieldNames(field.getValue(), names);
        });
        else if (node.isArray()) node.forEach(child -> collectExplanationFieldNames(child, names));
    }

    private boolean exposesImplementationDetails(String response, java.util.Set<String> fieldNames) {
        boolean containsInternalField = fieldNames.stream().anyMatch(name -> java.util.regex.Pattern
                .compile("(?i)\\b" + java.util.regex.Pattern.quote(name) + "\\b").matcher(response).find());
        return containsInternalField || response.stripLeading().startsWith("{")
                || response.stripLeading().startsWith("```json") || java.util.regex.Pattern.compile(
                "(?i)\\b(?:decisionEvidenceAvailable|generationDecisionEvidence|generationEvidenceMayBeOutdated|"
                + "progressDetailsAvailable|taskFieldsReflectCurrentState|upcomingPlannedHours|currentlyPlannedHours|"
                + "allocationRank|priorityRank|priorityScore|scoreContributions|hoursUntilDeadline|"
                + "currentPriorityAssessment|confirmedCompletedHours|inferredCompletedHours|recordedHours|"
                + "remainingPlannedHours|sessionCategory|SchedulePlanner|PlanPilotController|BalancedPriorityStrategy|"
                + "boolean flags?|backend (?:logic|fields?|details|context))\\b|\\b(?:flag|field)\\s+(?:is|=|:)\\s*(?:true|false)\\b")
                .matcher(response).find();
    }

    public RecoveryPlan suggestRecovery(
            double workloadDeficitHours,
            Schedule partialSchedule,
            List<Task> openTasks,
            List<AvailabilityBlock> availability) {
        return suggestRecovery(workloadDeficitHours, partialSchedule, openTasks, availability, progressContext(null));
    }

    public RecoveryPlan suggestRecovery(
            double workloadDeficitHours,
            Schedule partialSchedule,
            List<Task> openTasks,
            List<AvailabilityBlock> availability,
            List<ProgressRecord> history) {
        return suggestRecovery(workloadDeficitHours, partialSchedule, openTasks, availability, progressContext(
                        Objects.requireNonNull(history, "Progress history cannot be null.")));
    }

    private RecoveryPlan suggestRecovery(
            double workloadDeficitHours,
            Schedule partialSchedule,
            List<Task> openTasks,
            List<AvailabilityBlock> availability,
            ProgressContext progress) {
        if (!Double.isFinite(workloadDeficitHours)
                || workloadDeficitHours <= 0) {
            throw new IllegalArgumentException(
                    "Workload deficit must be a positive finite value.");
        }
        Objects.requireNonNull(partialSchedule,
                "Partial schedule cannot be null.");
        requireNonNullElements(openTasks, "Open tasks");
        requireNonNullElements(availability, "Availability");

        String response = llmClient.generateText(
                recoverySystemInstruction(),
                createRecoveryPrompt(
                        workloadDeficitHours, partialSchedule,
                        openTasks, availability, progress));
        return parseRecoveryPlan(response);
    }

    public ReplanRecommendation suggestReplanningOption(
            Schedule schedule,
            List<Task> openTasks,
            List<AvailabilityBlock> availability,
            UserPreferences preferences) {
        return suggestReplanningOption(schedule, openTasks, availability, preferences, progressContext(null));
    }

    public ReplanRecommendation suggestReplanningOption(
            Schedule schedule,
            List<Task> openTasks,
            List<AvailabilityBlock> availability,
            UserPreferences preferences,
            List<ProgressRecord> history) {
        return suggestReplanningOption(schedule, openTasks, availability, preferences, progressContext(
                        Objects.requireNonNull(history, "Progress history cannot be null.")));
    }

    private ReplanRecommendation suggestReplanningOption(
            Schedule schedule,
            List<Task> openTasks,
            List<AvailabilityBlock> availability,
            UserPreferences preferences,
            ProgressContext progress) {
        Objects.requireNonNull(schedule, "Schedule cannot be null.");
        requireNonNullElements(openTasks, "Open tasks");
        requireNonNullElements(availability, "Availability");
        Objects.requireNonNull(preferences,
                "User preferences cannot be null.");

        String response = llmClient.generateText(
                replanningSystemInstruction(),
                createReplanningPrompt(
                        schedule, openTasks, availability, preferences, progress));
        return parseReplanRecommendation(response);
    }

    private String taskParsingSystemInstruction() {
        return "Convert a task description into a proposed task. Use the "
                + "provided reference time to resolve relative dates. Return "
                + "exactly one JSON object with required fields title, deadline "
                + "(ISO local date-time), and estimatedHours. Optional fields: "
                + "difficulty (1-5), importance (LOW, MEDIUM, HIGH), taskKind "
                + "(ACADEMIC or GENERAL), courseCode, assessmentWeight (0-100), "
                + "and academicTaskType (ASSIGNMENT, EXAM, QUIZ, PROJECT, "
                + "READING, STUDY). Use null for unknown optional academic "
                + "fields. General tasks must use null for all academic fields. "
                + "Do not include Markdown or commentary.";
    }

    private String createTaskParsingPrompt(String text) {
        ObjectNode prompt = objectMapper.createObjectNode();
        prompt.put("referenceDateTime", LocalDateTime.now(clock).toString());
        prompt.put("taskDescription", text.trim());
        try {
            return objectMapper.writeValueAsString(prompt);
        } catch (JsonProcessingException exception) {
            throw new LLMClientException(
                    "Unable to create the task parsing request.", exception);
        }
    }

    private String scheduleExplanationSystemInstruction() {
        return "Explain the scheduling decision using only the supplied JSON "
                + "facts. Do not invent tasks, deadlines, priorities, "
                + "availability, or study blocks. If the context cannot answer "
                + "the question, say that the information is unavailable. "
                + "The explanation is read-only: do not claim to modify or "
                + "regenerate the schedule. Keep the answer concise." + decisionEvidenceInstruction()
                + progressAccountingInstruction() + studentExplanationInstruction();
    }

    private String recoverySystemInstruction() {
        return "Explain an infeasible study workload using only the supplied "
                + "JSON facts and recommend 1 to 4 distinct recovery actions. Deferred tasks stay unfinished with unchanged deadlines; explicit user approval is required to defer them. "
                + "Return exactly one JSON object with a nonblank summary and "
                + "a suggestions array. Each suggestion must contain action "
                + "and explanation. Allowed actions are "
                + "EXTEND_PLANNING_WINDOW, ADD_AVAILABILITY, "
                + "DEFER_LOWER_PRIORITY_TASK, and KEEP_CURRENT_CONSTRAINTS. "
                + "Never claim to apply an action, change a deadline, delete "
                + "work, or regenerate the schedule. The user must choose." + decisionEvidenceInstruction() + progressAccountingInstruction();
    }

    private String replanningSystemInstruction() {
        return "Summarize the impact of the supplied planning state and "
                + "recommend exactly one existing replanning option. Return "
                + "one JSON object with impactSummary, recommendedOption, and "
                + "rationale. recommendedOption must be one of "
                + "REPLAN_ENTIRE_WINDOW, RESCHEDULE_INCOMPLETE_WORK, "
                + "EXTEND_PLANNING_WINDOW, or KEEP_CURRENT_SCHEDULE. Use only "
                + "the supplied facts. Do not claim to apply the option, alter "
                + "tasks, or regenerate the schedule. The user decides whether "
                + "to proceed." + decisionEvidenceInstruction() + progressAccountingInstruction();
    }

    private String createReplanningPrompt(
            Schedule schedule,
            List<Task> openTasks,
            List<AvailabilityBlock> availability,
            UserPreferences preferences,
            ProgressContext progress) {
        ObjectNode root = objectMapper.createObjectNode();
        addScheduleContext(root.putObject("currentSchedule"), schedule, progress);
        var taskNodes = root.putArray("openTasks");
        for (Task task : openTasks) {
            addTaskContext(taskNodes.addObject(), task, progress);
        }
        root.put("totalRemainingEffortHours", openTasks.stream()
                .mapToDouble(Task::getRemainingHours)
                .sum());
        root.put("currentlyPlannedHours", schedule.getStudyBlocks().stream()
                .mapToDouble(block -> futurePlannedHours(block, progress.referenceTime()))
                .sum());
        addAvailabilityContext(root, availability);
        ObjectNode preferencesNode = root.putObject("userPreferences");
        preferencesNode.put("maxSessionLengthMinutes",
                preferences.getMaxSessionLength());
        preferencesNode.put("preferredStudyPeriod",
                preferences.getPreferredStudyPeriod().name());
        preferencesNode.put("breakLengthMinutes",
                preferences.getBreakLength());
        return writeJson(root, "replanning recommendation request");
    }

    private ReplanRecommendation parseReplanRecommendation(String response) {
        Objects.requireNonNull(response, "LLM response cannot be null.");
        if (response.isBlank()) {
            throw invalidReplanningResponse(null);
        }
        try {
            JsonNode root = objectMapper.readTree(response.trim());
            if (root == null
                    || !root.isObject()
                    || !root.path("impactSummary").isTextual()
                    || !root.path("recommendedOption").isTextual()
                    || !root.path("rationale").isTextual()) {
                throw invalidReplanningResponse(null);
            }
            return new ReplanRecommendation(
                    root.path("impactSummary").asText(),
                    ReplanOption.valueOf(
                            root.path("recommendedOption").asText()),
                    root.path("rationale").asText());
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            throw invalidReplanningResponse(exception);
        }
    }

    private LLMClientException invalidReplanningResponse(Throwable cause) {
        String message = "The LLM returned an invalid replanning recommendation.";
        return cause == null
                ? new LLMClientException(message)
                : new LLMClientException(message, cause);
    }

    private String createRecoveryPrompt(
            double workloadDeficitHours,
            Schedule partialSchedule,
            List<Task> openTasks,
            List<AvailabilityBlock> availability,
            ProgressContext progress) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("workloadDeficitHours", workloadDeficitHours);
        addScheduleContext(
                root.putObject("partialSchedule"), partialSchedule, progress);
        var taskNodes = root.putArray("openTasks");
        for (Task task : openTasks) {
            addTaskContext(taskNodes.addObject(), task, progress);
        }
        addAvailabilityContext(root, availability);
        return writeJson(root, "recovery suggestion request");
    }

    private RecoveryPlan parseRecoveryPlan(String response) {
        Objects.requireNonNull(response, "LLM response cannot be null.");
        if (response.isBlank()) {
            throw invalidRecoveryResponse(null);
        }
        try {
            JsonNode root = objectMapper.readTree(response.trim());
            if (root == null || !root.isObject()) {
                throw invalidRecoveryResponse(null);
            }
            JsonNode summaryNode = root.path("summary");
            JsonNode suggestionsNode = root.path("suggestions");
            if (!summaryNode.isTextual()
                    || summaryNode.asText().isBlank()
                    || !suggestionsNode.isArray()) {
                throw invalidRecoveryResponse(null);
            }
            List<RecoverySuggestion> suggestions = new ArrayList<>();
            for (JsonNode suggestionNode : suggestionsNode) {
                if (!suggestionNode.isObject()
                        || !suggestionNode.path("action").isTextual()
                        || !suggestionNode.path("explanation").isTextual()) {
                    throw invalidRecoveryResponse(null);
                }
                RecoveryAction action = RecoveryAction.valueOf(
                        suggestionNode.path("action").asText());
                suggestions.add(new RecoverySuggestion(
                        action,
                        suggestionNode.path("explanation").asText()));
            }
            return new RecoveryPlan(summaryNode.asText(), suggestions);
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            throw invalidRecoveryResponse(exception);
        }
    }

    private LLMClientException invalidRecoveryResponse(Throwable cause) {
        String message = "The LLM returned invalid recovery suggestions.";
        return cause == null
                ? new LLMClientException(message)
                : new LLMClientException(message, cause);
    }

    private String createScheduleExplanationPrompt(
            String question,
            Schedule schedule,
            List<AvailabilityBlock> availability,
            ProgressContext progress, PriorityAssessment currentPriorities) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("question", question.trim());
        if (currentPriorities != null) root.set("currentPriorityAssessment", objectMapper.valueToTree(currentPriorities));
        addScheduleContext(root.putObject("schedule"), schedule, progress);
        addAvailabilityContext(root, availability);
        return writeJson(root, "schedule explanation request");
    }

    private void addScheduleContext(ObjectNode node, Schedule schedule, ProgressContext progress) {
        node.put("id", schedule.getId());
        node.put("startDate", schedule.getStartDate().toString());
        node.put("endDate", schedule.getEndDate().toString());
        node.put("status", schedule.getStatus().name());
        node.put("decisionEvidenceAvailable", schedule.getDecisionEvidence().isPresent());
        node.put("generationEvidenceMayBeOutdated", schedule.getStatus() == com.planpilot.domain.ScheduleStatus.OUTDATED);
        node.put("taskFieldsReflectCurrentState", true);
        schedule.getDecisionEvidence().ifPresent(evidence -> node.set("generationDecisionEvidence", objectMapper.valueToTree(evidence)));
        node.put("referenceDateTime", progress.referenceTime().toString());
        node.put("progressDetailsAvailable", progress.detailsAvailable());
        node.put("upcomingPlannedHours", schedule.getStudyBlocks().stream()
                .mapToDouble(block -> futurePlannedHours(block, progress.referenceTime())).sum());
        node.put("sessionsAwaitingConfirmation", schedule.getStudyBlocks().stream().filter(StudyBlock::isInferred).count());
        var studyBlocks = node.putArray("studyBlocks");
        for (StudyBlock block : schedule.getStudyBlocks()) {
            ObjectNode blockNode = studyBlocks.addObject();
            blockNode.put("id", block.getId());
            blockNode.put("start", block.getStart().toString());
            blockNode.put("end", block.getEnd().toString());
            blockNode.put("durationHours", block.durationHours());
            blockNode.put("progressStatus",
                    block.getProgressStatus().name());
            blockNode.put("isInferred", block.isInferred());
            blockNode.put("requiresConfirmation", block.isInferred());
            blockNode.put("sessionCategory", sessionCategory(block, progress.referenceTime()));
            blockNode.put("remainingPlannedHours", futurePlannedHours(block, progress.referenceTime()));
            ProgressRecord latest = progress.latest().get(block.getId());
            if (latest != null) {
                blockNode.put("recordedHours", latest.getCompletedHours());
                blockNode.put("confirmedCompletedHours", latest.isInferred() ? 0 : latest.getCompletedHours());
                blockNode.put("inferredCompletedHours", latest.isInferred() ? latest.getCompletedHours() : 0);
            }
            addTaskContext(blockNode.putObject("task"), block.getTask(), progress);
        }
    }

    private void addAvailabilityContext(
            ObjectNode root, List<AvailabilityBlock> availability) {
        var availabilityNodes = root.putArray("availability");
        for (AvailabilityBlock block : availability) {
            ObjectNode blockNode = availabilityNodes.addObject();
            blockNode.put("id", block.getId());
            blockNode.put("start", block.getStart().toString());
            blockNode.put("end", block.getEnd().toString());
            blockNode.put("type", block.getType().name());
        }
    }

    private String writeJson(ObjectNode root, String requestName) {
        try {
            return objectMapper.writeValueAsString(root);
        } catch (JsonProcessingException exception) {
            throw new LLMClientException(
                    "Unable to create the " + requestName + ".",
                    exception);
        }
    }

    private void addTaskContext(ObjectNode node, Task task, ProgressContext progress) {
        node.put("id", task.getId());
        node.put("title", task.getTitle());
        node.put("kind",
                task instanceof AcademicTask ? "ACADEMIC" : "GENERAL");
        node.put("deadline", task.getDeadline().toString());
        node.put("estimatedHours", task.getEstimatedHours());
        node.put("completedHours", task.getCompletedHours());
        node.put("progressDetailsAvailable", progress.detailsAvailable());
        if (progress.detailsAvailable()) {
            double inferred = progress.latest().values().stream()
                    .filter(record -> record.getTask().getId().equals(task.getId()) && record.isInferred())
                    .mapToDouble(ProgressRecord::getCompletedHours).sum();
            // completedHours includes the confirmed baseline plus the latest outcome of each session.
            node.put("inferredCompletedHours", inferred);
            node.put("confirmedCompletedHours", Math.max(0, task.getCompletedHours() - inferred));
        }
        node.put("remainingHours", task.getRemainingHours());
        node.put("difficulty", task.getDifficulty());
        node.put("importance", task.getUserImportance().name());
        node.put("status", task.getStatus().name());
        if (task.getDeferredUntil() != null) node.put("deferredUntil", task.getDeferredUntil().toString());
        if (task instanceof AcademicTask academicTask) {
            node.put("courseCode", academicTask.getCourse().getCode());
            node.put("courseName", academicTask.getCourse().getName());
            node.put("assessmentWeight",
                    academicTask.getAssessmentWeight());
            node.put("academicTaskType",
                    academicTask.getTaskType().name());
        }
    }

    private record ProgressContext(LocalDateTime referenceTime,
                                   java.util.Map<String, ProgressRecord> latest,
                                   boolean detailsAvailable) { }

    private ProgressContext progressContext(List<ProgressRecord> history) {
        var latest = new java.util.LinkedHashMap<String, ProgressRecord>();
        if (history != null) {
            // Ledger order, rather than timestamps, also handles corrections recorded at the same instant.
            for (ProgressRecord record : history) {
                Objects.requireNonNull(record, "Progress history cannot contain null records.");
                latest.put(record.getStudyBlock().getId(), record);
            }
        }
        return new ProgressContext(LocalDateTime.now(clock), latest, history != null);
    }

    private double futurePlannedHours(StudyBlock block, LocalDateTime now) {
        if (block.getProgressStatus() != ProgressStatus.PLANNED || !block.getEnd().isAfter(now)) return 0;
        LocalDateTime remainingStart = block.getStart().isAfter(now) ? block.getStart() : now;
        return java.time.Duration.between(remainingStart, block.getEnd()).toSeconds() / 3600.0;
    }

    private String sessionCategory(StudyBlock block, LocalDateTime now) {
        if (block.getProgressStatus() != ProgressStatus.PLANNED) return "REPORTED_HISTORY";
        if (!block.getEnd().isAfter(now)) return "AWAITING_INFERENCE";
        return block.getStart().isAfter(now) ? "UPCOMING" : "IN_PROGRESS";
    }

    private String decisionEvidenceInstruction() {
        return " Use generationDecisionEvidence to explain the actual algorithm and compare factor contributions. "
                + "User importance (LOW/MEDIUM/HIGH) is only one factor, not the calculated priority score. "
                + "Use allocationRank for allocation order and placement timestamps for chronological order; they can differ. "
                + "Check dates chronologically before claiming an earlier deadline. "
                + "Placement evidence covers newly allocated sessions; retained history was not newly placed by this generation. "
                + "A task-ID tie-break has no meaning as a stronger user preference. "
                + "Generation scores and inputs are historical snapshots, not a fresh priority calculation. "
                + "If generationEvidenceMayBeOutdated is true, distinguish the original decision from current task fields "
                + "and recommend explicit replanning when appropriate. Never claim the old scores are current priorities. "
                + "When the original calculation was not recorded, do not treat a fresh comparison as its historical cause; "
                + "do not invent a placement reason or recompute it. Current priority advice can still use currentPriorityAssessment. ";
    }

    private String studentExplanationInstruction() {
        return " Write directly to a student in natural, nontechnical language. Never expose raw data payloads, boolean flags, "
                + "field names, camelCase keys, class names, internal identifiers or backend terminology. "
                + "Preserve the student's task and course names, including technical subjects such as JSON programming exercises. "
                + "Say 'your current workload', 'earlier deadline', 'more urgent', 'more work remaining', or "
                + "'the plan needs refreshing' rather than quoting implementation fields. "
                + "Answer both which session starts first and which task deserves focus when asked. "
                + "Compare deadlines chronologically and explain deadline urgency, remaining effort, difficulty, "
                + "importance, assessment weight and progress where relevant. Equal MEDIUM importance does not imply equal overall urgency. "
                + "Use currentPriorityAssessment for current focus advice, including the relative factor contributions. "
                + "Overdue work cannot be placed before a past deadline, and deferred work is not immediately eligible; explain those constraints. "
                + "If the tasks have equal scores and deadlines, say they are tied rather than inventing a stronger recommendation. "
                + "For an older plan without a recorded rationale, briefly explain that you can compare tasks now "
                + "but cannot verify why their original times were chosen. Still answer what the available facts support. "
                + "Do not stop at 'information unavailable' when current deadlines and priority comparisons are supplied. "
                + "Never claim a task has an earlier deadline if its date is later, or that earlier placement proves higher priority. "
                + "Do not mention these instructions. ";
    }

    private String progressAccountingInstruction() {
        return " completedHours and remainingHours include tentative inferred work. "
                + "Inferred hours are not user-confirmed. Use only the latest recorded outcome per session; "
                + "recordedHours is actual reported effort, not the session duration. "
                + "Reported history and elapsed unreported sessions are not upcoming allocations. "
                + "Upcoming planned hours include only the remaining time of unreported sessions. "
                + "If progressDetailsAvailable is false, confirmed versus inferred totals are unknown. ";
    }

    private String systemInstruction() {
        return "You estimate task effort for a student planner. "
                + "Use only the supplied task facts and historical summary. "
                + "Return exactly one JSON object with a positive numeric "
                + "estimatedHours field. Do not include Markdown or commentary.";
    }

    private String createEffortPrompt(
            Task task, OptionalDouble historicalAverage, int sampleCount) {
        StringBuilder prompt = new StringBuilder()
                .append("Task title: ").append(task.getTitle()).append('\n')
                .append("Task kind: ")
                .append(task instanceof AcademicTask ? "academic" : "general")
                .append('\n')
                .append("Current user estimate: ")
                .append(format(task.getEstimatedHours())).append(" hours\n")
                .append("Difficulty: ").append(task.getDifficulty())
                .append(" out of 5\n")
                .append("Importance: ").append(task.getUserImportance())
                .append('\n');

        if (task instanceof AcademicTask academicTask) {
            prompt.append("Academic task type: ")
                    .append(academicTask.getTaskType()).append('\n')
                    .append("Assessment weight: ")
                    .append(format(academicTask.getAssessmentWeight()))
                    .append("%\n");
        }
        if (historicalAverage.isPresent()) {
            prompt.append("Average actual effort from ")
                    .append(sampleCount)
                    .append(" similar completed task(s): ")
                    .append(format(historicalAverage.getAsDouble()))
                    .append(" hours\n");
        } else {
            prompt.append("Similar completed-task history: unavailable\n");
        }
        prompt.append("Recommend a realistic total effort estimate.");
        return prompt.toString();
    }

    private double parseSuggestedHours(String response) {
        Objects.requireNonNull(response, "LLM response cannot be null.");
        if (response.isBlank()) {
            throw invalidResponse();
        }
        try {
            JsonNode root = objectMapper.readTree(response.trim());
            if (root == null || !root.isObject()) {
                throw invalidResponse();
            }
            JsonNode value = root.path("estimatedHours");
            if (!value.isNumber()) {
                throw invalidResponse();
            }
            double hours = value.asDouble();
            if (!Double.isFinite(hours)
                    || hours <= 0
                    || hours > MAX_SUGGESTED_HOURS) {
                throw invalidResponse();
            }
            return hours;
        } catch (JsonProcessingException exception) {
            throw new LLMClientException(
                    "The LLM returned an invalid effort suggestion.", exception);
        }
    }

    private LLMClientException invalidResponse() {
        return new LLMClientException(
                "The LLM returned an invalid effort suggestion.");
    }

    private String format(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }

    private void requireText(String value, String fieldName) {
        Objects.requireNonNull(value, fieldName + " cannot be null.");
        if (value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " cannot be blank.");
        }
    }

    private <T> void requireNonNullElements(
            List<T> values, String fieldName) {
        Objects.requireNonNull(values, fieldName + " cannot be null.");
        values.forEach(value -> Objects.requireNonNull(
                value, fieldName + " cannot contain null values."));
    }
}

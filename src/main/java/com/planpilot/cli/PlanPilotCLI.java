package com.planpilot.cli;

import com.planpilot.ai.ParsedTaskData;
import com.planpilot.ai.ParsedTaskKind;
import com.planpilot.ai.RecoveryPlan;
import com.planpilot.ai.RecoverySuggestion;
import com.planpilot.ai.ReplanRecommendation;
import com.planpilot.config.PlanPilotApplicationFactory;
import com.planpilot.controller.PlanPilotController;
import com.planpilot.domain.AcademicTask;
import com.planpilot.domain.AcademicTaskType;
import com.planpilot.domain.AvailabilityBlock;
import com.planpilot.domain.AvailabilityType;
import com.planpilot.domain.Course;
import com.planpilot.domain.GeneralTask;
import com.planpilot.domain.PriorityLevel;
import com.planpilot.domain.ProgressStatus;
import com.planpilot.domain.Schedule;
import com.planpilot.domain.StudyBlock;
import com.planpilot.domain.Task;
import com.planpilot.domain.StudyPeriod;
import com.planpilot.domain.UserPreferences;
import com.planpilot.service.ReplanOption;
import com.planpilot.service.ScheduleInfeasibleException;

import java.io.PrintStream;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.Objects;
import java.util.Scanner;

public class PlanPilotCLI {
    private final PlanPilotController controller;
    private final Scanner scanner;
    private final PrintStream output;
    private Schedule currentSchedule;
    private ScheduleInfeasibleException lastInfeasibility;
    private boolean running = true;

    public PlanPilotCLI(
            PlanPilotController controller, Scanner scanner, PrintStream output) {
        this.controller = Objects.requireNonNull(controller, "Controller cannot be null.");
        this.scanner = Objects.requireNonNull(scanner, "Scanner cannot be null.");
        this.output = Objects.requireNonNull(output, "Output cannot be null.");
    }

    public static void main(String[] args) {
        PlanPilotCLI cli = new PlanPilotCLI(
                createDefaultController(), new Scanner(System.in), System.out);
        cli.run();
    }

    public void run() {
        output.println("PlanPilot CLI");
        output.println("Start here: add courses if needed, add tasks, add Available time, then generate a schedule.");
        output.println("Dates: YYYY-MM-DD. Date/time: YYYY-MM-DDTHH:MM (24-hour time). Type /cancel at a prompt to return to the menu.");
        while (running) {
            try { controller.refreshProgress(); }
            catch (RuntimeException failure) { output.println("Progress refresh failed: " + errorMessage(failure)); }
            syncSchedule();
            printMenu();
            if (!scanner.hasNextLine()) break;
            String choice = scanner.nextLine().trim();
            try {
                handleChoice(choice);
            } catch (CancelledInput cancelled) {
                output.println("Action cancelled. Returning to the main menu.");
            } catch (EndOfInput ended) {
                running = false;
            } catch (RuntimeException exception) {
                output.println("Error: " + errorMessage(exception));
            }
        }
        output.println("Goodbye.");
    }

    private void syncSchedule() {
        currentSchedule = controller.getCurrentSchedule().orElse(null);
        if (lastInfeasibility != null && (currentSchedule == null
                || !currentSchedule.getId().equals(lastInfeasibility.getPartialSchedule().getId())
                || currentSchedule.getStatus() == com.planpilot.domain.ScheduleStatus.OUTDATED)) {
            lastInfeasibility = null;
        }
    }

    static PlanPilotController createDefaultController() {
        return PlanPilotApplicationFactory.createDefault().controller();
    }

    private void printMenu() {
        output.println();
        output.println("1. List courses");
        output.println("2. Add course");
        output.println("3. List tasks and progress");
        output.println("4. Add task");
        output.println("5. Add availability");
        output.println("6. Generate schedule");
        output.println("7. Display study blocks");
        output.println("8. Record progress");
        output.println("9. View progress and session history");
        output.println("10. Add task from natural language (AI)");
        output.println("11. Request effort suggestion (AI)");
        output.println("12. Explain current schedule (AI)");
        output.println("13. Request replanning recommendation (AI)");
        output.println("14. Edit/delete courses, tasks, or availability");
        output.println("15. Resolve unscheduled work");
        output.println("16. Replan remaining work");
        output.println("17. View/change scheduling preferences");
        output.println("0. Exit");
        output.print("Choose an option: ");
        output.flush();
    }

    private void handleChoice(String choice) {
        switch (choice) {
            case "1" -> listCourses();
            case "2" -> addCourse();
            case "3" -> displayTaskProgress();
            case "9" -> displayProgressOverview();
            case "4" -> addTask();
            case "5" -> addAvailability();
            case "6" -> generateSchedule();
            case "7" -> displaySchedule();
            case "8" -> recordProgress();
            case "10" -> addNaturalLanguageTask();
            case "11" -> requestEffortSuggestion();
            case "12" -> explainSchedule();
            case "13" -> requestReplanRecommendation();
            case "14" -> manageExistingData();
            case "15" -> {
                if (lastInfeasibility == null) output.println("Generate an infeasible schedule first.");
                else offerRecovery(lastInfeasibility);
            }
            case "16" -> replanRemainingWork();
            case "17" -> editPreferences();
            case "0" -> running = false;
            default -> output.println("Unknown option. Enter a number from 0 to 17.");
        }
    }

    private void listCourses() {
        var courses = controller.getCourses();
        if (courses.isEmpty()) {
            output.println("No courses have been added.");
            return;
        }
        output.println("Courses:");
        courses.forEach(course -> output.printf(
                "- %s — %s%n", course.getCode(), course.getName()));
    }

    private void addCourse() {
        Course course = new Course(
                readText("Course code: "),
                readText("Course name: "));
        controller.addCourse(course);
        output.println("Course added.");
    }

    private void addTask() {
        String kind = readValue("Task kind (general/academic): ", value -> {
            if (!value.equalsIgnoreCase("general") && !value.equalsIgnoreCase("academic"))
                throw new IllegalArgumentException("Choose general or academic.");
            return value.toLowerCase(Locale.ROOT);
        });
        if (kind.equals("academic") && controller.getCourses().isEmpty()) {
            output.println("Add a course first (option 2), then add an academic task."); return;
        }
        String title = readText("Title: ");
        LocalDateTime deadline = readDateTime("Deadline (YYYY-MM-DDTHH:MM): ");
        double hours = readValue("Estimated hours: ", value -> number(value, Double.MIN_VALUE, Double.MAX_VALUE));
        int difficulty = readValue("Difficulty (1-5): ", value -> integer(value, 1, 5));
        PriorityLevel importance = readValue("Importance (LOW/MEDIUM/HIGH): ", value -> parseEnum(PriorityLevel.class, value));
        Task task;
        if (kind.equals("academic")) {
            Course course = chooseCourse();
            double weight = readValue("Assessment weight % (0-100): ", value -> number(value, 0, 100));
            AcademicTaskType type = readValue("Type (ASSIGNMENT/EXAM/QUIZ/PROJECT/READING/STUDY): ",
                    value -> parseEnum(AcademicTaskType.class, value));
            task = new AcademicTask(title, deadline, hours, difficulty, importance, course, weight, type);
        } else task = new GeneralTask(title, deadline, hours, difficulty, importance);
        controller.addTask(task);
        output.println("Task added.");
    }

    private void addAvailability() {
        LocalDateTime start = readDateTime("Start (YYYY-MM-DDTHH:MM): ");
        LocalDateTime end = readEnd(start);
        AvailabilityType type = readValue("Type (AVAILABLE = Available time; CLASS/WORK/APPOINTMENT/INTERVIEW/UNAVAILABLE = commitment): ",
                value -> parseEnum(AvailabilityType.class, value));
        controller.addAvailability(new AvailabilityBlock(start, end, type));
        output.println("Availability added.");
    }

    private LocalDateTime readEnd(LocalDateTime start) {
        return readValue("End (YYYY-MM-DDTHH:MM): ", value -> {
            LocalDateTime end = LocalDateTime.parse(value);
            if (!end.isAfter(start)) throw new IllegalArgumentException("End must be after start.");
            return end;
        });
    }

    private void generateSchedule() {
        LocalDate start = readDate("Planning start date (YYYY-MM-DD): ");
        LocalDate end = readValue("Planning end date (YYYY-MM-DD, inclusive): ", value -> {
            LocalDate date = LocalDate.parse(value);
            if (date.isBefore(start)) throw new IllegalArgumentException("End date cannot be before start date.");
            return date;
        });
        try {
            currentSchedule = controller.generateSchedule(start, end);
            lastInfeasibility = null;
            output.println("Schedule generated.");
        } catch (ScheduleInfeasibleException exception) {
            currentSchedule = exception.getPartialSchedule();
            output.printf(
                    "Schedule is incomplete: %.2f hours could not be scheduled.%n",
                    exception.getWorkloadDeficitHours());
            displayRecoverySuggestions(exception);
        }
        displaySchedule();
    }

    private void displayRecoverySuggestions(ScheduleInfeasibleException exception) {
        lastInfeasibility = exception;
        output.println(exception.getMessage());
        output.println("A partial plan was saved. Choose a recovery action; AI advice is optional.");
        offerRecovery(exception);
    }

    private void printRecoveryAdvice(ScheduleInfeasibleException exception) {
        try {
            RecoveryPlan recovery = controller.requestRecoverySuggestions(exception);
            output.println("AI recovery summary: " + recovery.summary());
            for (RecoverySuggestion suggestion : recovery.suggestions())
                output.println("- " + suggestion.action() + ": " + suggestion.explanation());
            output.println("No recovery action was applied automatically.");
        } catch (RuntimeException failure) {
            output.println("AI recovery suggestions unavailable: " + errorMessage(failure));
        }
    }

    private void offerRecovery(ScheduleInfeasibleException failure) {
        String choice;
        while (true) {
            output.println("Recovery: 1 Add available time; 2 Extend window; 3 Defer a task; 4 Ask AI for advice; 0 Keep current plan");
            choice = readValue("Recovery choice: ", value -> {
                if (!java.util.List.of("0", "1", "2", "3", "4").contains(value))
                    throw new IllegalArgumentException("Choose 0, 1, 2, 3, or 4.");
                return value;
            });
            if (!choice.equals("4")) break;
            printRecoveryAdvice(failure);
        }
        var partial = failure.getPartialSchedule();
        var action = com.planpilot.ai.RecoveryAction.KEEP_CURRENT_CONSTRAINTS;
        AvailabilityBlock block = null; LocalDate newEnd = null; String taskId = null;
        switch (choice) {
            case "0" -> { output.println("Current constraints and partial plan kept."); return; }
            case "1" -> {
                action = com.planpilot.ai.RecoveryAction.ADD_AVAILABILITY;
                LocalDateTime start = readDateTime("Start (YYYY-MM-DDTHH:MM): ");
                block = new AvailabilityBlock(start, readEnd(start), AvailabilityType.AVAILABLE);
                output.println("Add Available time " + block.getStart() + " to " + block.getEnd() + " and replan.");
            }
            case "2" -> {
                action = com.planpilot.ai.RecoveryAction.EXTEND_PLANNING_WINDOW;
                newEnd = readValue("New planning end date (YYYY-MM-DD): ", value -> {
                    LocalDate date = LocalDate.parse(value);
                    if (!date.isAfter(partial.getEndDate())) throw new IllegalArgumentException("Choose a later end date.");
                    return date;
                });
                output.println("Extend through " + newEnd + ". Deadlines stay unchanged; no availability is invented.");
            }
            case "3" -> {
                action = com.planpilot.ai.RecoveryAction.DEFER_LOWER_PRIORITY_TASK;
                var candidates = controller.getOpenTasks().stream().filter(t -> t.getDeferredUntil() == null
                        || t.getDeferredUntil().isBefore(partial.getEndDate().plusDays(1).atStartOfDay())).toList();
                if (candidates.isEmpty()) throw new IllegalStateException("No unfinished task can be deferred.");
                for (int index = 0; index < candidates.size(); index++) {
                    Task task = candidates.get(index);
                    output.printf("%d. %s — %.2f hours remaining, deadline %s%n", index + 1,
                            task.getTitle(), task.getRemainingHours(), task.getDeadline());
                }
                Task selected = candidates.get(selectionIndex("Task number: ", candidates.size()));
                taskId = selected.getId();
                output.println("Leave " + selected.getTitle() + " out of this plan until " + partial.getEndDate().plusDays(1)
                        + ". Its deadline stays " + selected.getDeadline() + "; it remains unfinished and can become overdue.");
            }
            default -> throw new IllegalArgumentException("Choose 0, 1, 2, or 3.");
        }
        if (!confirm("Apply this change and replan? (yes/no): ")) {
            output.println("Recovery cancelled; nothing changed."); return;
        }
        var request = new com.planpilot.service.RecoveryRequest(partial.getId(), action, block, newEnd, taskId);
        try {
            currentSchedule = controller.applyRecovery(request); lastInfeasibility = null;
            output.println("Recovery applied and schedule regenerated.");
        } catch (ScheduleInfeasibleException stillInfeasible) {
            currentSchedule = stillInfeasible.getPartialSchedule(); lastInfeasibility = stillInfeasible;
            output.printf("Change applied; partial plan saved with %.2f hours still unscheduled.%n", stillInfeasible.getWorkloadDeficitHours());
        }
        displaySchedule();
    }

    private void manageExistingData() {
        output.println("Manage: 1 Edit course; 2 Delete course; 3 Edit task; 4 Delete task; 5 List availability; 6 Edit availability; 7 Delete availability; 8 Include deferred task again; 0 Back");
        switch (prompt("Manage choice: ")) {
            case "0" -> { }
            case "1" -> {
                Course selected = chooseCourse();
                controller.updateCourse(new Course(selected.getId(), promptWithDefault("Course code", selected.getCode()),
                        promptWithDefault("Course name", selected.getName())));
                output.println("Course updated.");
            }
            case "2" -> {
                Course selected = chooseCourse();
                if (confirm("Delete " + selected.getCode() + "? (yes/no): ")) {
                    controller.deleteCourse(selected.getId()); output.println("Course deleted.");
                }
            }
            case "3" -> editTask();
            case "4" -> {
                Task selected = chooseTask();
                if (confirm("Delete " + selected.getTitle() + "? Recorded history is kept. (yes/no): ")) {
                    controller.deleteTask(selected.getId()); output.println("Task deleted.");
                }
            }
            case "5" -> listAvailability();
            case "6" -> {
                AvailabilityBlock selected = chooseAvailability();
                LocalDateTime start = readDefault("Start", selected.getStart().toString(), LocalDateTime::parse);
                LocalDateTime end = readDefault("End", selected.getEnd().toString(), value -> {
                    LocalDateTime parsed = LocalDateTime.parse(value);
                    if (!parsed.isAfter(start)) throw new IllegalArgumentException("End must be after start.");
                    return parsed;
                });
                AvailabilityType type = readDefault("Type (AVAILABLE = Available time)", selected.getType().name(),
                        value -> parseEnum(AvailabilityType.class, value));
                controller.updateAvailability(new AvailabilityBlock(selected.getId(), start, end, type));
                output.println("Availability updated.");
            }
            case "7" -> {
                AvailabilityBlock selected = chooseAvailability();
                if (confirm("Delete this time entry? (yes/no): ")) {
                    controller.deleteAvailability(selected.getId()); output.println("Availability deleted.");
                }
            }
            case "8" -> {
                Task selected = chooseTask();
                if (selected.getDeferredUntil() == null) throw new IllegalArgumentException("This task is not deferred.");
                if (confirm("Include this task in planning again? (yes/no): ")) {
                    controller.resumeTask(selected.getId()); output.println("Task included again. Replan when ready.");
                }
            }
            default -> throw new IllegalArgumentException("Choose a valid management option.");
        }
    }

    private void editTask() {
        Task selected = chooseTask();
        String title = promptWithDefault("Title", selected.getTitle());
        LocalDateTime deadline = readDefault("Deadline", selected.getDeadline().toString(), LocalDateTime::parse);
        double hours = readDefault("Estimated hours", Double.toString(selected.getEstimatedHours()),
                value -> number(value, Math.max(Double.MIN_VALUE, selected.getCompletedHours()), Double.MAX_VALUE));
        int difficulty = readDefault("Difficulty", Integer.toString(selected.getDifficulty()), value -> integer(value, 1, 5));
        PriorityLevel importance = readDefault("Importance", selected.getUserImportance().name(), value -> parseEnum(PriorityLevel.class, value));
        Task edited;
        if (selected instanceof AcademicTask academic) {
            Course course = confirm("Change course? (yes/no): ") ? chooseCourse() : academic.getCourse();
            double weight = readDefault("Assessment weight % (0-100)", Double.toString(academic.getAssessmentWeight()), value -> number(value, 0, 100));
            AcademicTaskType type = readDefault("Academic type", academic.getTaskType().name(), value -> parseEnum(AcademicTaskType.class, value));
            edited = new AcademicTask(selected.getId(), title, deadline, hours, difficulty, importance, course, weight, type);
        } else edited = new GeneralTask(selected.getId(), title, deadline, hours, difficulty, importance);
        edited.setCompletedHours(selected.getCompletedHours()); edited.setDeferredUntil(selected.getDeferredUntil());
        if (selected.getStatus() == com.planpilot.domain.TaskStatus.SKIPPED) edited.markSkipped();
        controller.updateTask(edited); output.println("Task updated.");
    }

    private void listAvailability() {
        var entries = controller.getAvailability(LocalDateTime.MIN, LocalDateTime.MAX);
        if (entries.isEmpty()) { output.println("No availability entries."); return; }
        for (int index = 0; index < entries.size(); index++) {
            AvailabilityBlock entry = entries.get(index);
            output.printf("%d. %s — %s to %s%n", index + 1, entry.getType() == AvailabilityType.AVAILABLE ? "Available time" : entry.getType(), entry.getStart(), entry.getEnd());
        }
    }

    private AvailabilityBlock chooseAvailability() {
        var entries = controller.getAvailability(LocalDateTime.MIN, LocalDateTime.MAX);
        if (entries.isEmpty()) throw new IllegalStateException("Add availability first.");
        listAvailability(); return entries.get(selectionIndex("Entry number: ", entries.size()));
    }

    private void addNaturalLanguageTask() {
        ParsedTaskData proposal = controller.parseNaturalLanguageTask(
                prompt("Describe the task: "));
        while (true) {
            displayTaskProposal(proposal);
            String action = readValue("Proposal action (accept/edit/cancel): ", value -> {
                String choice = value.toLowerCase(Locale.ROOT);
                if (!java.util.List.of("accept", "edit", "cancel").contains(choice))
                    throw new IllegalArgumentException("Choose accept, edit, or cancel.");
                return choice;
            });
            if (action.equals("cancel")) {
                output.println("Task proposal cancelled; nothing was saved."); return;
            }
            if (action.equals("accept")) break;
            proposal = editProposal(proposal);
        }

        Task task = createTaskFromProposal(proposal);
        controller.addTask(task);
        output.println("Proposed task confirmed and added.");
    }

    private void displayTaskProposal(ParsedTaskData proposal) {
        output.println("AI task proposal (not yet saved):");
        output.printf("- %s | %s | %.2f hours | difficulty %d | %s | %s%n",
                proposal.title(), proposal.deadline(),
                proposal.estimatedHours(), proposal.difficulty(),
                proposal.importance(), proposal.taskKind());
        if (proposal.taskKind() == ParsedTaskKind.ACADEMIC) {
            output.printf("- course=%s | weight=%s | type=%s%n",
                    proposal.courseCode(), proposal.assessmentWeight(),
                    proposal.academicTaskType());
        }
    }

    private ParsedTaskData editProposal(ParsedTaskData proposal) {
        String title = promptWithDefault("Title", proposal.title());
        LocalDateTime deadline = readDefault("Deadline", proposal.deadline().toString(), LocalDateTime::parse);
        double hours = readDefault("Estimated hours", Double.toString(proposal.estimatedHours()),
                value -> number(value, Double.MIN_VALUE, Double.MAX_VALUE));
        int difficulty = readDefault("Difficulty", Integer.toString(proposal.difficulty()), value -> integer(value, 1, 5));
        PriorityLevel importance = readDefault("Importance", proposal.importance().name(), value -> parseEnum(PriorityLevel.class, value));
        ParsedTaskKind kind = readDefault("Task kind", proposal.taskKind().name(), value -> parseEnum(ParsedTaskKind.class, value));

        String courseCode = null;
        Double weight = null;
        AcademicTaskType taskType = null;
        if (kind == ParsedTaskKind.ACADEMIC) {
            courseCode = chooseCourse().getCode();
            weight = readDefault("Assessment weight % (0-100)", proposal.assessmentWeight() == null
                    ? "0" : proposal.assessmentWeight().toString(), value -> number(value, 0, 100));
            taskType = readDefault("Academic task type", proposal.academicTaskType() == null
                    ? AcademicTaskType.STUDY.name() : proposal.academicTaskType().name(),
                    value -> parseEnum(AcademicTaskType.class, value));
        }
        return new ParsedTaskData(
                title, deadline, hours, difficulty, importance, kind,
                courseCode, weight, taskType);
    }

    private Task createTaskFromProposal(ParsedTaskData proposal) {
        if (proposal.taskKind() == ParsedTaskKind.GENERAL) {
            return new GeneralTask(
                    proposal.title(), proposal.deadline(),
                    proposal.estimatedHours(), proposal.difficulty(),
                    proposal.importance());
        }
        Course course = controller.getCourses().stream()
                .filter(c -> c.getCode().equalsIgnoreCase(proposal.courseCode()) || c.getId().equals(proposal.courseCode()))
                .findFirst().orElse(null);
        if (course == null) {
            output.println("Choose an existing course for this academic proposal; no course will be created automatically.");
            course = chooseCourse();
        }
        double weight = proposal.assessmentWeight() == null
                ? readValue("Assessment weight % (0-100): ", value -> number(value, 0, 100))
                : proposal.assessmentWeight();
        AcademicTaskType taskType = proposal.academicTaskType() == null
                ? readValue("Type (ASSIGNMENT/EXAM/QUIZ/PROJECT/READING/STUDY): ",
                    value -> parseEnum(AcademicTaskType.class, value))
                : proposal.academicTaskType();
        return new AcademicTask(
                proposal.title(), proposal.deadline(),
                proposal.estimatedHours(), proposal.difficulty(),
                proposal.importance(), course, weight, taskType);
    }

    private void requestEffortSuggestion() {
        Task task = chooseTask();
        double suggestion = controller.requestEffortSuggestion(task);
        output.printf("Current estimate: %.2f hours%n",
                task.getEstimatedHours());
        output.printf("AI suggested estimate: %.2f hours%n", suggestion);
        Double confirmedEstimate = readValue("New estimate (blank to ignore; positive hours): ", value -> {
            if (value.isBlank()) return null;
            return number(value, Math.max(Double.MIN_VALUE, task.getCompletedHours()), Double.MAX_VALUE);
        });
        if (confirmedEstimate == null) {
            output.println("Suggestion ignored; estimate unchanged."); return;
        }
        Task edited = task instanceof AcademicTask academic
                ? new AcademicTask(task.getId(), task.getTitle(), task.getDeadline(), confirmedEstimate,
                    task.getDifficulty(), task.getUserImportance(), academic.getCourse(), academic.getAssessmentWeight(), academic.getTaskType())
                : new GeneralTask(task.getId(), task.getTitle(), task.getDeadline(), confirmedEstimate, task.getDifficulty(), task.getUserImportance());
        controller.updateTask(edited);
        output.println("Task estimate updated by user confirmation.");
    }

    private void explainSchedule() {
        if (!requireSchedule()) return;
        String explanation = controller.explainSchedule(
                readText("Schedule question: "));
        output.println("AI explanation: " + explanation);
    }

    private void requestReplanRecommendation() {
        if (!requireSchedule()) return;
        ReplanRecommendation recommendation =
                controller.requestReplanRecommendation();
        output.println("AI impact summary: "
                + recommendation.impactSummary());
        output.println("Recommended option: "
                + recommendation.recommendedOption());
        output.println("Reason: " + recommendation.rationale());
        if (!confirm("Apply this replanning option? (yes/no): ")) {
            output.println("Recommendation not applied.");
            return;
        }
        try {
            currentSchedule = controller.requestReplan(
                    recommendation.recommendedOption());
            lastInfeasibility = null;
            output.println("Replanning option applied by user request.");
        } catch (ScheduleInfeasibleException exception) {
            currentSchedule = exception.getPartialSchedule();
            output.printf(
                    "Replanned schedule remains incomplete: %.2f hours could not be scheduled.%n",
                    exception.getWorkloadDeficitHours());
            displayRecoverySuggestions(exception);
        }
        displaySchedule();
    }

    private void displaySchedule() {
        if (currentSchedule == null) {
            output.println("No schedule has been generated. Choose 6 to plan work.");
            return;
        }
        output.printf("Schedule: %s to %s (%s)%n", currentSchedule.getStartDate(),
                currentSchedule.getEndDate(), currentSchedule.getStatus());
        if (currentSchedule.getStatus() == com.planpilot.domain.ScheduleStatus.OUTDATED)
            output.println("Workload or constraints changed. Choose 16 to replan when ready.");
        var upcoming = currentSchedule.getStudyBlocks().stream()
                .filter(b -> b.getProgressStatus() == ProgressStatus.PLANNED).toList();
        if (lastInfeasibility != null)
            output.printf("Incomplete plan: %.2f hours unscheduled. Choose 15 to review recovery actions.%n",
                    lastInfeasibility.getWorkloadDeficitHours());
        output.println("Upcoming study sessions:");
        if (upcoming.isEmpty()) output.println("No upcoming sessions. Review remaining tasks and any unscheduled-work warning.");
        printSessions(upcoming);
        var history = currentSchedule.getStudyBlocks().stream()
                .filter(b -> b.getProgressStatus() != ProgressStatus.PLANNED).toList();
        if (!history.isEmpty()) {
            output.println("Recorded sessions (history, not new allocations):");
            printSessions(history);
        }
    }

    private void printSessions(java.util.List<StudyBlock> blocks) {
        for (int index = 0; index < blocks.size(); index++) {
            StudyBlock block = blocks.get(index);
            output.printf("Session %d: %s%n  %s to %s; planned %.2f hours%n  %s%n", index + 1,
                    taskLabel(block.getTask()), block.getStart(), block.getEnd(), block.durationHours(),
                    block.isInferred() ? "INFERRED — awaiting confirmation" : block.getProgressStatus());
            var records = controller.getProgressHistory(block.getTask()).stream()
                    .filter(record -> record.getStudyBlock().getId().equals(block.getId())).toList();
            if (!records.isEmpty()) {
                var latest = records.get(records.size() - 1);
                output.printf("  %s hours: %.2f%n", latest.isInferred() ? "Inferred" : "Confirmed", latest.getCompletedHours());
            }
        }
    }

    private String taskLabel(Task task) {
        return task instanceof AcademicTask academic
                ? academic.getCourse().getCode() + ": " + task.getTitle() : task.getTitle();
    }

    private void recordProgress() {
        StudyBlock block = chooseStudyBlock();
        ProgressStatus status = readValue("Outcome (COMPLETED/PARTIAL/SKIPPED): ", value -> {
            ProgressStatus selected = parseEnum(ProgressStatus.class, value);
            if (selected == ProgressStatus.PLANNED)
                throw new IllegalArgumentException("Choose COMPLETED, PARTIAL, or SKIPPED.");
            return selected;
        });
        double hours = status == ProgressStatus.COMPLETED ? block.durationHours() : 0;
        if (status == ProgressStatus.PARTIAL)
            hours = readValue("Actual completed hours (0 to " + block.durationHours() + "): ",
                    value -> number(value, 0, block.durationHours()));
        controller.updateProgress(block, status, hours);
        output.printf("Progress saved: %s; %.2f confirmed hours; %.2f hours remaining.%n",
                status, controller.getConfirmedHours(block.getTask()), block.getTask().getRemainingHours());
        output.println("Corrections replace this session's previous contribution. Replan remaining work with option 16 when ready.");
    }

    private void displayTaskProgress() {
        var tasks = controller.getTasks();
        if (tasks.isEmpty()) {
            output.println("No tasks have been added.");
            return;
        }
        output.println("Tasks:");
        tasks.forEach(task -> {
            output.printf("%s%n  Status: %s; deadline: %s%n", taskLabel(task), task.getStatus(), task.getDeadline());
            output.printf("  Original estimate: %.2f hours; current estimate: %.2f hours%n  Confirmed: %.2f; inferred (unconfirmed): %.2f; remaining: %.2f hours%n",
                    controller.getOriginalEstimate(task), task.getEstimatedHours(), controller.getConfirmedHours(task),
                    controller.getInferredHours(task), task.getRemainingHours());
            if (task.getDeferredUntil() != null) output.println("  Deferred until " + task.getDeferredUntil());
        });
    }

    private void displayProgressOverview() {
        var tasks = controller.getTasks();
        var sessions = controller.getProgressSessions();
        output.printf("Planned session hours: %.2f; confirmed completed hours: %.2f; remaining task effort: %.2f%n",
                sessions.stream().mapToDouble(StudyBlock::durationHours).sum(),
                tasks.stream().mapToDouble(controller::getConfirmedHours).sum(),
                tasks.stream().mapToDouble(Task::getRemainingHours).sum());
        long completed = tasks.stream().filter(t -> t.getRemainingHours() <= 0).count();
        long awaiting = sessions.stream().filter(StudyBlock::isInferred).count();
        output.printf("Completed tasks: %d; incomplete tasks: %d; sessions awaiting confirmation: %d%n",
                completed, tasks.size() - completed, awaiting);
        output.println("Inferred hours are tentative and reduce remaining effort until corrected; they are not confirmed work.");
        displayTaskProgress();
        if (sessions.isEmpty()) output.println("Generate a schedule first to record study progress.");
        else {
            printSessions(sessions);
            output.println("Choose 8 to confirm or correct a session, including saved historical sessions.");
        }
    }

    private void replanRemainingWork() {
        if (!requireSchedule()) return;
        output.println("Rebuild future sessions in the existing planning window. Recorded history and deadlines are kept.");
        if (!confirm("Replan now? (yes/no): ")) return;
        try {
            currentSchedule = controller.requestReplan(ReplanOption.RESCHEDULE_INCOMPLETE_WORK);
            lastInfeasibility = null;
            output.println("Remaining work replanned.");
        } catch (ScheduleInfeasibleException failure) {
            currentSchedule = failure.getPartialSchedule();
            displayRecoverySuggestions(failure);
        }
        displaySchedule();
    }

    private boolean requireSchedule() {
        if (currentSchedule != null) return true;
        output.println("Generate a schedule first (option 6).");
        return false;
    }

    private void editPreferences() {
        UserPreferences current = controller.getUserPreferences();
        output.printf("Preferences: maximum session %d minutes; break %d minutes; preferred period %s.%n",
                current.getMaxSessionLength(), current.getBreakLength(), current.getPreferredStudyPeriod());
        output.println("Preferences apply to future generation. Available time is still required.");
        if (!confirm("Edit preferences? (yes/no): ")) return;
        int maximum = readDefault("Maximum session minutes", Integer.toString(current.getMaxSessionLength()),
                value -> integer(value, 1, Integer.MAX_VALUE));
        int pause = readDefault("Break minutes", Integer.toString(current.getBreakLength()),
                value -> integer(value, 0, Integer.MAX_VALUE));
        StudyPeriod period = readDefault("Preferred period (ANY/MORNING/AFTERNOON/EVENING)", current.getPreferredStudyPeriod().name(),
                value -> parseEnum(StudyPeriod.class, value));
        if (confirm("Save preferences? (yes/no): ")) {
            controller.updateUserPreferences(new UserPreferences(maximum, period, pause));
            output.println("Preferences saved. Replan when ready; no schedule was regenerated.");
        }
    }

    private Course chooseCourse() {
        var courses = controller.getCourses();
        if (courses.isEmpty()) {
            throw new IllegalStateException(
                    "Add a course before creating an academic task.");
        }
        output.println("Choose a course:");
        for (int index = 0; index < courses.size(); index++) {
            Course course = courses.get(index);
            output.printf("%d. %s — %s%n", index + 1,
                    course.getCode(), course.getName());
        }
        int selected = selectionIndex("Course number: ", courses.size());
        return courses.get(selected);
    }



    private Task chooseTask() {
        var tasks = controller.getTasks();
        if (tasks.isEmpty()) {
            throw new IllegalStateException("Add a task first.");
        }
        output.println("Choose a task:");
        for (int index = 0; index < tasks.size(); index++) {
            Task task = tasks.get(index);
            output.printf("%d. %s (%s, %.2f hours remaining)%n",
                    index + 1, task.getTitle(), task.getStatus(),
                    task.getRemainingHours());
        }
        return tasks.get(selectionIndex("Task number: ", tasks.size()));
    }

    private StudyBlock chooseStudyBlock() {
        var blocks = controller.getProgressSessions();
        if (blocks.isEmpty()) throw new IllegalStateException("Generate a schedule first to record study progress.");
        output.println("Choose a session to confirm or correct:");
        printSessions(blocks);
        return blocks.get(selectionIndex("Session number: ", blocks.size()));
    }

    private int selectionIndex(String message, int count) {
        return readValue(message, value -> {
            int selected;
            try { selected = Integer.parseInt(value); }
            catch (NumberFormatException invalid) { throw new IllegalArgumentException("Enter a number from 1 to " + count + "."); }
            if (selected < 1 || selected > count) throw new IllegalArgumentException("Enter a number from 1 to " + count + ".");
            return selected - 1;
        });
    }

    private static final class CancelledInput extends RuntimeException { }
    private static final class EndOfInput extends RuntimeException { }

    private <T> T readValue(String message, java.util.function.Function<String, T> parser) {
        while (true) {
            String value = prompt(message);
            try { return parser.apply(value); }
            catch (IllegalArgumentException | java.time.DateTimeException invalid) {
                output.println("Error: " + invalid.getMessage() + " Try again, or type /cancel.");
            }
        }
    }

    private LocalDate readDate(String message) {
        return readValue(message, value -> {
            try { return LocalDate.parse(value); }
            catch (java.time.DateTimeException invalid) { throw new IllegalArgumentException("Use a date such as 2026-11-06."); }
        });
    }
    private LocalDateTime readDateTime(String message) {
        return readValue(message, value -> {
            try { return LocalDateTime.parse(value); }
            catch (java.time.DateTimeException invalid) { throw new IllegalArgumentException("Use date/time such as 2026-11-06T13:00."); }
        });
    }
    private double number(String value, double minimum, double maximum) {
        double result;
        try { result = Double.parseDouble(value); }
        catch (NumberFormatException invalid) { throw new IllegalArgumentException("Enter a numeric value."); }
        if (!Double.isFinite(result)) throw new IllegalArgumentException("Enter a finite numeric value.");
        if (result < minimum || result > maximum) {
            if (minimum == Double.MIN_VALUE) throw new IllegalArgumentException("Hours must be greater than zero.");
            if (maximum == Double.MAX_VALUE) throw new IllegalArgumentException("Hours must be at least " + minimum + ".");
            throw new IllegalArgumentException("Enter a number from " + minimum + " to " + maximum + ".");
        }
        return result;
    }
    private String readText(String message) {
        return readValue(message, value -> {
            if (value.isBlank()) throw new IllegalArgumentException("This field is required.");
            return value;
        });
    }
    private boolean confirm(String message) {
        return readValue(message, value -> {
            if (value.equalsIgnoreCase("yes") || value.equalsIgnoreCase("y")) return true;
            if (value.isBlank() || value.equalsIgnoreCase("no") || value.equalsIgnoreCase("n")) return false;
            throw new IllegalArgumentException("Enter yes or no.");
        });
    }

    private String prompt(String message) {
        output.print(message); output.flush();
        if (!scanner.hasNextLine()) throw new EndOfInput();
        String value = scanner.nextLine().trim();
        if (value.equalsIgnoreCase("/cancel")) throw new CancelledInput();
        return value;
    }

    private String promptWithDefault(String field, String defaultValue) {
        String value = prompt(field + " [" + defaultValue + "]: ");
        return value.isBlank() ? defaultValue : value;
    }



    private int integer(String value, int minimum, int maximum) {
        int result;
        try { result = Integer.parseInt(value); }
        catch (NumberFormatException invalid) { throw new IllegalArgumentException("Enter a whole number."); }
        if (result < minimum || result > maximum)
            throw new IllegalArgumentException("Enter a whole number from " + minimum + " to " + maximum + ".");
        return result;
    }

    private <T> T readDefault(String field, String defaultValue, java.util.function.Function<String, T> parser) {
        return readValue(field + " [" + defaultValue + "]: ", value -> parser.apply(value.isBlank() ? defaultValue : value));
    }

    private <E extends Enum<E>> E parseEnum(Class<E> enumType, String value) {
        try {
            return Enum.valueOf(enumType, value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(
                    "Invalid " + enumType.getSimpleName() + ": " + value + ".");
        }
    }

    private String errorMessage(RuntimeException exception) {
        return exception.getMessage() == null
                ? exception.getClass().getSimpleName()
                : exception.getMessage();
    }
}

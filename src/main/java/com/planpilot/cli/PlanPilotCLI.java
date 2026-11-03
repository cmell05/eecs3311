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
import com.planpilot.service.ScheduleInfeasibleException;

import java.io.PrintStream;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Scanner;

public class PlanPilotCLI {
    private final PlanPilotController controller;
    private final Scanner scanner;
    private final PrintStream output;
    private final Map<String, Task> knownTasks = new LinkedHashMap<>();
    private Schedule currentSchedule;
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
        while (running && scanner.hasNextLine()) {
            printMenu();
            String choice = scanner.nextLine().trim();
            try {
                handleChoice(choice);
            } catch (RuntimeException exception) {
                output.println("Error: " + errorMessage(exception));
            }
        }
        output.println("Goodbye.");
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
        output.println("9. View current task status");
        output.println("10. Add task from natural language (AI)");
        output.println("11. Request effort suggestion (AI)");
        output.println("12. Explain current schedule (AI)");
        output.println("13. Request replanning recommendation (AI)");
        output.println("0. Exit");
        output.print("Choose an option: ");
    }

    private void handleChoice(String choice) {
        switch (choice) {
            case "1" -> listCourses();
            case "2" -> addCourse();
            case "3", "9" -> displayTaskProgress();
            case "4" -> addTask();
            case "5" -> addAvailability();
            case "6" -> generateSchedule();
            case "7" -> displaySchedule();
            case "8" -> recordProgress();
            case "10" -> addNaturalLanguageTask();
            case "11" -> requestEffortSuggestion();
            case "12" -> explainSchedule();
            case "13" -> requestReplanRecommendation();
            case "0" -> running = false;
            default -> output.println("Unknown option. Enter a number from 0 to 13.");
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
                "- %s: %s - %s%n",
                course.getId(), course.getCode(), course.getName()));
    }

    private void addCourse() {
        Course course = new Course(
                prompt("Course ID: "),
                prompt("Course code: "),
                prompt("Course name: "));
        controller.addCourse(course);
        output.println("Course added.");
    }

    private void addTask() {
        String kind = prompt("Task kind (general/academic): ").toLowerCase(Locale.ROOT);
        String id = prompt("Task ID: ");
        String title = prompt("Title: ");
        LocalDateTime deadline = LocalDateTime.parse(
                prompt("Deadline (YYYY-MM-DDTHH:MM): "));
        double estimatedHours = Double.parseDouble(prompt("Estimated hours: "));
        int difficulty = Integer.parseInt(prompt("Difficulty (1-5): "));
        PriorityLevel importance = parseEnum(
                PriorityLevel.class, prompt("Importance (LOW/MEDIUM/HIGH): "));

        Task task;
        if (kind.equals("academic")) {
            Course course = findCourse(prompt("Course ID: "));
            double weight = Double.parseDouble(prompt("Assessment weight (0-100): "));
            AcademicTaskType taskType = parseEnum(
                    AcademicTaskType.class,
                    prompt("Type (ASSIGNMENT/EXAM/QUIZ/PROJECT/READING/STUDY): "));
            task = new AcademicTask(
                    id, title, deadline, estimatedHours, difficulty,
                    importance, course, weight, taskType);
        } else if (kind.equals("general")) {
            task = new GeneralTask(
                    id, title, deadline, estimatedHours, difficulty, importance);
        } else {
            throw new IllegalArgumentException(
                    "Task kind must be general or academic.");
        }

        Task created = controller.addTask(task);
        knownTasks.put(created.getId(), created);
        output.println("Task added.");
    }

    private void addAvailability() {
        AvailabilityBlock block = new AvailabilityBlock(
                prompt("Availability ID: "),
                LocalDateTime.parse(prompt("Start (YYYY-MM-DDTHH:MM): ")),
                LocalDateTime.parse(prompt("End (YYYY-MM-DDTHH:MM): ")),
                parseEnum(
                        AvailabilityType.class,
                        prompt("Type (AVAILABLE/CLASS/WORK/APPOINTMENT/INTERVIEW/UNAVAILABLE): ")));
        controller.addAvailability(block);
        output.println("Availability added.");
    }

    private void generateSchedule() {
        LocalDate start = LocalDate.parse(prompt("Planning start date (YYYY-MM-DD): "));
        LocalDate end = LocalDate.parse(prompt("Planning end date (YYYY-MM-DD): "));
        try {
            currentSchedule = controller.generateSchedule(start, end);
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

    private void displayRecoverySuggestions(
            ScheduleInfeasibleException exception) {
        try {
            RecoveryPlan recovery = controller.requestRecoverySuggestions(exception);
            output.println("AI recovery summary: " + recovery.summary());
            for (RecoverySuggestion suggestion : recovery.suggestions()) {
                output.printf("- %s: %s%n",
                        suggestion.action(), suggestion.explanation());
            }
            output.println("No recovery action was applied automatically.");
        } catch (RuntimeException aiFailure) {
            output.println("AI recovery suggestions unavailable: "
                    + errorMessage(aiFailure));
        }
    }

    private void addNaturalLanguageTask() {
        ParsedTaskData proposal = controller.parseNaturalLanguageTask(
                prompt("Describe the task: "));
        displayTaskProposal(proposal);
        String action = prompt("Proposal action (accept/edit/cancel): ")
                .toLowerCase(Locale.ROOT);
        if (action.equals("cancel")) {
            output.println("Task proposal cancelled; nothing was saved.");
            return;
        }
        if (action.equals("edit")) {
            proposal = editProposal(proposal);
            displayTaskProposal(proposal);
        } else if (!action.equals("accept")) {
            throw new IllegalArgumentException(
                    "Proposal action must be accept, edit, or cancel.");
        }

        Task task = createTaskFromProposal(
                prompt("Task ID: "), proposal);
        Task created = controller.addTask(task);
        knownTasks.put(created.getId(), created);
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
        LocalDateTime deadline = LocalDateTime.parse(
                promptWithDefault("Deadline", proposal.deadline().toString()));
        double hours = Double.parseDouble(promptWithDefault(
                "Estimated hours", Double.toString(proposal.estimatedHours())));
        int difficulty = Integer.parseInt(promptWithDefault(
                "Difficulty", Integer.toString(proposal.difficulty())));
        PriorityLevel importance = parseEnum(
                PriorityLevel.class,
                promptWithDefault("Importance", proposal.importance().name()));
        ParsedTaskKind kind = parseEnum(
                ParsedTaskKind.class,
                promptWithDefault("Task kind", proposal.taskKind().name()));

        String courseCode = null;
        Double weight = null;
        AcademicTaskType taskType = null;
        if (kind == ParsedTaskKind.ACADEMIC) {
            courseCode = promptWithDefault(
                    "Course code or ID", optional(proposal.courseCode()));
            String weightText = promptWithDefault(
                    "Assessment weight",
                    proposal.assessmentWeight() == null
                            ? "0"
                            : proposal.assessmentWeight().toString());
            weight = Double.parseDouble(weightText);
            taskType = parseEnum(
                    AcademicTaskType.class,
                    promptWithDefault(
                            "Academic task type",
                            proposal.academicTaskType() == null
                                    ? AcademicTaskType.STUDY.name()
                                    : proposal.academicTaskType().name()));
        }
        return new ParsedTaskData(
                title, deadline, hours, difficulty, importance, kind,
                courseCode, weight, taskType);
    }

    private Task createTaskFromProposal(
            String id, ParsedTaskData proposal) {
        if (proposal.taskKind() == ParsedTaskKind.GENERAL) {
            return new GeneralTask(
                    id, proposal.title(), proposal.deadline(),
                    proposal.estimatedHours(), proposal.difficulty(),
                    proposal.importance());
        }
        Course course = proposal.courseCode() == null
                ? findCourse(prompt("Course ID: "))
                : findCourseByIdOrCode(proposal.courseCode());
        double weight = proposal.assessmentWeight() == null
                ? Double.parseDouble(prompt("Assessment weight (0-100): "))
                : proposal.assessmentWeight();
        AcademicTaskType taskType = proposal.academicTaskType() == null
                ? parseEnum(AcademicTaskType.class,
                prompt("Type (ASSIGNMENT/EXAM/QUIZ/PROJECT/READING/STUDY): "))
                : proposal.academicTaskType();
        return new AcademicTask(
                id, proposal.title(), proposal.deadline(),
                proposal.estimatedHours(), proposal.difficulty(),
                proposal.importance(), course, weight, taskType);
    }

    private void requestEffortSuggestion() {
        Task task = findTask(prompt("Task ID: "));
        double suggestion = controller.requestEffortSuggestion(task);
        output.printf("Current estimate: %.2f hours%n",
                task.getEstimatedHours());
        output.printf("AI suggested estimate: %.2f hours%n", suggestion);
        String replacement = prompt(
                "New estimate (blank to ignore; enter any positive value): ");
        if (replacement.isBlank()) {
            output.println("Suggestion ignored; estimate unchanged.");
            return;
        }
        double confirmedEstimate = Double.parseDouble(replacement);
        if (!Double.isFinite(confirmedEstimate) || confirmedEstimate <= 0) {
            throw new IllegalArgumentException(
                    "Confirmed estimate must be a positive finite value.");
        }
        task.setEstimatedHours(confirmedEstimate);
        controller.updateTask(task);
        output.println("Task estimate updated by user confirmation.");
    }

    private void explainSchedule() {
        String explanation = controller.explainSchedule(
                prompt("Schedule question: "));
        output.println("AI explanation: " + explanation);
    }

    private void requestReplanRecommendation() {
        ReplanRecommendation recommendation =
                controller.requestReplanRecommendation();
        output.println("AI impact summary: "
                + recommendation.impactSummary());
        output.println("Recommended option: "
                + recommendation.recommendedOption());
        output.println("Reason: " + recommendation.rationale());
        if (!yes(prompt("Apply this replanning option? (yes/no): "))) {
            output.println("Recommendation not applied.");
            return;
        }
        try {
            currentSchedule = controller.requestReplan(
                    recommendation.recommendedOption());
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
            output.println("No schedule has been generated.");
            return;
        }
        if (currentSchedule.getStudyBlocks().isEmpty()) {
            output.println("The current schedule has no study blocks.");
            return;
        }
        output.printf("Study blocks (%s):%n", currentSchedule.getStatus());
        currentSchedule.getStudyBlocks().forEach(block -> output.printf(
                "- %s | %s | %s to %s | %.2f hours | %s%n",
                block.getId(), block.getTask().getTitle(),
                block.getStart(), block.getEnd(),
                block.durationHours(), block.getProgressStatus()));
    }

    private void recordProgress() {
        if (currentSchedule == null) {
            output.println("Generate a schedule before recording progress.");
            return;
        }
        String blockId = prompt("Study block ID: ");
        StudyBlock block = currentSchedule.getStudyBlocks().stream()
                .filter(candidate -> candidate.getId().equals(blockId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "No study block exists with id " + blockId + "."));
        String outcome = prompt("Outcome (COMPLETED/PARTIAL/SKIPPED): ")
                .toUpperCase(Locale.ROOT);

        ProgressStatus status;
        double hours;
        switch (outcome) {
            case "COMPLETED" -> {
                status = ProgressStatus.COMPLETED;
                hours = block.durationHours();
            }
            case "PARTIAL" -> {
                status = ProgressStatus.PARTIAL;
                hours = Double.parseDouble(prompt("Completed hours: "));
            }
            case "SKIPPED" -> {
                status = ProgressStatus.SKIPPED;
                hours = 0;
            }
            default -> throw new IllegalArgumentException(
                    "Outcome must be COMPLETED, PARTIAL, or SKIPPED.");
        }

        controller.updateProgress(block, status, hours);
        output.println("Progress recorded. The schedule is now outdated; replan when ready.");
    }

    private void displayTaskProgress() {
        controller.getTasks().forEach(task -> knownTasks.putIfAbsent(task.getId(), task));
        if (knownTasks.isEmpty()) {
            output.println("No tasks have been added.");
            return;
        }
        output.println("Tasks:");
        knownTasks.values().forEach(task -> output.printf(
                "- %s | %s | %s | %.2f/%.2f hours completed | %.2f remaining%n",
                task.getId(), task.getTitle(), task.getStatus(),
                task.getCompletedHours(), task.getEstimatedHours(),
                task.getRemainingHours()));
    }

    private Course findCourse(String courseId) {
        return controller.getCourses().stream()
                .filter(course -> course.getId().equals(courseId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "No course exists with id " + courseId + "."));
    }

    private Course findCourseByIdOrCode(String value) {
        return controller.getCourses().stream()
                .filter(course -> course.getId().equalsIgnoreCase(value)
                        || course.getCode().equalsIgnoreCase(value))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "No course exists with id or code " + value + "."));
    }

    private Task findTask(String taskId) {
        return controller.getTasks().stream()
                .filter(task -> task.getId().equals(taskId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "No task exists with id " + taskId + "."));
    }

    private String prompt(String message) {
        output.print(message);
        if (!scanner.hasNextLine()) {
            throw new IllegalStateException("Input ended unexpectedly.");
        }
        return scanner.nextLine().trim();
    }

    private String promptWithDefault(String field, String defaultValue) {
        String value = prompt(field + " [" + defaultValue + "]: ");
        return value.isBlank() ? defaultValue : value;
    }

    private String optional(String value) {
        return value == null ? "" : value;
    }

    private boolean yes(String value) {
        return value.equalsIgnoreCase("yes")
                || value.equalsIgnoreCase("y");
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

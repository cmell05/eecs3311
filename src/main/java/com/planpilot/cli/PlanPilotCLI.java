package com.planpilot.cli;

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
import com.planpilot.domain.StudyPeriod;
import com.planpilot.domain.Task;
import com.planpilot.domain.UserPreferences;
import com.planpilot.event.PlanningEventPublisher;
import com.planpilot.service.AvailabilityService;
import com.planpilot.service.ConflictDetector;
import com.planpilot.service.CourseService;
import com.planpilot.service.ProgressService;
import com.planpilot.service.ScheduleInfeasibleException;
import com.planpilot.service.SchedulePlanner;
import com.planpilot.service.ScheduleService;
import com.planpilot.service.TaskService;
import com.planpilot.strategy.BalancedPriorityStrategy;

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
        PlanningEventPublisher publisher = new PlanningEventPublisher();
        CourseService courseService = new CourseService();
        TaskService taskService = new TaskService(courseService, publisher);
        AvailabilityService availabilityService = new AvailabilityService();
        ProgressService progressService = new ProgressService(publisher);
        ConflictDetector conflictDetector = new ConflictDetector();
        SchedulePlanner planner = new SchedulePlanner(
                new BalancedPriorityStrategy(), conflictDetector);
        ScheduleService scheduleService = new ScheduleService(
                taskService, availabilityService, planner,
                new UserPreferences(60, StudyPeriod.ANY, 10), publisher);
        return new PlanPilotController(
                courseService, taskService, availabilityService,
                scheduleService, progressService);
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
            case "0" -> running = false;
            default -> output.println("Unknown option. Enter a number from 0 to 9.");
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
        controller.markScheduleOutdated();
        output.println("Progress recorded. The schedule is now outdated; replan when ready.");
    }

    private void displayTaskProgress() {
        controller.getOpenTasks().forEach(task -> knownTasks.putIfAbsent(task.getId(), task));
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

    private String prompt(String message) {
        output.print(message);
        if (!scanner.hasNextLine()) {
            throw new IllegalStateException("Input ended unexpectedly.");
        }
        return scanner.nextLine().trim();
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

package com.planpilot.gui;

import com.planpilot.controller.PlanPilotController;
import com.planpilot.domain.AcademicTask;
import com.planpilot.domain.AcademicTaskType;
import com.planpilot.domain.AvailabilityBlock;
import com.planpilot.domain.AvailabilityType;
import com.planpilot.domain.Course;
import com.planpilot.domain.GeneralTask;
import com.planpilot.domain.PriorityLevel;
import com.planpilot.domain.StudyPeriod;
import com.planpilot.domain.Task;
import com.planpilot.domain.UserPreferences;
import com.planpilot.event.PlanningEventPublisher;
import com.planpilot.event.PlanningObserver;
import com.planpilot.service.AvailabilityService;
import com.planpilot.service.ConflictDetector;
import com.planpilot.service.CourseService;
import com.planpilot.service.ProgressService;
import com.planpilot.service.SchedulePlanner;
import com.planpilot.service.ScheduleService;
import com.planpilot.service.TaskService;
import com.planpilot.strategy.BalancedPriorityStrategy;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.Separator;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

public class PlanPilotGUI extends Application implements PlanningObserver {
    private PlanPilotController controller;
    private PlanningEventPublisher eventPublisher;
    private BorderPane root;
    private Label statusLabel;
    private Page currentPage = Page.COURSES;

    public static void main(String[] args) {
        launch(args);
    }

    @Override
    public void start(Stage stage) {
        ApplicationContext context = createApplicationContext();
        controller = context.controller();
        eventPublisher = context.publisher();
        eventPublisher.subscribe(this);

        root = new BorderPane();
        root.setLeft(createNavigation());
        statusLabel = new Label("Ready");
        statusLabel.setPadding(new Insets(8, 12, 8, 12));
        root.setBottom(statusLabel);
        showPage(Page.COURSES);

        stage.setTitle("PlanPilot");
        stage.setScene(new Scene(root, 1080, 700));
        stage.setMinWidth(860);
        stage.setMinHeight(560);
        stage.show();
    }

    @Override
    public void stop() {
        if (eventPublisher != null) {
            eventPublisher.unsubscribe(this);
        }
    }

    @Override
    public void onPlanningStateChanged() {
        Runnable refresh = () -> {
            if (root != null) {
                showPage(currentPage);
                statusLabel.setText("Planning state updated");
            }
        };
        if (Platform.isFxApplicationThread()) {
            refresh.run();
        } else {
            Platform.runLater(refresh);
        }
    }

    private VBox createNavigation() {
        Label title = new Label("PlanPilot");
        title.setStyle("-fx-font-size: 22px; -fx-font-weight: bold;");

        VBox navigation = new VBox(8, title);
        navigation.setPadding(new Insets(20));
        navigation.setPrefWidth(190);
        navigation.setStyle("-fx-background-color: #eef2f7;");
        for (Page page : Page.values()) {
            Button button = new Button(page.label);
            button.setMaxWidth(Double.MAX_VALUE);
            button.setAlignment(Pos.CENTER_LEFT);
            button.setOnAction(event -> showPage(page));
            navigation.getChildren().add(button);
        }
        return navigation;
    }

    private void showPage(Page page) {
        currentPage = page;
        root.setCenter(switch (page) {
            case COURSES -> createCoursesPage();
            case TASKS -> createTasksPage();
            case AVAILABILITY -> createAvailabilityPage();
            case SCHEDULE -> createPlaceholderPage(
                    "Schedule", "Generate and review schedule controls will be added later.");
            case PROGRESS -> createPlaceholderPage(
                    "Progress", "Progress reporting controls will be added later.");
        });
        statusLabel.setText(page.label);
    }

    private Node createCoursesPage() {
        ListView<String> courseList = new ListView<>(FXCollections.observableArrayList(
                controller.getCourses().stream()
                        .map(course -> course.getId() + " | " + course.getCode()
                                + " | " + course.getName())
                        .toList()));
        courseList.setPlaceholder(new Label("No courses added yet."));

        TextField idField = new TextField();
        TextField codeField = new TextField();
        TextField nameField = new TextField();
        GridPane form = createFormGrid();
        addFormRow(form, 0, "Course ID", idField);
        addFormRow(form, 1, "Code", codeField);
        addFormRow(form, 2, "Name", nameField);
        Button addButton = new Button("Add Course");
        addButton.setOnAction(event -> executeAction("Course added", () ->
                controller.addCourse(new Course(
                        required(idField, "Course ID"),
                        required(codeField, "Course code"),
                        required(nameField, "Course name")))));

        return createManagementPage(
                "Courses", "Add and review academic courses.", courseList,
                createEditor("Add Course", form, addButton));
    }

    private Node createTasksPage() {
        ListView<String> taskList = new ListView<>(FXCollections.observableArrayList(
                controller.getOpenTasks().stream().map(this::formatTask).toList()));
        taskList.setPlaceholder(new Label("No open tasks."));

        ComboBox<String> kindField = new ComboBox<>(
                FXCollections.observableArrayList("General", "Academic"));
        kindField.setValue("General");
        TextField idField = new TextField();
        TextField titleField = new TextField();
        DatePicker deadlineDate = new DatePicker(LocalDate.now());
        TextField deadlineTime = new TextField("17:00");
        TextField effortField = new TextField();
        ComboBox<Integer> difficultyField = new ComboBox<>(
                FXCollections.observableArrayList(1, 2, 3, 4, 5));
        difficultyField.setValue(3);
        ComboBox<PriorityLevel> importanceField = new ComboBox<>(
                FXCollections.observableArrayList(PriorityLevel.values()));
        importanceField.setValue(PriorityLevel.MEDIUM);
        ComboBox<String> courseField = new ComboBox<>(FXCollections.observableArrayList(
                controller.getCourses().stream().map(Course::getId).toList()));
        TextField weightField = new TextField();
        ComboBox<AcademicTaskType> academicTypeField = new ComboBox<>(
                FXCollections.observableArrayList(AcademicTaskType.values()));
        academicTypeField.setValue(AcademicTaskType.ASSIGNMENT);

        Runnable updateAcademicFields = () -> {
            boolean academic = "Academic".equals(kindField.getValue());
            courseField.setDisable(!academic);
            weightField.setDisable(!academic);
            academicTypeField.setDisable(!academic);
        };
        kindField.setOnAction(event -> updateAcademicFields.run());
        updateAcademicFields.run();

        GridPane form = createFormGrid();
        addFormRow(form, 0, "Task kind", kindField);
        addFormRow(form, 1, "Task ID", idField);
        addFormRow(form, 2, "Title", titleField);
        addFormRow(form, 3, "Deadline date", deadlineDate);
        addFormRow(form, 4, "Deadline time", deadlineTime);
        addFormRow(form, 5, "Estimated hours", effortField);
        addFormRow(form, 6, "Difficulty", difficultyField);
        addFormRow(form, 7, "Importance", importanceField);
        addFormRow(form, 8, "Course ID", courseField);
        addFormRow(form, 9, "Assessment weight", weightField);
        addFormRow(form, 10, "Academic type", academicTypeField);

        Button addButton = new Button("Add Task");
        addButton.setOnAction(event -> executeAction("Task added", () -> {
            LocalDateTime deadline = combineDateAndTime(
                    deadlineDate, deadlineTime, "Deadline");
            double effort = positiveDouble(effortField, "Estimated hours");
            Task task;
            if ("Academic".equals(kindField.getValue())) {
                task = new AcademicTask(
                        required(idField, "Task ID"),
                        required(titleField, "Task title"),
                        deadline,
                        effort,
                        requiredValue(difficultyField.getValue(), "Difficulty"),
                        requiredValue(importanceField.getValue(), "Importance"),
                        findCourse(courseField.getValue()),
                        nonNegativeDouble(weightField, "Assessment weight"),
                        requiredValue(academicTypeField.getValue(), "Academic type"));
            } else {
                task = new GeneralTask(
                        required(idField, "Task ID"),
                        required(titleField, "Task title"),
                        deadline,
                        effort,
                        requiredValue(difficultyField.getValue(), "Difficulty"),
                        requiredValue(importanceField.getValue(), "Importance"));
            }
            controller.addTask(task);
        }));

        return createManagementPage(
                "Tasks", "Add academic or general tasks and review open work.",
                taskList, createEditor("Add Task", form, addButton));
    }

    private Node createAvailabilityPage() {
        List<AvailabilityBlock> entries = controller.getAvailability(
                LocalDateTime.MIN, LocalDateTime.MAX);
        ListView<String> availabilityList = new ListView<>(
                FXCollections.observableArrayList(entries.stream()
                        .map(block -> block.getId() + " | " + block.getType()
                                + " | " + block.getStart() + " to " + block.getEnd())
                        .toList()));
        availabilityList.setPlaceholder(new Label("No availability entries added yet."));

        TextField idField = new TextField();
        DatePicker dateField = new DatePicker(LocalDate.now());
        TextField startTimeField = new TextField("09:00");
        TextField endTimeField = new TextField("10:00");
        ComboBox<AvailabilityType> typeField = new ComboBox<>(
                FXCollections.observableArrayList(AvailabilityType.values()));
        typeField.setValue(AvailabilityType.AVAILABLE);

        GridPane form = createFormGrid();
        addFormRow(form, 0, "Entry ID", idField);
        addFormRow(form, 1, "Date", dateField);
        addFormRow(form, 2, "Start time", startTimeField);
        addFormRow(form, 3, "End time", endTimeField);
        addFormRow(form, 4, "Type", typeField);

        Button addButton = new Button("Add Availability");
        addButton.setOnAction(event -> executeAction("Availability added", () -> {
            LocalDate date = requiredValue(dateField.getValue(), "Date");
            controller.addAvailability(new AvailabilityBlock(
                    required(idField, "Entry ID"),
                    LocalDateTime.of(date, parseTime(startTimeField, "Start time")),
                    LocalDateTime.of(date, parseTime(endTimeField, "End time")),
                    requiredValue(typeField.getValue(), "Availability type")));
        }));

        return createManagementPage(
                "Availability", "Add free study time or fixed commitments.",
                availabilityList,
                createEditor("Add Availability or Commitment", form, addButton));
    }

    private Node createPlaceholderPage(String title, String description) {
        return createPage(title, description, new Label("Navigation shell ready."));
    }

    private Node createManagementPage(
            String title, String description, Node list, Node editor) {
        HBox content = new HBox(20, list, editor);
        HBox.setHgrow(list, Priority.ALWAYS);
        if (list instanceof ListView<?> listView) {
            listView.setMinWidth(360);
        }
        return createPage(title, description, content);
    }

    private VBox createEditor(String title, Node form, Button action) {
        Label heading = new Label(title);
        heading.setStyle("-fx-font-size: 16px; -fx-font-weight: bold;");
        VBox editor = new VBox(12, heading, new Separator(), form, action);
        editor.setPrefWidth(380);
        return editor;
    }

    private GridPane createFormGrid() {
        GridPane form = new GridPane();
        form.setHgap(10);
        form.setVgap(8);
        return form;
    }

    private void addFormRow(GridPane form, int row, String label, Node field) {
        form.add(new Label(label + ":"), 0, row);
        form.add(field, 1, row);
        GridPane.setHgrow(field, Priority.ALWAYS);
        if (field instanceof TextField textField) {
            textField.setMaxWidth(Double.MAX_VALUE);
        }
    }

    private VBox createPage(String title, String description, Node content) {
        Label heading = new Label(title);
        heading.setStyle("-fx-font-size: 24px; -fx-font-weight: bold;");
        Label subtitle = new Label(description);
        VBox page = new VBox(12, heading, subtitle, content);
        page.setPadding(new Insets(24));
        VBox.setVgrow(content, Priority.ALWAYS);
        return page;
    }

    private void executeAction(String successMessage, Runnable action) {
        try {
            action.run();
            showPage(currentPage);
            statusLabel.setText(successMessage);
        } catch (RuntimeException exception) {
            showError(exception.getMessage() == null
                    ? exception.getClass().getSimpleName()
                    : exception.getMessage());
        }
    }

    private void showError(String message) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle("PlanPilot Error");
        alert.setHeaderText("Unable to save");
        alert.setContentText(message);
        alert.showAndWait();
        statusLabel.setText("Error: " + message);
    }

    private String formatTask(Task task) {
        String prefix = task instanceof AcademicTask academicTask
                ? academicTask.getCourse().getCode() + " | "
                : "General | ";
        return task.getId() + " | " + prefix + task.getTitle()
                + " | " + task.getStatus()
                + " | " + task.getRemainingHours() + "h remaining";
    }

    private Course findCourse(String courseId) {
        if (courseId == null || courseId.isBlank()) {
            throw new IllegalArgumentException("Course ID is required.");
        }
        return controller.getCourses().stream()
                .filter(course -> course.getId().equals(courseId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "No course exists with ID " + courseId + "."));
    }

    private LocalDateTime combineDateAndTime(
            DatePicker dateField, TextField timeField, String fieldName) {
        LocalDate date = requiredValue(dateField.getValue(), fieldName + " date");
        return LocalDateTime.of(date, parseTime(timeField, fieldName + " time"));
    }

    private LocalTime parseTime(TextField field, String fieldName) {
        try {
            return LocalTime.parse(required(field, fieldName));
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException(
                    fieldName + " must use HH:MM format.");
        }
    }

    private double positiveDouble(TextField field, String fieldName) {
        double value = parseDouble(field, fieldName);
        if (value <= 0) {
            throw new IllegalArgumentException(fieldName + " must be positive.");
        }
        return value;
    }

    private double nonNegativeDouble(TextField field, String fieldName) {
        double value = parseDouble(field, fieldName);
        if (value < 0) {
            throw new IllegalArgumentException(fieldName + " cannot be negative.");
        }
        return value;
    }

    private double parseDouble(TextField field, String fieldName) {
        try {
            double value = Double.parseDouble(required(field, fieldName));
            if (!Double.isFinite(value)) {
                throw new NumberFormatException();
            }
            return value;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(fieldName + " must be a valid number.");
        }
    }

    private String required(TextField field, String fieldName) {
        String value = field.getText() == null ? "" : field.getText().trim();
        if (value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " is required.");
        }
        return value;
    }

    private <T> T requiredValue(T value, String fieldName) {
        if (value == null) {
            throw new IllegalArgumentException(fieldName + " is required.");
        }
        return value;
    }

    private ApplicationContext createApplicationContext() {
        PlanningEventPublisher publisher = new PlanningEventPublisher();
        CourseService courseService = new CourseService();
        TaskService taskService = new TaskService(courseService, publisher);
        AvailabilityService availabilityService = new AvailabilityService();
        ProgressService progressService = new ProgressService(publisher);
        ConflictDetector conflictDetector = new ConflictDetector();
        SchedulePlanner schedulePlanner = new SchedulePlanner(
                new BalancedPriorityStrategy(), conflictDetector);
        ScheduleService scheduleService = new ScheduleService(
                taskService, availabilityService, schedulePlanner,
                new UserPreferences(60, StudyPeriod.ANY, 10), publisher);
        PlanPilotController planPilotController = new PlanPilotController(
                courseService, taskService, availabilityService,
                scheduleService, progressService);
        return new ApplicationContext(planPilotController, publisher);
    }

    private enum Page {
        COURSES("Courses"),
        TASKS("Tasks"),
        AVAILABILITY("Availability"),
        SCHEDULE("Schedule"),
        PROGRESS("Progress");

        private final String label;

        Page(String label) {
            this.label = label;
        }
    }

    private record ApplicationContext(
            PlanPilotController controller, PlanningEventPublisher publisher) {
    }
}

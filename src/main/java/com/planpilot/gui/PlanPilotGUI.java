package com.planpilot.gui;

import com.planpilot.controller.PlanPilotController;
import com.planpilot.domain.AcademicTask;
import com.planpilot.domain.AcademicTaskType;
import com.planpilot.domain.AvailabilityBlock;
import com.planpilot.domain.AvailabilityType;
import com.planpilot.domain.Course;
import com.planpilot.domain.GeneralTask;
import com.planpilot.domain.PriorityLevel;
import com.planpilot.domain.ProgressRecord;
import com.planpilot.domain.ProgressStatus;
import com.planpilot.domain.Schedule;
import com.planpilot.domain.StudyPeriod;
import com.planpilot.domain.StudyBlock;
import com.planpilot.domain.Task;
import com.planpilot.domain.TaskStatus;
import com.planpilot.domain.UserPreferences;
import com.planpilot.event.PlanningEventPublisher;
import com.planpilot.event.PlanningObserver;
import com.planpilot.service.AvailabilityService;
import com.planpilot.service.ConflictDetector;
import com.planpilot.service.CourseService;
import com.planpilot.service.ProgressService;
import com.planpilot.service.ReplanOption;
import com.planpilot.service.SchedulePlanner;
import com.planpilot.service.ScheduleService;
import com.planpilot.service.ScheduleInfeasibleException;
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
import java.util.function.Supplier;

public class PlanPilotGUI extends Application implements PlanningObserver {
    private PlanPilotController controller;
    private PlanningEventPublisher eventPublisher;
    private BorderPane root;
    private Label statusLabel;
    private Page currentPage = Page.COURSES;
    private Schedule displayedSchedule;
    private LocalDate planningStart = LocalDate.now();
    private LocalDate planningEnd = LocalDate.now().plusDays(7);
    private String scheduleWarning = "";
    private boolean scheduleMayBeOutdated;
    private boolean scheduleGenerationInProgress;

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
                if (displayedSchedule != null && !scheduleGenerationInProgress) {
                    scheduleMayBeOutdated = true;
                }
                showPage(currentPage);
                statusLabel.setText(scheduleMayBeOutdated
                        ? "Planning state updated; the schedule may be outdated"
                        : "Planning state updated");
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
            case SCHEDULE -> createSchedulePage();
            case PROGRESS -> createProgressPage();
        });
        statusLabel.setText(page.label);
    }

    private Node createCoursesPage() {
        List<Course> courses = controller.getCourses();
        ListView<String> courseList = new ListView<>(FXCollections.observableArrayList(
                courses.stream()
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

        Button updateButton = new Button("Update");
        Button deleteButton = new Button("Delete");
        Button clearButton = new Button("New/Clear");
        updateButton.setDisable(true);
        deleteButton.setDisable(true);
        courseList.getSelectionModel().selectedIndexProperty().addListener(
                (observable, oldIndex, newIndex) -> {
                    int index = newIndex.intValue();
                    if (index < 0 || index >= courses.size()) {
                        return;
                    }
                    Course selected = courses.get(index);
                    idField.setText(selected.getId());
                    idField.setDisable(true);
                    codeField.setText(selected.getCode());
                    nameField.setText(selected.getName());
                    updateButton.setDisable(false);
                    deleteButton.setDisable(false);
                });
        updateButton.setOnAction(event -> executeAction("Course updated", () -> {
            Course selected = selectedItem(courseList, courses, "course");
            selected.setCode(required(codeField, "Course code"));
            selected.rename(required(nameField, "Course name"));
            controller.updateCourse(selected);
        }));
        deleteButton.setOnAction(event -> executeAction("Course deleted", () ->
                controller.deleteCourse(
                        selectedItem(courseList, courses, "course").getId())));
        clearButton.setOnAction(event -> showPage(Page.COURSES));

        return createManagementPage(
                "Courses", "Add and review academic courses.", courseList,
                createEditor(
                        "Add or Edit Course", form,
                        addButton, updateButton, deleteButton, clearButton));
    }

    private Node createTasksPage() {
        List<Task> tasks = controller.getTasks();
        ListView<String> taskList = new ListView<>(FXCollections.observableArrayList(
                tasks.stream().map(this::formatTask).toList()));
        taskList.setPlaceholder(new Label("No tasks have been added."));

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

        Supplier<Task> taskFromForm = () -> {
            LocalDateTime deadline = combineDateAndTime(
                    deadlineDate, deadlineTime, "Deadline");
            double effort = positiveDouble(effortField, "Estimated hours");
            if ("Academic".equals(kindField.getValue())) {
                return new AcademicTask(
                        required(idField, "Task ID"),
                        required(titleField, "Task title"),
                        deadline,
                        effort,
                        requiredValue(difficultyField.getValue(), "Difficulty"),
                        requiredValue(importanceField.getValue(), "Importance"),
                        findCourse(courseField.getValue()),
                        nonNegativeDouble(weightField, "Assessment weight"),
                        requiredValue(academicTypeField.getValue(), "Academic type"));
            }
            return new GeneralTask(
                    required(idField, "Task ID"),
                    required(titleField, "Task title"),
                    deadline,
                    effort,
                    requiredValue(difficultyField.getValue(), "Difficulty"),
                    requiredValue(importanceField.getValue(), "Importance"));
        };

        Button addButton = new Button("Add Task");
        Button updateButton = new Button("Update");
        Button deleteButton = new Button("Delete");
        Button clearButton = new Button("New/Clear");
        updateButton.setDisable(true);
        deleteButton.setDisable(true);
        addButton.setOnAction(event -> executeAction(
                "Task added", () -> controller.addTask(taskFromForm.get())));
        taskList.getSelectionModel().selectedIndexProperty().addListener(
                (observable, oldIndex, newIndex) -> {
                    int index = newIndex.intValue();
                    if (index < 0 || index >= tasks.size()) {
                        return;
                    }
                    Task selected = tasks.get(index);
                    idField.setText(selected.getId());
                    idField.setDisable(true);
                    titleField.setText(selected.getTitle());
                    deadlineDate.setValue(selected.getDeadline().toLocalDate());
                    deadlineTime.setText(selected.getDeadline().toLocalTime().toString());
                    effortField.setText(Double.toString(selected.getEstimatedHours()));
                    difficultyField.setValue(selected.getDifficulty());
                    importanceField.setValue(selected.getUserImportance());
                    if (selected instanceof AcademicTask academicTask) {
                        kindField.setValue("Academic");
                        courseField.setValue(academicTask.getCourse().getId());
                        weightField.setText(Double.toString(
                                academicTask.getAssessmentWeight()));
                        academicTypeField.setValue(academicTask.getTaskType());
                    } else {
                        kindField.setValue("General");
                        courseField.setValue(null);
                        weightField.clear();
                    }
                    updateAcademicFields.run();
                    updateButton.setDisable(false);
                    deleteButton.setDisable(false);
                });
        updateButton.setOnAction(event -> executeAction("Task updated", () -> {
            Task selected = selectedItem(taskList, tasks, "task");
            Task updated = taskFromForm.get();
            updated.setCompletedHours(selected.getCompletedHours());
            if (selected.getStatus() == TaskStatus.SKIPPED) {
                updated.markSkipped();
            }
            controller.updateTask(updated);
        }));
        deleteButton.setOnAction(event -> executeAction("Task deleted", () ->
                controller.deleteTask(
                        selectedItem(taskList, tasks, "task").getId())));
        clearButton.setOnAction(event -> showPage(Page.TASKS));

        return createManagementPage(
                "Tasks", "Add academic or general tasks and review open work.",
                taskList, createEditor(
                        "Add or Edit Task", form,
                        addButton, updateButton, deleteButton, clearButton));
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

        Button updateButton = new Button("Update");
        Button deleteButton = new Button("Delete");
        Button clearButton = new Button("New/Clear");
        updateButton.setDisable(true);
        deleteButton.setDisable(true);
        availabilityList.getSelectionModel().selectedIndexProperty().addListener(
                (observable, oldIndex, newIndex) -> {
                    int index = newIndex.intValue();
                    if (index < 0 || index >= entries.size()) {
                        return;
                    }
                    AvailabilityBlock selected = entries.get(index);
                    idField.setText(selected.getId());
                    idField.setDisable(true);
                    dateField.setValue(selected.getStart().toLocalDate());
                    startTimeField.setText(selected.getStart().toLocalTime().toString());
                    endTimeField.setText(selected.getEnd().toLocalTime().toString());
                    typeField.setValue(selected.getType());
                    updateButton.setDisable(false);
                    deleteButton.setDisable(false);
                });
        updateButton.setOnAction(event -> executeAction(
                "Availability updated", () -> {
                    AvailabilityBlock selected = selectedItem(
                            availabilityList, entries, "availability entry");
                    LocalDate date = requiredValue(dateField.getValue(), "Date");
                    controller.updateAvailability(new AvailabilityBlock(
                            selected.getId(),
                            LocalDateTime.of(
                                    date, parseTime(startTimeField, "Start time")),
                            LocalDateTime.of(
                                    date, parseTime(endTimeField, "End time")),
                            requiredValue(
                                    typeField.getValue(), "Availability type")));
                }));
        deleteButton.setOnAction(event -> executeAction(
                "Availability deleted", () -> controller.deleteAvailability(
                        selectedItem(
                                availabilityList, entries,
                                "availability entry").getId())));
        clearButton.setOnAction(event -> showPage(Page.AVAILABILITY));

        return createManagementPage(
                "Availability", "Add free study time or fixed commitments.",
                availabilityList,
                createEditor(
                        "Add or Edit Availability", form,
                        addButton, updateButton, deleteButton, clearButton));
    }

    private Node createSchedulePage() {
        DatePicker startField = new DatePicker(planningStart);
        DatePicker endField = new DatePicker(planningEnd);
        Button generateButton = new Button("Generate Schedule");
        generateButton.setOnAction(event -> generateSchedule(startField, endField));

        HBox controls = new HBox(
                10, new Label("Start:"), startField,
                new Label("End:"), endField, generateButton);
        controls.setAlignment(Pos.CENTER_LEFT);

        ComboBox<ReplanOption> replanField = new ComboBox<>(
                FXCollections.observableArrayList(ReplanOption.values()));
        replanField.setValue(ReplanOption.RESCHEDULE_INCOMPLETE_WORK);
        Button replanButton = new Button("Apply Replan Option");
        replanButton.setDisable(displayedSchedule == null);
        replanButton.setOnAction(event -> replanSchedule(replanField.getValue()));
        HBox replanControls = new HBox(
                10, new Label("Replan:"), replanField, replanButton);
        replanControls.setAlignment(Pos.CENTER_LEFT);

        ListView<String> blockList = new ListView<>(FXCollections.observableArrayList(
                scheduleBlocks().stream().map(this::formatStudyBlock).toList()));
        blockList.setPlaceholder(new Label(
                displayedSchedule == null
                        ? "Choose a planning window and generate a schedule."
                        : "No study blocks were needed for this window."));

        Label summary = new Label(scheduleSummary());
        Label warning = new Label(scheduleWarningText());
        warning.setWrapText(true);
        warning.setStyle("-fx-text-fill: #9a5a00; -fx-font-weight: bold;");
        warning.setVisible(!warning.getText().isBlank());
        warning.setManaged(warning.isVisible());

        UserPreferences preferences = controller.getUserPreferences();
        TextField maxSessionField = new TextField(
                Integer.toString(preferences.getMaxSessionLength()));
        TextField breakField = new TextField(
                Integer.toString(preferences.getBreakLength()));
        ComboBox<StudyPeriod> periodField = new ComboBox<>(
                FXCollections.observableArrayList(StudyPeriod.values()));
        periodField.setValue(preferences.getPreferredStudyPeriod());
        GridPane preferenceForm = createFormGrid();
        addFormRow(preferenceForm, 0, "Max session (minutes)", maxSessionField);
        addFormRow(preferenceForm, 1, "Break (minutes)", breakField);
        addFormRow(preferenceForm, 2, "Preferred period", periodField);
        Button savePreferencesButton = new Button("Save Preferences");
        savePreferencesButton.setOnAction(event -> executeAction(
                "Preferences saved; replan when ready", () ->
                        controller.updateUserPreferences(new UserPreferences(
                                positiveInt(
                                        maxSessionField, "Maximum session length"),
                                requiredValue(
                                        periodField.getValue(), "Preferred period"),
                                nonNegativeInt(breakField, "Break length")))));
        VBox preferenceEditor = createEditor(
                "Scheduling Preferences", preferenceForm, savePreferencesButton);
        preferenceEditor.setPrefWidth(300);

        HBox scheduleContent = new HBox(20, blockList, preferenceEditor);
        HBox.setHgrow(blockList, Priority.ALWAYS);
        VBox content = new VBox(
                12, controls, replanControls, summary, warning, scheduleContent);
        VBox.setVgrow(scheduleContent, Priority.ALWAYS);
        return createPage(
                "Schedule", "Generate a deterministic schedule for a planning window.",
                content);
    }

    private void generateSchedule(DatePicker startField, DatePicker endField) {
        try {
            planningStart = requiredValue(startField.getValue(), "Planning start date");
            planningEnd = requiredValue(endField.getValue(), "Planning end date");
            if (planningEnd.isBefore(planningStart)) {
                throw new IllegalArgumentException(
                        "Planning end date cannot be before the start date.");
            }

            scheduleGenerationInProgress = true;
            displayedSchedule = controller.generateSchedule(planningStart, planningEnd);
            scheduleWarning = "";
            scheduleMayBeOutdated = false;
            showPage(Page.SCHEDULE);
            statusLabel.setText("Schedule generated");
        } catch (ScheduleInfeasibleException exception) {
            displayedSchedule = exception.getPartialSchedule();
            scheduleWarning = String.format(
                    "The full workload does not fit. %.2f hours remain unscheduled; "
                            + "the blocks below are the feasible partial schedule.",
                    exception.getWorkloadDeficitHours());
            scheduleMayBeOutdated = false;
            showPage(Page.SCHEDULE);
            statusLabel.setText("Schedule is infeasible");
        } catch (RuntimeException exception) {
            showError(errorMessage(exception));
        } finally {
            scheduleGenerationInProgress = false;
        }
    }

    private void replanSchedule(ReplanOption option) {
        try {
            scheduleGenerationInProgress = true;
            displayedSchedule = controller.requestReplan(
                    requiredValue(option, "Replan option"));
            planningStart = displayedSchedule.getStartDate();
            planningEnd = displayedSchedule.getEndDate();
            scheduleWarning = "";
            scheduleMayBeOutdated = false;
            showPage(Page.SCHEDULE);
            statusLabel.setText("Replan option applied");
        } catch (ScheduleInfeasibleException exception) {
            displayedSchedule = exception.getPartialSchedule();
            planningStart = displayedSchedule.getStartDate();
            planningEnd = displayedSchedule.getEndDate();
            scheduleWarning = String.format(
                    "The replanned workload does not fit. %.2f hours remain "
                            + "unscheduled; the feasible partial schedule is shown.",
                    exception.getWorkloadDeficitHours());
            scheduleMayBeOutdated = false;
            showPage(Page.SCHEDULE);
            statusLabel.setText("Replanned schedule is infeasible");
        } catch (RuntimeException exception) {
            showError(errorMessage(exception));
        } finally {
            scheduleGenerationInProgress = false;
        }
    }

    private Node createProgressPage() {
        List<StudyBlock> blocks = scheduleBlocks();
        ListView<String> blockList = new ListView<>(FXCollections.observableArrayList(
                blocks.stream().map(this::formatProgressBlock).toList()));
        blockList.setPlaceholder(new Label(
                "Generate a schedule before recording progress."));
        ListView<String> historyList = new ListView<>();
        historyList.setPlaceholder(new Label(
                "Select a study block to view its task history."));
        Runnable refreshHistory = () -> {
            int index = blockList.getSelectionModel().getSelectedIndex();
            if (index < 0 || index >= blocks.size()) {
                historyList.getItems().clear();
                return;
            }
            historyList.setItems(FXCollections.observableArrayList(
                    controller.getProgressHistory(blocks.get(index).getTask())
                            .stream()
                            .map(this::formatProgressRecord)
                            .toList()));
        };
        blockList.getSelectionModel().selectedIndexProperty().addListener(
                (observable, oldIndex, newIndex) -> refreshHistory.run());

        Label selectionHelp = new Label(
                "Select a study block, then record its result.");
        selectionHelp.setWrapText(true);
        TextField partialHoursField = new TextField();
        partialHoursField.setPromptText("Hours completed");

        Button completedButton = new Button("Completed");
        completedButton.setMaxWidth(Double.MAX_VALUE);
        completedButton.setOnAction(event -> {
            StudyBlock block = selectedBlock(blockList, blocks);
            if (block != null) {
                recordProgress(block, ProgressStatus.COMPLETED,
                        block.durationHours(), "Work marked completed");
            }
        });

        Button partialButton = new Button("Partially Completed");
        partialButton.setMaxWidth(Double.MAX_VALUE);
        partialButton.setOnAction(event -> {
            StudyBlock block = selectedBlock(blockList, blocks);
            if (block != null) {
                try {
                    double hours = positiveDouble(
                            partialHoursField, "Completed hours");
                    recordProgress(block, ProgressStatus.PARTIAL, hours,
                            "Partial progress recorded");
                } catch (RuntimeException exception) {
                    showError(errorMessage(exception));
                }
            }
        });

        Button skippedButton = new Button("Skipped");
        skippedButton.setMaxWidth(Double.MAX_VALUE);
        skippedButton.setOnAction(event -> {
            StudyBlock block = selectedBlock(blockList, blocks);
            if (block != null) {
                recordProgress(block, ProgressStatus.SKIPPED, 0,
                        "Work marked skipped");
            }
        });

        Label warning = new Label(scheduleMayBeOutdated && displayedSchedule != null
                ? "Planning data changed. This schedule may be outdated; "
                        + "replanning still requires user action."
                : "");
        warning.setWrapText(true);
        warning.setStyle("-fx-text-fill: #9a5a00; -fx-font-weight: bold;");
        warning.setVisible(!warning.getText().isBlank());
        warning.setManaged(warning.isVisible());

        VBox editor = new VBox(
                10, selectionHelp, new Label("Partial hours:"), partialHoursField,
                completedButton, partialButton, skippedButton);
        editor.setPrefWidth(240);
        boolean noBlocks = blocks.isEmpty();
        completedButton.setDisable(noBlocks);
        partialButton.setDisable(noBlocks);
        skippedButton.setDisable(noBlocks);

        VBox historyPanel = new VBox(
                8, new Label("Progress History for Selected Task"), historyList);
        historyPanel.setPrefWidth(340);
        VBox.setVgrow(historyList, Priority.ALWAYS);
        HBox main = new HBox(20, blockList, editor, historyPanel);
        HBox.setHgrow(blockList, Priority.ALWAYS);
        VBox content = new VBox(12, warning, main);
        VBox.setVgrow(main, Priority.ALWAYS);
        if (!blocks.isEmpty()) {
            blockList.getSelectionModel().selectFirst();
        }
        return createPage(
                "Progress", "Record outcomes for scheduled study blocks.", content);
    }

    private StudyBlock selectedBlock(
            ListView<String> blockList, List<StudyBlock> blocks) {
        int selectedIndex = blockList.getSelectionModel().getSelectedIndex();
        if (selectedIndex < 0 || selectedIndex >= blocks.size()) {
            showError("Select a study block first.");
            return null;
        }
        return blocks.get(selectedIndex);
    }

    private void recordProgress(
            StudyBlock block, ProgressStatus progressStatus,
            double hours, String successMessage) {
        executeAction(successMessage,
                () -> controller.updateProgress(block, progressStatus, hours));
    }

    private List<StudyBlock> scheduleBlocks() {
        return displayedSchedule == null
                ? List.of()
                : displayedSchedule.getStudyBlocks();
    }

    private String scheduleSummary() {
        if (displayedSchedule == null) {
            return "No schedule has been generated.";
        }
        return displayedSchedule.getStartDate() + " to "
                + displayedSchedule.getEndDate() + " | "
                + scheduleBlocks().size() + " study block(s) | "
                + displayedSchedule.getStatus();
    }

    private String scheduleWarningText() {
        String outdatedWarning = scheduleMayBeOutdated && displayedSchedule != null
                ? "Planning data changed. This schedule may be outdated; "
                        + "generate it again only when you choose to replan."
                : "";
        if (scheduleWarning.isBlank()) {
            return outdatedWarning;
        }
        return outdatedWarning.isBlank()
                ? scheduleWarning
                : scheduleWarning + " " + outdatedWarning;
    }

    private String formatStudyBlock(StudyBlock block) {
        return block.getStart() + " to " + block.getEnd().toLocalTime()
                + " | " + block.getTask().getTitle()
                + " | " + String.format("%.2fh", block.durationHours());
    }

    private String formatProgressBlock(StudyBlock block) {
        Task task = block.getTask();
        return formatStudyBlock(block)
                + " | block: " + block.getProgressStatus()
                + " | task: " + String.format(
                        "%.2f/%.2fh (%s)", task.getCompletedHours(),
                        task.getEstimatedHours(), task.getStatus());
    }

    private String formatProgressRecord(ProgressRecord record) {
        return record.getRecordedAt() + " | " + record.getStatus()
                + " | " + String.format(
                        "%.2fh", record.getCompletedHours())
                + " | block " + record.getStudyBlock().getId();
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

    private VBox createEditor(String title, Node form, Node... actions) {
        Label heading = new Label(title);
        heading.setStyle("-fx-font-size: 16px; -fx-font-weight: bold;");
        HBox actionBar = new HBox(8, actions);
        VBox editor = new VBox(12, heading, new Separator(), form, actionBar);
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
            showError(errorMessage(exception));
        }
    }

    private String errorMessage(RuntimeException exception) {
        return exception.getMessage() == null
                ? exception.getClass().getSimpleName()
                : exception.getMessage();
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

    private int positiveInt(TextField field, String fieldName) {
        int value = parseInt(field, fieldName);
        if (value <= 0) {
            throw new IllegalArgumentException(fieldName + " must be positive.");
        }
        return value;
    }

    private int nonNegativeInt(TextField field, String fieldName) {
        int value = parseInt(field, fieldName);
        if (value < 0) {
            throw new IllegalArgumentException(fieldName + " cannot be negative.");
        }
        return value;
    }

    private int parseInt(TextField field, String fieldName) {
        try {
            return Integer.parseInt(required(field, fieldName));
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(
                    fieldName + " must be a whole number.");
        }
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

    private <T> T selectedItem(
            ListView<String> listView, List<T> items, String itemName) {
        int index = listView.getSelectionModel().getSelectedIndex();
        if (index < 0 || index >= items.size()) {
            throw new IllegalArgumentException(
                    "Select a " + itemName + " first.");
        }
        return items.get(index);
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

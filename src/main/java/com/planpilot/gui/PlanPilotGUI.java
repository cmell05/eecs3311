package com.planpilot.gui;

import com.planpilot.ai.ParsedTaskData;
import com.planpilot.ai.ParsedTaskKind;
import com.planpilot.ai.RecoveryPlan;
import com.planpilot.ai.ReplanRecommendation;
import com.planpilot.config.PlanPilotApplicationContext;
import com.planpilot.config.PlanPilotApplicationFactory;
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
import com.planpilot.service.ReplanOption;
import com.planpilot.service.ScheduleInfeasibleException;
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
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.util.StringConverter;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
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
    private ScheduleInfeasibleException lastInfeasibility;
    private ReplanRecommendation pendingReplanRecommendation;
    private final java.util.Map<Page, Button> navigationButtons =
            new java.util.EnumMap<>(Page.class);

    public static void main(String[] args) {
        launch(args);
    }

    @Override
    public void start(Stage stage) {
        PlanPilotApplicationContext context =
                PlanPilotApplicationFactory.createDefault();
        controller = context.controller();
        eventPublisher = context.eventPublisher();
        eventPublisher.subscribe(this);

        root = new BorderPane();
        root.getStyleClass().add("app-root");
        root.setLeft(createNavigation());
        statusLabel = new Label("Ready");
        statusLabel.setPadding(new Insets(12, 20, 12, 20));
        statusLabel.getStyleClass().add("status-bar");
        root.setBottom(statusLabel);
        showPage(Page.COURSES);

        stage.setTitle("PlanPilot");
        Scene scene = new Scene(root, 1180, 780);
        var stylesheet = getClass().getResource("/com/planpilot/gui/planpilot.css");
        if (stylesheet != null) {
            scene.getStylesheets().add(stylesheet.toExternalForm());
        }
        stage.setScene(scene);
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
        title.getStyleClass().add("brand-title");

        VBox navigation = new VBox(8, title);
        navigation.setPadding(new Insets(20));
        navigation.setPrefWidth(220);
        navigation.getStyleClass().add("sidebar");
        Label tagline = new Label("Your study workspace");
        tagline.getStyleClass().add("sidebar-caption");
        navigation.getChildren().add(tagline);
        for (Page page : Page.values()) {
            Button button = new Button(page.label);
            button.setMaxWidth(Double.MAX_VALUE);
            button.setAlignment(Pos.CENTER_LEFT);
            button.getStyleClass().add("nav-button");
            button.setTooltip(new Tooltip("Open " + page.label));
            button.setOnAction(event -> showPage(page));
            navigationButtons.put(page, button);
            navigation.getChildren().add(button);
        }
        return navigation;
    }

    private void showPage(Page page) {
        currentPage = page;
        navigationButtons.forEach((item, button) -> {
            button.getStyleClass().remove("nav-button-active");
            if (item == page) {
                button.getStyleClass().add("nav-button-active");
            }
        });
        root.setCenter(switch (page) {
            case COURSES -> createCoursesPage();
            case TASKS -> createTasksPage();
            case AVAILABILITY -> createAvailabilityPage();
            case SCHEDULE -> createSchedulePage();
            case PROGRESS -> createProgressPage();
            case AI_ASSISTANT -> createAiAssistantPage();
        });
        statusLabel.setText(page.label);
    }

    private Node createCoursesPage() {
        List<Course> courses = controller.getCourses();
        ListView<String> courseList = new ListView<>(FXCollections.observableArrayList(
                courses.stream()
                        .map(course -> course.getCode() + "  ·  " + course.getName())
                        .toList()));
        courseList.setPlaceholder(new Label("No courses added yet."));

        TextField codeField = new TextField();
        TextField nameField = new TextField();
        GridPane form = createFormGrid();
        addFormRow(form, 0, "Course code", codeField);
        addFormRow(form, 1, "Course name", nameField);
        Button addButton = new Button("Add Course");
        addButton.setOnAction(event -> executeAction("Course added", () ->
                controller.addCourse(new Course(
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
        ComboBox<Course> courseField = new ComboBox<>(FXCollections.observableArrayList(
                controller.getCourses()));
        courseField.setConverter(courseConverter());
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
        addFormRow(form, 1, "Task title", titleField);
        addFormRow(form, 2, "Due date", deadlineDate);
        addFormRow(form, 3, "Due time", deadlineTime);
        addFormRow(form, 4, "Estimated hours", effortField);
        addFormRow(form, 5, "Difficulty (1–5)", difficultyField);
        addFormRow(form, 6, "Importance", importanceField);
        addFormRow(form, 7, "Course", courseField);
        addFormRow(form, 8, "Assessment weight (%)", weightField);
        addFormRow(form, 9, "Academic type", academicTypeField);

        Function<String, Task> taskFromForm = id -> {
            LocalDateTime deadline = combineDateAndTime(
                    deadlineDate, deadlineTime, "Deadline");
            double effort = positiveDouble(effortField, "Estimated hours");
            if ("Academic".equals(kindField.getValue())) {
                Course course = requiredValue(courseField.getValue(), "Course");
                return id == null
                        ? new AcademicTask(
                                required(titleField, "Task title"), deadline,
                                effort, requiredValue(difficultyField.getValue(), "Difficulty"),
                                requiredValue(importanceField.getValue(), "Importance"),
                                course, nonNegativeDouble(weightField, "Assessment weight"),
                                requiredValue(academicTypeField.getValue(), "Academic type"))
                        : new AcademicTask(
                                id, required(titleField, "Task title"), deadline,
                                effort, requiredValue(difficultyField.getValue(), "Difficulty"),
                                requiredValue(importanceField.getValue(), "Importance"),
                                course, nonNegativeDouble(weightField, "Assessment weight"),
                                requiredValue(academicTypeField.getValue(), "Academic type"));
            }
            return id == null
                    ? new GeneralTask(required(titleField, "Task title"), deadline,
                            effort, requiredValue(difficultyField.getValue(), "Difficulty"),
                            requiredValue(importanceField.getValue(), "Importance"))
                    : new GeneralTask(id, required(titleField, "Task title"), deadline,
                            effort, requiredValue(difficultyField.getValue(), "Difficulty"),
                            requiredValue(importanceField.getValue(), "Importance"));
        };

        Button addButton = new Button("Add Task");
        Button updateButton = new Button("Update");
        Button deleteButton = new Button("Delete");
        Button clearButton = new Button("New/Clear");
        updateButton.setDisable(true);
        deleteButton.setDisable(true);
        addButton.setOnAction(event -> executeAction(
                "Task added", () -> controller.addTask(taskFromForm.apply(null))));
        taskList.getSelectionModel().selectedIndexProperty().addListener(
                (observable, oldIndex, newIndex) -> {
                    int index = newIndex.intValue();
                    if (index < 0 || index >= tasks.size()) {
                        return;
                    }
                    Task selected = tasks.get(index);
                    titleField.setText(selected.getTitle());
                    deadlineDate.setValue(selected.getDeadline().toLocalDate());
                    deadlineTime.setText(selected.getDeadline().toLocalTime().toString());
                    effortField.setText(Double.toString(selected.getEstimatedHours()));
                    difficultyField.setValue(selected.getDifficulty());
                    importanceField.setValue(selected.getUserImportance());
                    if (selected instanceof AcademicTask academicTask) {
                        kindField.setValue("Academic");
                        courseField.setValue(academicTask.getCourse());
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
            Task updated = taskFromForm.apply(selected.getId());
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

    private Node createAiAssistantPage() {
        TextArea taskDescription = new TextArea();
        taskDescription.setPromptText(
                "Example: OS assignment due next Tuesday, worth 10%, about four hours");
        taskDescription.setPrefRowCount(2);

        TextField proposalTitle = new TextField();
        DatePicker proposalDate = new DatePicker();
        TextField proposalTime = new TextField("17:00");
        TextField proposalEffort = new TextField();
        ComboBox<Integer> proposalDifficulty = new ComboBox<>(
                FXCollections.observableArrayList(1, 2, 3, 4, 5));
        ComboBox<PriorityLevel> proposalImportance = new ComboBox<>(
                FXCollections.observableArrayList(PriorityLevel.values()));
        ComboBox<ParsedTaskKind> proposalKind = new ComboBox<>(
                FXCollections.observableArrayList(ParsedTaskKind.values()));
        ComboBox<Course> proposalCourse = new ComboBox<>(
                FXCollections.observableArrayList(
                        controller.getCourses()));
        proposalCourse.setConverter(courseConverter());
        TextField proposalWeight = new TextField();
        ComboBox<AcademicTaskType> proposalType = new ComboBox<>(
                FXCollections.observableArrayList(AcademicTaskType.values()));

        Runnable updateProposalAcademicFields = () -> {
            boolean academic = proposalKind.getValue()
                    == ParsedTaskKind.ACADEMIC;
            proposalCourse.setDisable(!academic);
            proposalWeight.setDisable(!academic);
            proposalType.setDisable(!academic);
        };
        proposalKind.setOnAction(
                event -> updateProposalAcademicFields.run());
        proposalKind.setValue(ParsedTaskKind.GENERAL);
        proposalDifficulty.setValue(3);
        proposalImportance.setValue(PriorityLevel.MEDIUM);
        updateProposalAcademicFields.run();

        GridPane proposalForm = createFormGrid();
        addFormRow(proposalForm, 0, "Task title", proposalTitle);
        addFormRow(proposalForm, 1, "Due date", proposalDate);
        addFormRow(proposalForm, 2, "Due time", proposalTime);
        addFormRow(proposalForm, 3, "Estimated hours", proposalEffort);
        addFormRow(proposalForm, 4, "Difficulty (1–5)", proposalDifficulty);
        addFormRow(proposalForm, 5, "Importance", proposalImportance);
        addFormRow(proposalForm, 6, "Task type", proposalKind);
        addFormRow(proposalForm, 7, "Course", proposalCourse);
        addFormRow(proposalForm, 8, "Assessment weight (%)", proposalWeight);
        addFormRow(proposalForm, 9, "Academic type", proposalType);

        Button saveProposalButton = new Button("Confirm and Add Task");
        saveProposalButton.setDisable(true);
        Button parseTaskButton = new Button("Parse Task with Gemini");
        parseTaskButton.setOnAction(event -> {
            String description = taskDescription.getText() == null
                    ? "" : taskDescription.getText().trim();
            if (description.isBlank()) {
                showError("Task description is required.");
                return;
            }
            runAiAction(
                    parseTaskButton,
                    "Parsing task with Gemini...",
                    () -> controller.parseNaturalLanguageTask(description),
                    proposal -> {
                        populateProposal(
                                proposal, proposalTitle, proposalDate,
                                proposalTime, proposalEffort,
                                proposalDifficulty, proposalImportance,
                                proposalKind, proposalCourse,
                                proposalWeight, proposalType);
                        updateProposalAcademicFields.run();
                        saveProposalButton.setDisable(false);
                        statusLabel.setText(
                                "AI proposal ready; review before saving");
                    });
        });
        saveProposalButton.setOnAction(event -> executeAction(
                "Confirmed task added", () -> controller.addTask(
                        taskFromProposalForm(
                                proposalTitle, proposalDate,
                                proposalTime, proposalEffort,
                                proposalDifficulty, proposalImportance,
                                proposalKind, proposalCourse,
                                proposalWeight, proposalType))));
        VBox taskParsingPanel = createEditor(
                "Natural-Language Task Proposal",
                new VBox(8, new Label("Task description:"),
                        taskDescription, proposalForm),
                parseTaskButton, saveProposalButton);
        taskParsingPanel.setMaxWidth(Double.MAX_VALUE);

        ComboBox<Task> effortTask = new ComboBox<>(
                FXCollections.observableArrayList(
                        controller.getTasks()));
        effortTask.setConverter(taskConverter());
        TextField suggestedEffort = new TextField();
        suggestedEffort.setPromptText("Suggested hours appear here");
        Button suggestEffortButton = new Button("Suggest Effort");
        Button applyEffortButton = new Button("Apply Reviewed Estimate");
        applyEffortButton.setDisable(true);
        suggestEffortButton.setDisable(effortTask.getItems().isEmpty());
        suggestEffortButton.setOnAction(event -> {
            try {
                Task selected = requiredValue(effortTask.getValue(), "Task");
                runAiAction(
                        suggestEffortButton,
                        "Requesting effort suggestion...",
                        () -> controller.requestEffortSuggestion(selected),
                        hours -> {
                            suggestedEffort.setText(Double.toString(hours));
                            applyEffortButton.setDisable(false);
                            statusLabel.setText(
                                    "Effort suggestion ready; edit or apply it");
                        });
            } catch (RuntimeException exception) {
                showError(errorMessage(exception));
            }
        });
        applyEffortButton.setOnAction(event -> executeAction(
                "Task estimate updated by user confirmation", () -> {
                    Task selected = requiredValue(effortTask.getValue(), "Task");
                    double hours = positiveDouble(
                            suggestedEffort, "Reviewed estimate");
                    selected.setEstimatedHours(hours);
                    controller.updateTask(selected);
                }));
        GridPane effortForm = createFormGrid();
        addFormRow(effortForm, 0, "Task", effortTask);
        addFormRow(effortForm, 1, "Reviewed estimate", suggestedEffort);
        VBox effortPanel = createEditor(
                "Adaptive Effort Suggestion", effortForm,
                suggestEffortButton, applyEffortButton);

        TextArea scheduleQuestion = new TextArea();
        scheduleQuestion.setPromptText(
                "Ask why a task was prioritized or placed at a certain time");
        scheduleQuestion.setPrefRowCount(2);
        TextArea aiOutput = new TextArea();
        aiOutput.setEditable(false);
        aiOutput.setWrapText(true);
        aiOutput.setPrefRowCount(7);
        Button explainButton = new Button("Explain Schedule");
        explainButton.setDisable(displayedSchedule == null);
        explainButton.setOnAction(event -> {
            String question = scheduleQuestion.getText() == null
                    ? "" : scheduleQuestion.getText().trim();
            if (question.isBlank()) {
                showError("Schedule question is required.");
                return;
            }
            runAiAction(
                    explainButton,
                    "Generating grounded explanation...",
                    () -> controller.explainSchedule(question),
                    explanation -> {
                        aiOutput.setText(explanation);
                        statusLabel.setText("Schedule explanation ready");
                    });
        });

        Button recoveryButton = new Button("Suggest Workload Recovery");
        recoveryButton.setDisable(lastInfeasibility == null);
        recoveryButton.setOnAction(event -> runAiAction(
                recoveryButton,
                "Generating recovery suggestions...",
                () -> controller.requestRecoverySuggestions(lastInfeasibility),
                recovery -> {
                    aiOutput.setText(formatRecoveryPlan(recovery));
                    statusLabel.setText(
                            "Recovery advice ready; no action was applied");
                }));

        Button recommendReplanButton = new Button("Recommend Replan Option");
        recommendReplanButton.setDisable(displayedSchedule == null);
        Button applyReplanButton = new Button("Apply Recommended Replan");
        applyReplanButton.setDisable(pendingReplanRecommendation == null);
        recommendReplanButton.setOnAction(event -> runAiAction(
                recommendReplanButton,
                "Evaluating replanning options...",
                controller::requestReplanRecommendation,
                recommendation -> {
                    pendingReplanRecommendation = recommendation;
                    aiOutput.setText(formatReplanRecommendation(recommendation));
                    applyReplanButton.setDisable(false);
                    statusLabel.setText(
                            "Replan recommendation ready; review before applying");
                }));
        applyReplanButton.setOnAction(event -> {
            if (pendingReplanRecommendation == null) {
                showError("Request a replanning recommendation first.");
                return;
            }
            replanSchedule(pendingReplanRecommendation.recommendedOption());
            pendingReplanRecommendation = null;
        });

        HBox scheduleActions = new HBox(
                8, explainButton, recoveryButton,
                recommendReplanButton, applyReplanButton);
        VBox scheduleAiPanel = new VBox(
                8, new Label("Schedule question:"), scheduleQuestion,
                scheduleActions, new Label("AI response:"), aiOutput);
        VBox.setVgrow(aiOutput, Priority.ALWAYS);
        VBox schedulePanel = createEditor(
                "Schedule Advice and Recovery", scheduleAiPanel);

        HBox secondaryPanels = new HBox(16, effortPanel, schedulePanel);
        HBox.setHgrow(effortPanel, Priority.ALWAYS);
        HBox.setHgrow(schedulePanel, Priority.ALWAYS);
        VBox content = new VBox(18, taskParsingPanel, secondaryPanels);
        ScrollPane scrollPane = new ScrollPane(content);
        scrollPane.setFitToWidth(true);
        return createPage(
                "AI Assistant",
                "Review every Gemini proposal before it changes planning data.",
                scrollPane);
    }

    private Node createAvailabilityPage() {
        List<AvailabilityBlock> entries = controller.getAvailability(
                LocalDateTime.MIN, LocalDateTime.MAX);
        ListView<String> availabilityList = new ListView<>(
                FXCollections.observableArrayList(entries.stream()
                        .map(block -> block.getType() + "  ·  " + block.getStart()
                                + " to " + block.getEnd())
                        .toList()));
        availabilityList.setPlaceholder(new Label("No availability entries added yet."));

        DatePicker dateField = new DatePicker(LocalDate.now());
        TextField startTimeField = new TextField("09:00");
        TextField endTimeField = new TextField("10:00");
        ComboBox<AvailabilityType> typeField = new ComboBox<>(
                FXCollections.observableArrayList(AvailabilityType.values()));
        typeField.setValue(AvailabilityType.AVAILABLE);

        GridPane form = createFormGrid();
        addFormRow(form, 0, "Date", dateField);
        addFormRow(form, 1, "Start time", startTimeField);
        addFormRow(form, 2, "End time", endTimeField);
        addFormRow(form, 3, "Entry type", typeField);

        Button addButton = new Button("Add Availability");
        addButton.setOnAction(event -> executeAction("Availability added", () -> {
            LocalDate date = requiredValue(dateField.getValue(), "Date");
            controller.addAvailability(new AvailabilityBlock(
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
            lastInfeasibility = null;
            pendingReplanRecommendation = null;
            showPage(Page.SCHEDULE);
            statusLabel.setText("Schedule generated");
        } catch (ScheduleInfeasibleException exception) {
            displayedSchedule = exception.getPartialSchedule();
            lastInfeasibility = exception;
            pendingReplanRecommendation = null;
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
            lastInfeasibility = null;
            pendingReplanRecommendation = null;
            showPage(Page.SCHEDULE);
            statusLabel.setText("Replan option applied");
        } catch (ScheduleInfeasibleException exception) {
            displayedSchedule = exception.getPartialSchedule();
            lastInfeasibility = exception;
            pendingReplanRecommendation = null;
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
                        "%.2fh", record.getCompletedHours());
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
        if (actionBar.getChildren().size() > 0
                && actionBar.getChildren().get(0) instanceof Button primary) {
            primary.getStyleClass().add("primary-action");
        }
        VBox editor = new VBox(12, heading, new Separator(), form, actionBar);
        editor.setPrefWidth(380);
        editor.getStyleClass().add("panel");
        actionBar.getStyleClass().add("action-bar");
        return editor;
    }

    private GridPane createFormGrid() {
        GridPane form = new GridPane();
        form.setHgap(10);
        form.setVgap(8);
        form.getStyleClass().add("form-grid");
        return form;
    }

    private void addFormRow(GridPane form, int row, String label, Node field) {
        Label fieldLabel = new Label(label + ":");
        fieldLabel.getStyleClass().add("field-label");
        form.add(fieldLabel, 0, row);
        form.add(field, 1, row);
        GridPane.setHgrow(field, Priority.ALWAYS);
        if (field instanceof TextField textField) {
            textField.setMaxWidth(Double.MAX_VALUE);
        }
    }

    private Node createPage(String title, String description, Node content) {
        Label heading = new Label(title);
        heading.getStyleClass().add("page-title");
        Label subtitle = new Label(description);
        subtitle.getStyleClass().add("page-subtitle");
        VBox page = new VBox(18, heading, subtitle, content);
        page.setPadding(new Insets(28, 32, 32, 32));
        page.getStyleClass().add("page-content");
        VBox.setVgrow(content, Priority.ALWAYS);
        ScrollPane scroll = new ScrollPane(page);
        scroll.setFitToWidth(true);
        scroll.getStyleClass().add("page-scroll");
        return scroll;
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

    private <T> void runAiAction(
            Button trigger,
            String progressMessage,
            Supplier<T> operation,
            Consumer<T> onSuccess) {
        trigger.setDisable(true);
        statusLabel.setText(progressMessage);
        javafx.concurrent.Task<T> backgroundTask = new javafx.concurrent.Task<>() {
            @Override
            protected T call() {
                return operation.get();
            }
        };
        backgroundTask.setOnSucceeded(event -> {
            trigger.setDisable(false);
            onSuccess.accept(backgroundTask.getValue());
        });
        backgroundTask.setOnFailed(event -> {
            trigger.setDisable(false);
            showError(errorMessage(backgroundTask.getException()));
        });
        Thread worker = new Thread(backgroundTask, "planpilot-ai-request");
        worker.setDaemon(true);
        worker.start();
    }

    private void populateProposal(
            ParsedTaskData proposal,
            TextField titleField,
            DatePicker deadlineDate,
            TextField deadlineTime,
            TextField effortField,
            ComboBox<Integer> difficultyField,
            ComboBox<PriorityLevel> importanceField,
            ComboBox<ParsedTaskKind> kindField,
            ComboBox<Course> courseField,
            TextField weightField,
            ComboBox<AcademicTaskType> typeField) {
        titleField.setText(proposal.title());
        deadlineDate.setValue(proposal.deadline().toLocalDate());
        deadlineTime.setText(proposal.deadline().toLocalTime().toString());
        effortField.setText(Double.toString(proposal.estimatedHours()));
        difficultyField.setValue(proposal.difficulty());
        importanceField.setValue(proposal.importance());
        kindField.setValue(proposal.taskKind());
        if (proposal.courseCode() == null) {
            courseField.setValue(null);
        } else {
            Course matchingCourse = controller.getCourses().stream()
                    .filter(course -> course.getId().equalsIgnoreCase(
                                    proposal.courseCode())
                            || course.getCode().equalsIgnoreCase(
                                    proposal.courseCode()))
                    .findFirst()
                    .orElse(null);
            courseField.setValue(matchingCourse);
        }
        weightField.setText(proposal.assessmentWeight() == null
                ? "" : proposal.assessmentWeight().toString());
        typeField.setValue(proposal.academicTaskType());
    }

    private Task taskFromProposalForm(
            TextField titleField,
            DatePicker deadlineDate,
            TextField deadlineTime,
            TextField effortField,
            ComboBox<Integer> difficultyField,
            ComboBox<PriorityLevel> importanceField,
            ComboBox<ParsedTaskKind> kindField,
            ComboBox<Course> courseField,
            TextField weightField,
            ComboBox<AcademicTaskType> typeField) {
        String title = required(titleField, "Task title");
        LocalDateTime deadline = combineDateAndTime(
                deadlineDate, deadlineTime, "Deadline");
        double effort = positiveDouble(effortField, "Estimated hours");
        int difficulty = requiredValue(
                difficultyField.getValue(), "Difficulty");
        PriorityLevel importance = requiredValue(
                importanceField.getValue(), "Importance");
        ParsedTaskKind kind = requiredValue(
                kindField.getValue(), "Task kind");
        if (kind == ParsedTaskKind.GENERAL) {
            return new GeneralTask(title, deadline, effort, difficulty, importance);
        }
        return new AcademicTask(
                title, deadline, effort, difficulty, importance,
                requiredValue(courseField.getValue(), "Course"),
                nonNegativeDouble(weightField, "Assessment weight"),
                requiredValue(typeField.getValue(), "Academic type"));
    }

    static String formatRecoveryPlan(RecoveryPlan recovery) {
        StringBuilder text = new StringBuilder(recovery.summary());
        recovery.suggestions().forEach(suggestion -> text
                .append(System.lineSeparator())
                .append("- ").append(suggestion.action())
                .append(": ").append(suggestion.explanation()));
        text.append(System.lineSeparator())
                .append("No action has been applied.");
        return text.toString();
    }

    static String formatReplanRecommendation(
            ReplanRecommendation recommendation) {
        return recommendation.impactSummary()
                + System.lineSeparator()
                + "Recommended: " + recommendation.recommendedOption()
                + System.lineSeparator()
                + recommendation.rationale()
                + System.lineSeparator()
                + "Review before applying.";
    }

    private String errorMessage(Throwable exception) {
        return exception.getMessage() == null
                ? exception.getClass().getSimpleName()
                : exception.getMessage();
    }

    private void showError(String message) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle("PlanPilot Error");
        alert.setHeaderText("Please check your input");
        alert.setContentText(message);
        alert.showAndWait();
        statusLabel.setText("Error: " + message);
    }

    private String formatTask(Task task) {
        String prefix = task instanceof AcademicTask academicTask
                ? academicTask.getCourse().getCode() + " | "
                : "General | ";
        return prefix + task.getTitle()
                + " | " + task.getStatus()
                + " | " + task.getRemainingHours() + "h remaining";
    }

    private String courseLabel(Course course) {
        return course == null ? "" : course.getCode() + " — " + course.getName();
    }

    private String taskLabel(Task task) {
        if (task == null) {
            return "";
        }
        String course = task instanceof AcademicTask academic
                ? academic.getCourse().getCode() + " · " : "";
        return course + task.getTitle() + " (" + task.getRemainingHours()
                + "h remaining)";
    }

    private StringConverter<Course> courseConverter() {
        return new StringConverter<>() {
            @Override
            public String toString(Course course) {
                return courseLabel(course);
            }

            @Override
            public Course fromString(String value) {
                return controller.getCourses().stream()
                        .filter(course -> courseLabel(course).equals(value))
                        .findFirst().orElse(null);
            }
        };
    }

    private StringConverter<Task> taskConverter() {
        return new StringConverter<>() {
            @Override
            public String toString(Task task) {
                return taskLabel(task);
            }

            @Override
            public Task fromString(String value) {
                return controller.getTasks().stream()
                        .filter(task -> taskLabel(task).equals(value))
                        .findFirst().orElse(null);
            }
        };
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

    private enum Page {
        COURSES("Courses"),
        TASKS("Tasks"),
        AVAILABILITY("Availability"),
        SCHEDULE("Schedule"),
        PROGRESS("Progress"),
        AI_ASSISTANT("AI Assistant");

        private final String label;

        Page(String label) {
            this.label = label;
        }
    }
}

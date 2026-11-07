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
import javafx.scene.control.ListCell;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.TitledPane;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.stage.Modality;
import javafx.util.StringConverter;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

public class PlanPilotGUI extends Application implements PlanningObserver {
    private PlanPilotController controller;
    private PlanningEventPublisher eventPublisher;
    private Stage mainStage;
    private BorderPane root;
    private Label statusLabel;
    private javafx.animation.Timeline progressTimer;
    private String selectedProgressSessionId;
    private boolean progressHistorySelected;
    private final java.util.Set<TaskStatus> expandedTaskGroups = java.util.EnumSet.of(
            TaskStatus.IN_PROGRESS, TaskStatus.NOT_STARTED);
    private final javafx.beans.property.BooleanProperty aiBusy = new javafx.beans.property.SimpleBooleanProperty(false);
    private Page currentPage = Page.OVERVIEW;
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
        mainStage = stage;
        PlanPilotApplicationContext context =
                PlanPilotApplicationFactory.createDefault();
        controller = context.controller();
        displayedSchedule = controller.getCurrentSchedule().orElse(null);
        lastInfeasibility = controller.getCurrentInfeasibility().orElse(null);
        if (displayedSchedule != null) {
            planningStart = displayedSchedule.getStartDate();
            planningEnd = displayedSchedule.getEndDate();
            scheduleMayBeOutdated = displayedSchedule.getStatus() == com.planpilot.domain.ScheduleStatus.OUTDATED;
            if (lastInfeasibility != null) scheduleWarning = partialPlanMessage(lastInfeasibility);
        }
        eventPublisher = context.eventPublisher();
        eventPublisher.subscribe(this);

        root = new BorderPane();
        root.getStyleClass().add("app-root");
        root.setLeft(createNavigation());
        statusLabel = new Label("Ready");
        statusLabel.setWrapText(true);
        statusLabel.setMaxWidth(Double.MAX_VALUE);
        statusLabel.setPadding(new Insets(12, 20, 12, 20));
        statusLabel.getStyleClass().add("status-bar");
        root.setBottom(statusLabel);
        showPage(Page.OVERVIEW);

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
        progressTimer = new javafx.animation.Timeline(new javafx.animation.KeyFrame(
                javafx.util.Duration.seconds(30), event -> refreshInferredProgress()));
        progressTimer.setCycleCount(javafx.animation.Animation.INDEFINITE);
        progressTimer.play();
        refreshInferredProgress();
    }

    @Override
    public void stop() {
        if (progressTimer != null) progressTimer.stop();
        if (eventPublisher != null) {
            eventPublisher.unsubscribe(this);
        }
    }

    @Override
    public void onPlanningStateChanged() {
        Runnable refresh = () -> {
            if (root != null) {
                if (!scheduleGenerationInProgress) {
                    pendingReplanRecommendation = null;
                    lastInfeasibility = null;
                }
                if (displayedSchedule != null && !scheduleGenerationInProgress) {
                    scheduleMayBeOutdated = displayedSchedule.getStatus() == com.planpilot.domain.ScheduleStatus.OUTDATED;
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
            case OVERVIEW -> createOverviewPage();
            case COURSES -> createCoursesPage();
            case TASKS -> createTasksPage();
            case AVAILABILITY -> createAvailabilityPage();
            case SCHEDULE -> createSchedulePage();
            case PROGRESS -> createProgressPage();
            case AI_ASSISTANT -> createAiAssistantPage();
        });
        statusLabel.setText(page.label);
    }

    private Node createOverviewPage() {
        int courseCount = controller.getCourses().size();
        int taskCount = controller.getTasks().size();
        long availabilityCount = controller.getAvailability(
                LocalDateTime.MIN, LocalDateTime.MAX).size();
        int blockCount = scheduleBlocks().size();

        HBox metrics = new HBox(14,
                metricCard(Integer.toString(courseCount), "Courses"),
                metricCard(Integer.toString(taskCount), "Tasks"),
                metricCard(Long.toString(availabilityCount), "Availability entries"),
                metricCard(Integer.toString(blockCount), "Scheduled study blocks"));
        metrics.getStyleClass().add("metric-row");
        metrics.getChildren().forEach(node -> HBox.setHgrow(node, Priority.ALWAYS));

        VBox steps = new VBox(12,
                guideStep("1", "Add your courses", "Optional for personal tasks; needed for academic tasks."),
                guideStep("2", "Add tasks", "Enter a deadline and an effort estimate. Difficulty and importance have defaults."),
                guideStep("3", "Set your availability", "Add study windows, then add classes, shifts, or other commitments."),
                guideStep("4", "Build your plan", "Choose a date range and generate a schedule. Record outcomes in Progress."));
        steps.getStyleClass().add("panel");
        VBox.setVgrow(steps, Priority.ALWAYS);

        Button courseButton = new Button("Set up courses");
        courseButton.setOnAction(event -> showPage(Page.COURSES));
        Button taskButton = new Button("Add tasks");
        taskButton.setOnAction(event -> showPage(Page.TASKS));
        Button availabilityButton = new Button("Set availability");
        availabilityButton.setOnAction(event -> showPage(Page.AVAILABILITY));
        Button scheduleButton = new Button("Generate schedule");
        scheduleButton.getStyleClass().add("primary-action");
        scheduleButton.setOnAction(event -> showPage(Page.SCHEDULE));
        HBox actions = new HBox(10,
                courseButton, taskButton, availabilityButton, scheduleButton);

        VBox content = new VBox(18,
                metrics, new Label("Start with these four steps:"), steps, actions);
        content.getStyleClass().add("overview-content");
        return createPage("Welcome to PlanPilot",
                "Plan study time around your deadlines and real availability.", content);
    }

    private VBox metricCard(String value, String caption) {
        Label valueLabel = new Label(value);
        valueLabel.getStyleClass().add("metric-value");
        Label captionLabel = new Label(caption);
        captionLabel.getStyleClass().add("metric-caption");
        VBox card = new VBox(5, valueLabel, captionLabel);
        card.getStyleClass().add("metric-card");
        return card;
    }

    private HBox guideStep(String number, String title, String detail) {
        Label numberLabel = new Label(number);
        numberLabel.getStyleClass().add("step-number");
        Label titleLabel = new Label(title);
        titleLabel.getStyleClass().add("step-title");
        Label detailLabel = new Label(detail);
        detailLabel.getStyleClass().add("step-detail");
        detailLabel.setWrapText(true);
        VBox text = new VBox(4, titleLabel, detailLabel);
        HBox step = new HBox(12, numberLabel, text);
        step.setAlignment(Pos.CENTER_LEFT);
        return step;
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
        Button clearButton = new Button("Clear form");
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
                    addButton.setDisable(true);
                    updateButton.setDisable(false);
                    deleteButton.setDisable(false);
                });
        updateButton.setOnAction(event -> executeAction("Course updated", () -> {
            Course selected = selectedItem(courseList, courses, "course");
            controller.updateCourse(new Course(
                    selected.getId(),
                    required(codeField, "Course code"),
                    required(nameField, "Course name")));
        }));
        deleteButton.setOnAction(event -> {
            Course selected = selectedItem(courseList, courses, "course");
            if (confirmChange("Delete course " + selected.getCode() + "?", "Academic tasks must be removed or reassigned first."))
                executeAction("Course deleted", () -> controller.deleteCourse(selected.getId()));
        });
        clearButton.setOnAction(event -> showPage(Page.COURSES));

        return createManagementPage(
                "Courses", "Add and review academic courses.", courseList,
                createEditor(
                        "Add or Edit Course", form,
                        addButton, updateButton, deleteButton, clearButton));
    }

    private Node createTasksPage() {
        List<Task> tasks = controller.getTasks();
        var selectedTask = new javafx.beans.property.SimpleObjectProperty<Task>();
        List<ListView<Task>> taskLists = new java.util.ArrayList<>();
        VBox taskGroups = new VBox(10);
        taskGroups.setMinWidth(340);
        if (tasks.isEmpty()) taskGroups.getChildren().add(new Label("No tasks yet. Add one using the form."));
        PlanningViewGroups.tasksByStatus(tasks).forEach((status, groupedTasks) -> {
            ListView<Task> list = createTaskList(groupedTasks);
            taskLists.add(list);
            list.getSelectionModel().selectedItemProperty().addListener((obs, oldTask, task) -> {
                if (task == null) return;
                taskLists.stream().filter(other -> other != list)
                        .forEach(other -> other.getSelectionModel().clearSelection());
                selectedTask.set(task);
            });
            TitledPane group = new TitledPane(taskStatusLabel(status) + " (" + groupedTasks.size() + ")", list);
            group.setExpanded(expandedTaskGroups.contains(status));
            group.expandedProperty().addListener((obs, oldValue, expanded) -> {
                if (expanded) expandedTaskGroups.add(status); else expandedTaskGroups.remove(status);
            });
            taskGroups.getChildren().add(group);
        });

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
        weightField.setPromptText("e.g. 10");
        Label weightSuffix = new Label("%");
        weightSuffix.getStyleClass().add("percentage-suffix");
        HBox weightInput = new HBox(6, weightField, weightSuffix);
        weightInput.setAlignment(Pos.CENTER_LEFT);
        weightInput.setMaxWidth(Double.MAX_VALUE);
        weightField.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(weightField, Priority.ALWAYS);
        ComboBox<AcademicTaskType> academicTypeField = new ComboBox<>(
                FXCollections.observableArrayList(AcademicTaskType.values()));
        academicTypeField.setValue(AcademicTaskType.ASSIGNMENT);

        GridPane form = createFormGrid();
        addFormRow(form, 0, "Task kind", kindField);
        addFormRow(form, 1, "Task title", titleField);
        addFormRow(form, 2, "Due date", deadlineDate);
        addFormRow(form, 3, "Due time", deadlineTime);
        addFormRow(form, 4, "Estimated hours", effortField);
        addFormRow(form, 5, "Difficulty (1–5)", difficultyField);
        addFormRow(form, 6, "Importance", importanceField);
        GridPane detailsForm = createFormGrid();
        addFormRow(detailsForm, 0, "Course", courseField);
        addFormRow(detailsForm, 1, "Assessment weight", weightInput);
        addFormRow(detailsForm, 2, "Academic type", academicTypeField);
        TitledPane academicDetails = new TitledPane(
                "Course details", detailsForm);
        academicDetails.setExpanded(false);
        Runnable updateAcademicFields = () -> {
            boolean academic = "Academic".equals(kindField.getValue());
            courseField.setDisable(!academic);
            weightField.setDisable(!academic);
            academicTypeField.setDisable(!academic);
            detailsForm.getChildren().forEach(node -> {
                node.setVisible(academic);
                node.setManaged(academic);
            });
            academicDetails.setExpanded(academic);
            academicDetails.setVisible(academic);
            academicDetails.setManaged(academic);
        };
        kindField.setOnAction(event -> updateAcademicFields.run());
        updateAcademicFields.run();

        TextArea taskDescription = new TextArea();
        taskDescription.setPromptText(
                "Example: EECS3311 design report due Nov 20, about 4 hours");
        taskDescription.setPrefRowCount(2);
        Button fillWithAiButton = new Button("Fill form with AI");
        fillWithAiButton.setDisable(!controller.isAiAvailable());
        fillWithAiButton.setOnAction(event -> {
            String description = taskDescription.getText() == null
                    ? "" : taskDescription.getText().trim();
            if (description.isBlank()) {
                showError("Describe the task first, for example: EECS3311 design report due Nov 20, about 4 hours.");
                return;
            }
            runAiAction(
                    fillWithAiButton,
                    "Drafting task details with Gemini...",
                    () -> controller.parseNaturalLanguageTask(description),
                    proposal -> {
                        populateTaskForm(
                                proposal, titleField, deadlineDate, deadlineTime,
                                effortField, difficultyField, importanceField,
                                kindField, courseField, weightField,
                                academicTypeField, updateAcademicFields);
                        statusLabel.setText(
                                "Draft filled. Review it, then select Add Task.");
                    });
        });
        Label aiTaskHint = new Label(controller.isAiAvailable()
                ? "Optional: describe it here, review the filled fields, then add the task."
                : "AI task entry is unavailable. Configure GEMINI_API_KEY in .env and restart.");
        aiTaskHint.setWrapText(true);
        VBox aiTaskEntry = new VBox(8,
                new Label("Create from a description (optional)"),
                aiTaskHint, taskDescription, fillWithAiButton);
        aiTaskEntry.getStyleClass().add("inline-ai-entry");
        VBox taskFields = new VBox(12, aiTaskEntry, form, academicDetails);

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
        Button clearButton = new Button("Clear form");
        updateButton.setDisable(true);
        deleteButton.setDisable(true);
        addButton.setOnAction(event -> executeAction(
                "Task added", () -> controller.addTask(taskFromForm.apply(null))));
        selectedTask.addListener((observable, oldTask, selected) -> {
            if (selected == null) return;
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
            addButton.setDisable(true);
            updateButton.setDisable(false);
            deleteButton.setDisable(false);
        });
        updateButton.setOnAction(event -> executeAction("Task updated", () -> {
            Task selected = requiredValue(selectedTask.get(), "Task");
            Task updated = taskFromForm.apply(selected.getId());
            updated.setDeferredUntil(selected.getDeferredUntil());
            updated.setCompletedHours(selected.getCompletedHours());
            if (selected.getStatus() == TaskStatus.SKIPPED) {
                updated.markSkipped();
            }
            controller.updateTask(updated);
        }));
        deleteButton.setOnAction(event -> {
            Task selected = requiredValue(selectedTask.get(), "Task");
            if (confirmChange("Delete task " + selected.getTitle() + "?", "The task leaves the active list. Recorded session history is kept."))
                executeAction("Task deleted", () -> controller.deleteTask(selected.getId()));
        });
        clearButton.setOnAction(event -> showPage(Page.TASKS));
        Button resumeButton = new Button("Include deferred task again");
        resumeButton.setDisable(true);
        selectedTask.addListener((obs, oldTask, task) ->
                resumeButton.setDisable(task == null || task.getDeferredUntil() == null));
        resumeButton.setOnAction(event -> executeAction("Task included again; replan when ready", () ->
                controller.resumeTask(requiredValue(selectedTask.get(), "Task").getId())));

        return createManagementPage(
                "Tasks", "Add a task manually or describe it to have AI fill the form.",
                taskGroups, createEditor(
                        "Add or Edit Task", taskFields,
                        addButton, updateButton, deleteButton, clearButton, resumeButton));
    }

    private ListView<Task> createTaskList(List<Task> tasks) {
        ListView<Task> taskList = new ListView<>(FXCollections.observableArrayList(tasks));
        taskList.setPlaceholder(new Label("No tasks in this status."));
        taskList.setMinHeight(100);
        taskList.setPrefHeight(Math.min(440, Math.max(110, tasks.size() * 155)));
        taskList.setCellFactory(view -> new ListCell<>() {
            @Override protected void updateItem(Task task, boolean empty) {
                super.updateItem(task, empty); setText(null);
                if (empty || task == null) { setGraphic(null); return; }
                Label title = new Label(task instanceof AcademicTask academic
                        ? academic.getCourse().getCode() + ": " + task.getTitle() : task.getTitle());
                title.getStyleClass().add("session-title"); title.setWrapText(true);
                Label deadline = new Label("Due " + task.getDeadline().format(DateTimeFormatter.ofPattern("MMM d, yyyy · h:mm a")));
                deadline.setWrapText(true);
                Label effort = new Label(String.format("%.1f hours remaining of %.1f estimated",
                        task.getRemainingHours(), task.getEstimatedHours()));
                effort.setWrapText(true);
                Label status = new Label(taskStatusLabel(task.getStatus())
                        + (controller.getInferredHours(task) > 0 ? " — includes tentative progress" : "")
                        + (task.getDeferredUntil() == null ? "" : " — Deferred until " + task.getDeferredUntil().toLocalDate()));
                VBox card = new VBox(6, title, deadline, effort, status);
                card.getStyleClass().add("session-card");
                card.setStyle("-fx-border-color: " + taskColour(task) + ";");
                card.prefWidthProperty().bind(view.widthProperty().subtract(40));
                setGraphic(card);
            }
        });
        return taskList;
    }

    private static String taskStatusLabel(TaskStatus status) {
        return switch (status) {
            case IN_PROGRESS -> "In progress";
            case NOT_STARTED -> "Not started";
            case COMPLETED -> "Completed";
            case SKIPPED -> "Skipped";
        };
    }

    private static String taskTitle(Task task) {
        return task instanceof AcademicTask academic
                ? academic.getCourse().getCode() + ": " + task.getTitle() : task.getTitle();
    }

    private Node createAiAssistantPage() {
        boolean aiAvailable = controller.isAiAvailable();
        ComboBox<Task> effortTask = new ComboBox<>(
                FXCollections.observableArrayList(
                        controller.getTasks()));
        effortTask.setConverter(taskConverter());
        TextField suggestedEffort = new TextField();
        suggestedEffort.setPromptText("Suggested hours appear here");
        Button suggestEffortButton = new Button("Suggest Effort");
        Button applyEffortButton = new Button("Apply Reviewed Estimate");
        applyEffortButton.setDisable(true);
        suggestEffortButton.disableProperty().bind(
                effortTask.valueProperty().isNull().or(aiBusy).or(new javafx.beans.property.SimpleBooleanProperty(!aiAvailable)));
        suggestEffortButton.setOnAction(event -> {
            try {
                Task selected = requiredValue(effortTask.getValue(), "Task");
                runAiAction(
                        suggestEffortButton,
                        "Requesting effort suggestion...",
                        () -> controller.requestEffortSuggestion(selected),
                        hours -> {
                            if (effortTask.getValue() != selected) return;
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
        effortTask.valueProperty().addListener((obs, oldTask, newTask) -> {
            applyEffortButton.setDisable(true);
            suggestedEffort.clear();
        });
        GridPane effortForm = createFormGrid();
        addFormRow(effortForm, 0, "Task", effortTask);
        addFormRow(effortForm, 1, "Reviewed estimate", suggestedEffort);
        VBox effortPanel = createEditor(
                "Adaptive Effort Suggestion", new VBox(8, new Label("Select a task to request an effort suggestion."), effortForm),
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
        explainButton.disableProperty().bind(scheduleQuestion.textProperty().isEmpty()
                .or(javafx.beans.binding.Bindings.createBooleanBinding(
                        () -> scheduleQuestion.getText().isBlank(), scheduleQuestion.textProperty()))
                .or(aiBusy).or(new javafx.beans.property.SimpleBooleanProperty(!aiAvailable || displayedSchedule == null || lastInfeasibility != null)));
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
        recoveryButton.disableProperty().bind(aiBusy.or(new javafx.beans.property.SimpleBooleanProperty(!aiAvailable || lastInfeasibility == null)));
        recoveryButton.setOnAction(event -> {
            ScheduleInfeasibleException requestedFor = lastInfeasibility;
            runAiAction(recoveryButton, "Generating recovery suggestions...",
                    () -> controller.requestRecoverySuggestions(requestedFor), recovery -> {
                        if (lastInfeasibility != requestedFor) {
                            aiOutput.setText("Planning data changed. Generate or replan again before requesting fresh recovery advice.");
                            return;
                        }
                        aiOutput.setText(formatRecoveryPlan(recovery));
                        statusLabel.setText("Recovery advice ready; choose whether to apply a change.");
                        showRecoveryDialog(recovery);
                    });
        });

        Button recommendReplanButton = new Button("Recommend Replan Option");
        recommendReplanButton.disableProperty().bind(aiBusy.or(new javafx.beans.property.SimpleBooleanProperty(!aiAvailable || displayedSchedule == null)));
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

        javafx.scene.layout.FlowPane scheduleActions = new javafx.scene.layout.FlowPane(
                8, 8, explainButton, recoveryButton,
                recommendReplanButton, applyReplanButton);
        VBox scheduleAiPanel = new VBox(
                8, new Label(displayedSchedule == null ? "Generate a schedule first to ask questions or request replanning." : "Ask about your current schedule."),
                new Label(lastInfeasibility == null ? "Recovery suggestions appear after an infeasible schedule is detected." : "An infeasible schedule was detected; recovery advice is available."),
                new Label("Schedule question:"), scheduleQuestion,
                scheduleActions, new Label("AI response:"), aiOutput);
        VBox.setVgrow(aiOutput, Priority.ALWAYS);
        VBox schedulePanel = createEditor(
                "Schedule Advice and Recovery", scheduleAiPanel);

        HBox secondaryPanels = new HBox(16, effortPanel, schedulePanel);
        HBox.setHgrow(effortPanel, Priority.ALWAYS);
        HBox.setHgrow(schedulePanel, Priority.ALWAYS);
        TitledPane additionalAiTools = new TitledPane(
                "Other tools: effort estimate, schedule advice, and replanning",
                secondaryPanels);
        additionalAiTools.setExpanded(false);
        Label configurationNote = new Label(aiAvailable
                ? "AI is ready. Suggestions use your current planning data; review before applying."
                : "Gemini is not configured. Add GEMINI_API_KEY to your local .env file and restart PlanPilot.");
        configurationNote.setWrapText(true);
        configurationNote.getStyleClass().add("page-instruction");
        VBox content = new VBox(18, configurationNote, additionalAiTools);
        return createPage(
                "AI Tools",
                "Optional help with effort estimates and schedule decisions.",
                content);
    }

    private Node createAvailabilityPage() {
        List<AvailabilityBlock> entries = controller.getAvailability(
                LocalDateTime.MIN, LocalDateTime.MAX);
        ListView<String> availabilityList = new ListView<>(
                FXCollections.observableArrayList(entries.stream()
                        .map(this::formatAvailability)
                        .toList()));
        availabilityList.setPlaceholder(new Label("No availability entries added yet."));

        DatePicker dateField = new DatePicker(LocalDate.now());
        TextField startTimeField = new TextField("09:00");
        TextField endTimeField = new TextField("10:00");
        ComboBox<AvailabilityType> typeField = new ComboBox<>(
                FXCollections.observableArrayList(AvailabilityType.values()));
        typeField.setConverter(new StringConverter<>() {
            @Override
            public String toString(AvailabilityType type) {
                return availabilityTypeLabel(type);
            }

            @Override
            public AvailabilityType fromString(String value) {
                return java.util.Arrays.stream(AvailabilityType.values())
                        .filter(type -> availabilityTypeLabel(type).equals(value))
                        .findFirst().orElse(null);
            }
        });
        typeField.setValue(AvailabilityType.AVAILABLE);

        GridPane form = createFormGrid();
        addFormRow(form, 0, "Date", dateField);
        startTimeField.setPromptText("13:00 (24-hour time)");
        endTimeField.setPromptText("14:00 (24-hour time)");
        addFormRow(form, 1, "Start time (24h)", startTimeField);
        addFormRow(form, 2, "End time (24h)", endTimeField);
        addFormRow(form, 3, "Entry type", typeField);

        AvailabilityBlock[] editing = {null};
        Button saveButton = new Button("Add time");
        Button deleteButton = new Button("Delete");
        deleteButton.setDisable(true);
        availabilityList.getSelectionModel().selectedIndexProperty().addListener(
                (observable, oldIndex, newIndex) -> {
                    int index = newIndex.intValue();
                    if (index < 0 || index >= entries.size()) {
                        return;
                    }
                    AvailabilityBlock selected = entries.get(index);
                    editing[0] = selected;
                    saveButton.setText("Save changes");
                    dateField.setValue(selected.getStart().toLocalDate());
                    startTimeField.setText(selected.getStart().toLocalTime().toString());
                    endTimeField.setText(selected.getEnd().toLocalTime().toString());
                    typeField.setValue(selected.getType());
                    deleteButton.setDisable(false);
                });
        saveButton.setOnAction(event -> executeAction(
                editing[0] == null ? "Availability added" : "Availability updated",
                () -> {
                    LocalDate date = requiredValue(dateField.getValue(), "Date");
                    LocalDateTime start = LocalDateTime.of(
                            date, parseTime(startTimeField, "Start time"));
                    LocalDateTime end = LocalDateTime.of(
                            date, parseTime(endTimeField, "End time"));
                    AvailabilityType type = requiredValue(
                            typeField.getValue(), "Availability type");
                    if (editing[0] != null) {
                        controller.updateAvailability(new AvailabilityBlock(
                                editing[0].getId(), start, end, type));
                    } else {
                        AvailabilityBlock candidate = new AvailabilityBlock(
                                start, end, type);
                        if (type == AvailabilityType.AVAILABLE
                                && entries.stream().anyMatch(existing ->
                                        existing.getType() == AvailabilityType.AVAILABLE
                                                && candidate.overlaps(existing))) {
                            throw new IllegalArgumentException(
                                    "This overlaps an available window. Select that window in the list to change its times.");
                        }
                        controller.addAvailability(candidate);
                    }
                }));
        deleteButton.setOnAction(event -> executeAction(
                "Availability deleted", () -> controller.deleteAvailability(
                        selectedItem(
                                availabilityList, entries,
                                "availability entry").getId())));
        Button clearButton = new Button("Add another time");
        clearButton.setOnAction(event -> {
            editing[0] = null;
            availabilityList.getSelectionModel().clearSelection();
            dateField.setValue(LocalDate.now());
            startTimeField.setText("09:00");
            endTimeField.setText("10:00");
            typeField.setValue(AvailabilityType.AVAILABLE);
            saveButton.setText("Add time");
            deleteButton.setDisable(true);
        });

        return createManagementPage(
                "Availability", "Add free study time or fixed commitments.",
                availabilityList,
                createEditor(
                        "Availability time", form,
                        saveButton, deleteButton, clearButton));
    }

    private Node createSchedulePage() {
        DatePicker startField = new DatePicker(planningStart);
        DatePicker endField = new DatePicker(planningEnd);
        startField.valueProperty().addListener((obs, oldDate, newDate) -> { if (newDate != null) planningStart = newDate; });
        endField.valueProperty().addListener((obs, oldDate, newDate) -> { if (newDate != null) planningEnd = newDate; });
        Button generateButton = new Button("Generate Schedule");
        generateButton.setMinWidth(javafx.scene.layout.Region.USE_PREF_SIZE);
        generateButton.setOnAction(event -> generateSchedule(startField, endField));

        javafx.scene.layout.FlowPane controls = new javafx.scene.layout.FlowPane(
                10, 10, new Label("Start:"), startField,
                new Label("End:"), endField, generateButton);
        controls.setAlignment(Pos.CENTER_LEFT);

        Button replanButton = new Button("Replan remaining work");
        replanButton.setMinWidth(javafx.scene.layout.Region.USE_PREF_SIZE);
        replanButton.setDisable(displayedSchedule == null);
        replanButton.setOnAction(event -> {
            if (displayedSchedule != null
                    && (!java.util.Objects.equals(startField.getValue(), displayedSchedule.getStartDate())
                    || !java.util.Objects.equals(endField.getValue(), displayedSchedule.getEndDate()))) {
                generateSchedule(startField, endField);
            } else {
                replanSchedule(ReplanOption.RESCHEDULE_INCOMPLETE_WORK);
            }
        });

        Button resolveButton = new Button("Resolve unscheduled work");
        resolveButton.setDisable(lastInfeasibility == null);
        resolveButton.setOnAction(event -> showRecoveryDialog(null));

        ListView<StudyBlock> blockList = createSessionList(upcomingSessions(displayedSchedule),
                displayedSchedule == null ? "Choose dates and generate a schedule."
                        : "No upcoming sessions. Review progress or add remaining work.");
        ListView<StudyBlock> historicalList = createSessionList(historicalSessions(displayedSchedule),
                "No reported sessions in this window.");
        historicalList.setPrefHeight(220);
        TitledPane history = new TitledPane("Retained session history (" + historicalList.getItems().size()
                + ") — confirm or correct in Progress", historicalList);
        history.setExpanded(false);

        Label inputs = new Label();
        inputs.setWrapText(true);
        Runnable refreshInputs = () -> {
            LocalDate start = startField.getValue();
            LocalDate end = endField.getValue();
            if (start == null || end == null || end.isBefore(start)) {
                inputs.setText("Choose a valid planning date range.");
                return;
            }
            long availableCount = controller.getAvailability(start.atStartOfDay(), end.plusDays(1).atStartOfDay())
                    .stream().filter(b -> b.getType() == AvailabilityType.AVAILABLE
                            && b.getEnd().isAfter(LocalDateTime.now())).count();
            long deferredCount = controller.getOpenTasks().stream().filter(t -> t.getDeferredUntil() != null
                    && !t.getDeferredUntil().isBefore(end.plusDays(1).atStartOfDay())).count();
            inputs.setText(controller.getOpenTasks().size() + " unfinished task(s), " + deferredCount + " deferred from this window; " + availableCount
                    + " study window(s) with future time in these dates. Elapsed time is excluded; work must fit before each deadline.");
        };
        startField.valueProperty().addListener((obs, oldDate, newDate) -> refreshInputs.run());
        endField.valueProperty().addListener((obs, oldDate, newDate) -> refreshInputs.run());
        refreshInputs.run();
        Label summary = new Label(scheduleSummary());
        summary.setWrapText(true);
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
        TitledPane preferenceDetails = new TitledPane(
                "Optional schedule preferences", preferenceEditor);
        preferenceDetails.setExpanded(false);

        VBox scheduleContent = new VBox(14, new Label("Upcoming sessions"), blockList, history, preferenceDetails);
        VBox content = new VBox(
                12, controls, inputs, replanButton, summary, warning, resolveButton, scheduleContent);
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

            if (controller.getOpenTasks().isEmpty()) {
                throw new IllegalStateException("No unfinished tasks are available to schedule. Add a task with remaining effort first.");
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
            scheduleWarning = partialPlanMessage(exception);
            scheduleMayBeOutdated = false;
            showPage(Page.SCHEDULE);
            statusLabel.setText("Partial plan saved — more available study time is needed.");
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
            statusLabel.setText("Remaining work replanned. Unchanged inputs may produce the same session times; reported sessions are kept in history.");
        } catch (ScheduleInfeasibleException exception) {
            displayedSchedule = exception.getPartialSchedule();
            lastInfeasibility = exception;
            pendingReplanRecommendation = null;
            planningStart = displayedSchedule.getStartDate();
            planningEnd = displayedSchedule.getEndDate();
            scheduleWarning = partialPlanMessage(exception);
            scheduleMayBeOutdated = false;
            showPage(Page.SCHEDULE);
            statusLabel.setText("Partial replan saved. Your recorded progress is preserved.");
        } catch (RuntimeException exception) {
            showError(errorMessage(exception));
        } finally {
            scheduleGenerationInProgress = false;
        }
    }

    private boolean confirmChange(String title, String explanation) {
        javafx.scene.control.Alert dialog = new javafx.scene.control.Alert(javafx.scene.control.Alert.AlertType.CONFIRMATION);
        dialog.initOwner(mainStage); dialog.setTitle("Confirm change");
        dialog.setHeaderText(title); dialog.setContentText(explanation);
        return dialog.showAndWait().orElse(javafx.scene.control.ButtonType.CANCEL) == javafx.scene.control.ButtonType.OK;
    }

    private void showRecoveryDialog(RecoveryPlan advice) {
        ScheduleInfeasibleException failure = lastInfeasibility;
        if (failure == null) { showError("Generate or replan an infeasible schedule first."); return; }
        Schedule partial = failure.getPartialSchedule();
        var dialog = new javafx.scene.control.Dialog<com.planpilot.service.RecoveryRequest>();
        dialog.initOwner(mainStage); dialog.setTitle("Resolve unscheduled work");
        dialog.setHeaderText(String.format("%.2f hours need a recovery choice", failure.getWorkloadDeficitHours()));
        var apply = new javafx.scene.control.ButtonType("Apply change and replan", javafx.scene.control.ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(apply, javafx.scene.control.ButtonType.CANCEL);
        ComboBox<com.planpilot.ai.RecoveryAction> action = new ComboBox<>(
                FXCollections.observableArrayList(com.planpilot.ai.RecoveryAction.values()));
        action.setConverter(new StringConverter<>() {
            @Override public String toString(com.planpilot.ai.RecoveryAction value) {
                if (value == null) return "";
                return switch (value) {
                    case ADD_AVAILABILITY -> "Add available time";
                    case EXTEND_PLANNING_WINDOW -> "Extend the planning window";
                    case DEFER_LOWER_PRIORITY_TASK -> "Leave a selected task out of this plan";
                    case KEEP_CURRENT_CONSTRAINTS -> "Keep the current partial plan";
                };
            }
            @Override public com.planpilot.ai.RecoveryAction fromString(String text) { return null; }
        });
        action.setValue(advice == null ? com.planpilot.ai.RecoveryAction.ADD_AVAILABILITY : advice.suggestions().get(0).action());
        LocalDate suggestedDate = LocalDate.now().isBefore(partial.getStartDate()) ? partial.getStartDate() : LocalDate.now();
        if (suggestedDate.isAfter(partial.getEndDate())) suggestedDate = partial.getEndDate();
        DatePicker date = new DatePicker(suggestedDate);
        TextField start = new TextField(); TextField end = new TextField();
        start.setPromptText("e.g. 09:00"); end.setPromptText("e.g. 11:00");
        GridPane availability = createFormGrid();
        addFormRow(availability, 0, "Date", date); addFormRow(availability, 1, "Start (24h)", start);
        addFormRow(availability, 2, "End (24h)", end);
        DatePicker newEnd = new DatePicker(partial.getEndDate().plusDays(1));
        GridPane extension = createFormGrid(); addFormRow(extension, 0, "New planning end", newEnd);
        ComboBox<Task> task = new ComboBox<>(FXCollections.observableArrayList(controller.getOpenTasks().stream()
                .filter(t -> t.getDeferredUntil() == null || t.getDeferredUntil().isBefore(partial.getEndDate().plusDays(1).atStartOfDay()))
                .sorted(java.util.Comparator.comparing(Task::getUserImportance)).toList()));
        task.setConverter(taskConverter());
        task.setPromptText("Choose a lower-priority task");
        GridPane deferral = createFormGrid(); addFormRow(deferral, 0, "Task to defer", task);
        Label impact = new Label(); impact.setWrapText(true);
        Label error = new Label(); error.setWrapText(true); error.setStyle("-fx-text-fill: #a52828;");
        Runnable refresh = () -> {
            boolean adding = action.getValue() == com.planpilot.ai.RecoveryAction.ADD_AVAILABILITY;
            boolean extending = action.getValue() == com.planpilot.ai.RecoveryAction.EXTEND_PLANNING_WINDOW;
            boolean deferring = action.getValue() == com.planpilot.ai.RecoveryAction.DEFER_LOWER_PRIORITY_TASK;
            availability.setVisible(adding); availability.setManaged(adding);
            extension.setVisible(extending); extension.setManaged(extending);
            deferral.setVisible(deferring); deferral.setManaged(deferring);
            impact.setText(adding ? "Adds available time and replans. No deadline changes."
                    : extending ? "Changes the planning window only. Add availability on new dates if needed; task deadlines stay unchanged."
                    : deferring ? (task.getValue() == null ? "The selected task stays unfinished and keeps its deadline. "
                            : task.getValue().getTitle() + " stays unfinished. Its deadline remains " + task.getValue().getDeadline() + ". ") + "It is excluded until "
                            + partial.getEndDate().plusDays(1) + ". If its deadline is earlier, it can become overdue. Resume it in Tasks."
                    : "Keep all current constraints and the partial plan. No regeneration.");
            ((Button) dialog.getDialogPane().lookupButton(apply)).setText(
                    action.getValue() == com.planpilot.ai.RecoveryAction.KEEP_CURRENT_CONSTRAINTS ? "Keep current plan" : "Apply change and replan");
            error.setText("");
        };
        action.setOnAction(event -> refresh.run());
        task.valueProperty().addListener((obs, oldTask, newTask) -> refresh.run());
        refresh.run();
        Label adviceText = new Label(advice == null ? "Choose a change to review. Nothing is applied until you confirm."
                : formatRecoveryPlan(advice));
        adviceText.setWrapText(true);
        VBox content = new VBox(12, adviceText, new Label("Recovery action"), action, availability, extension, deferral, impact, error);
        content.setPrefWidth(510);
        ScrollPane recoveryScroll = new ScrollPane(content);
        recoveryScroll.setFitToWidth(true); recoveryScroll.setPrefViewportHeight(420);
        dialog.setResizable(true); dialog.getDialogPane().setContent(recoveryScroll);
        com.planpilot.service.RecoveryRequest[] reviewed = {null};
        dialog.getDialogPane().lookupButton(apply).addEventFilter(javafx.event.ActionEvent.ACTION, event -> {
            try {
                AvailabilityBlock block = action.getValue() == com.planpilot.ai.RecoveryAction.ADD_AVAILABILITY
                        ? new AvailabilityBlock(combineDateAndTime(date, start, "Start"), combineDateAndTime(date, end, "End"), AvailabilityType.AVAILABLE) : null;
                String taskId = action.getValue() == com.planpilot.ai.RecoveryAction.DEFER_LOWER_PRIORITY_TASK
                        ? requiredValue(task.getValue(), "Task to defer").getId() : null;
                var request = new com.planpilot.service.RecoveryRequest(partial.getId(), action.getValue(), block,
                        action.getValue() == com.planpilot.ai.RecoveryAction.EXTEND_PLANNING_WINDOW
                                ? requiredValue(newEnd.getValue(), "New planning end") : null, taskId);
                if (request.newEndDate() != null && !request.newEndDate().isAfter(partial.getEndDate()))
                    throw new IllegalArgumentException("Choose an end date later than the current planning window.");
                reviewed[0] = request;
            } catch (RuntimeException invalid) { error.setText(errorMessage(invalid)); event.consume(); }
        });
        dialog.setResultConverter(button -> button == apply ? reviewed[0] : null);
        dialog.showAndWait().ifPresent(this::applyRecovery);
    }

    private void applyRecovery(com.planpilot.service.RecoveryRequest request) {
        scheduleGenerationInProgress = true;
        try {
            displayedSchedule = controller.applyRecovery(request);
            planningStart = displayedSchedule.getStartDate(); planningEnd = displayedSchedule.getEndDate();
            if (request.action() != com.planpilot.ai.RecoveryAction.KEEP_CURRENT_CONSTRAINTS) {
                lastInfeasibility = null; scheduleWarning = ""; scheduleMayBeOutdated = false;
            }
            showPage(Page.SCHEDULE); statusLabel.setText("Recovery choice applied by your request.");
        } catch (ScheduleInfeasibleException failure) {
            displayedSchedule = failure.getPartialSchedule(); lastInfeasibility = failure;
            planningStart = displayedSchedule.getStartDate(); planningEnd = displayedSchedule.getEndDate();
            scheduleWarning = partialPlanMessage(failure); scheduleMayBeOutdated = false;
            showPage(Page.SCHEDULE); statusLabel.setText("Change applied; some work still needs recovery.");
        } catch (RuntimeException failure) { showError(errorMessage(failure)); }
        finally { scheduleGenerationInProgress = false; }
    }

    private void refreshInferredProgress() {
        try { controller.refreshProgress(); }
        catch (RuntimeException failure) { showError(errorMessage(failure)); }
    }

    private Node createProgressPage() {
        List<Task> tasks = controller.getTasks();
        List<StudyBlock> blocks = controller.getProgressSessions();
        double planned = blocks.stream().mapToDouble(StudyBlock::durationHours).sum();
        double confirmed = tasks.stream().mapToDouble(controller::getConfirmedHours).sum();
        double inferred = tasks.stream().mapToDouble(controller::getInferredHours).sum();
        double remaining = tasks.stream().mapToDouble(Task::getRemainingHours).sum();
        long completedTasks = tasks.stream().filter(t -> controller.getConfirmedHours(t) >= t.getEstimatedHours()).count();
        long awaiting = blocks.stream().filter(b -> !b.getEnd().isAfter(LocalDateTime.now())
                && (b.isInferred() || b.getProgressStatus() == ProgressStatus.PLANNED)).count();
        HBox totals = new HBox(12, metricCard(String.format("%.1f h", planned), "Planned study hours"),
                metricCard(String.format("%.1f h", confirmed), "Confirmed completed"),
                metricCard(String.format("%.1f h", remaining), "Remaining effort"));
        HBox outcomes = new HBox(12, metricCard(completedTasks + " / " + tasks.size(), "Confirmed complete tasks"),
                metricCard(Long.toString(tasks.size() - completedTasks), "Incomplete / unconfirmed tasks"),
                metricCard(Long.toString(awaiting), "Sessions awaiting confirmation"));
        Label inferenceNote = new Label(String.format(
                "%.1f h tentatively inferred from elapsed sessions. Remaining effort includes these assumptions; confirm or correct each session below. No schedule is regenerated automatically.", inferred));
        inferenceNote.setWrapText(true);
        inferenceNote.getStyleClass().add("page-instruction");

        VBox taskCards = new VBox(10);
        if (tasks.isEmpty()) taskCards.getChildren().add(new Label("Add tasks to see progress."));
        for (Task task : tasks) {
            double confirmedHours = controller.getConfirmedHours(task);
            double inferredHours = controller.getInferredHours(task);
            String title = task instanceof AcademicTask academic
                    ? academic.getCourse().getCode() + ": " + task.getTitle() : task.getTitle();
            Label taskTitle = new Label(title);
            taskTitle.getStyleClass().add("step-title");
            javafx.scene.control.ProgressBar bar = new javafx.scene.control.ProgressBar(
                    Math.min(1, confirmedHours / task.getEstimatedHours()));
            bar.setMaxWidth(Double.MAX_VALUE);
            Label detail = new Label(String.format(
                    "Original estimate %.1f h · Current estimate %.1f h · Confirmed %.1f h · Inferred %.1f h · Remaining %.1f h",
                    controller.getOriginalEstimate(task), task.getEstimatedHours(), confirmedHours,
                    inferredHours, task.getRemainingHours()));
            detail.setWrapText(true);
            Label status = new Label("Status: " + task.getStatus()
                    + (inferredHours > 0 ? " (includes tentative progress)" : "")
                    + (task.getDeferredUntil() == null ? "" : " · Deferred until " + task.getDeferredUntil().toLocalDate())
                    + " · Deadline: " + task.getDeadline().format(DateTimeFormatter.ofPattern("MMM d, yyyy h:mm a")));
            status.setWrapText(true);
            VBox card = new VBox(7, taskTitle, bar, detail, status);
            card.getStyleClass().add("panel"); taskCards.getChildren().add(card);
        }

        List<StudyBlock> pending = PlanningViewGroups.sessionsForView(blocks, false);
        List<StudyBlock> historical = PlanningViewGroups.sessionsForView(blocks, true);
        var selectedSession = new javafx.beans.property.SimpleObjectProperty<StudyBlock>();
        List<ListView<StudyBlock>> sessionLists = new java.util.ArrayList<>();
        ListView<String> historyList = new ListView<>();
        historyList.setPrefHeight(160);
        historyList.setPlaceholder(new Label("No recorded corrections for this session."));
        Label selectionHelp = new Label("Choose a session within a task group.");
        selectionHelp.setWrapText(true);
        TextField partialHoursField = new TextField();
        partialHoursField.setPromptText("Actual hours, from 0 to session duration");
        Button completedButton = new Button("Confirm completed");
        Button partialButton = new Button("Save actual hours");
        Button skippedButton = new Button("Mark skipped");
        completedButton.setOnAction(event -> {
            StudyBlock block = selectedSession.get();
            if (block != null) recordProgress(block, ProgressStatus.COMPLETED, block.durationHours(), "Session moved to History");
        });
        partialButton.setOnAction(event -> {
            StudyBlock block = selectedSession.get();
            if (block != null) {
                try { recordProgress(block, ProgressStatus.PARTIAL, nonNegativeDouble(partialHoursField, "Actual completed hours"), "Actual hours saved in History; remaining effort is still tracked"); }
                catch (RuntimeException failure) { showError(errorMessage(failure)); }
            }
        });
        skippedButton.setOnAction(event -> {
            StudyBlock block = selectedSession.get();
            if (block != null) recordProgress(block, ProgressStatus.SKIPPED, 0, "Session moved to History; remaining effort is still tracked");
        });
        for (Button button : List.of(completedButton, partialButton, skippedButton)) {
            button.disableProperty().bind(selectedSession.isNull());
        }
        selectedSession.addListener((obs, oldBlock, block) -> {
            partialHoursField.clear();
            if (block == null) {
                selectionHelp.setText("Choose a session within a task group.");
                historyList.getItems().clear();
                return;
            }
            selectedProgressSessionId = block.getId();
            selectionHelp.setText(String.format("%s · %.2f h session. %s", taskTitle(block.getTask()), block.durationHours(),
                    block.isInferred() ? "Tentative — confirm or correct this session." : "Saving replaces this session's previous result."));
            historyList.setItems(FXCollections.observableArrayList(controller.getProgressHistory(block.getTask())
                    .stream().filter(record -> record.getStudyBlock().getId().equals(block.getId()))
                    .map(this::formatProgressRecord).toList()));
        });
        javafx.scene.control.Tab pendingTab = new javafx.scene.control.Tab("To confirm (" + pending.size() + ")",
                createGroupedProgressSessions(pending, selectedSession, sessionLists, false));
        javafx.scene.control.Tab historyTab = new javafx.scene.control.Tab("History (" + historical.size() + ")",
                createGroupedProgressSessions(historical, selectedSession, sessionLists, true));
        javafx.scene.control.TabPane sessionTabs = new javafx.scene.control.TabPane(pendingTab, historyTab);
        sessionTabs.setTabClosingPolicy(javafx.scene.control.TabPane.TabClosingPolicy.UNAVAILABLE);
        sessionTabs.setMinWidth(340);
        Runnable restoreSelection = () -> {
            sessionLists.forEach(list -> list.getSelectionModel().clearSelection());
            selectedSession.set(null);
            List<ListView<StudyBlock>> visibleLists = sessionLists.stream()
                    .filter(list -> !list.getItems().isEmpty()
                            && PlanningViewGroups.needsConfirmation(list.getItems().get(0)) != progressHistorySelected).toList();
            for (ListView<StudyBlock> list : visibleLists) {
                for (StudyBlock block : list.getItems()) {
                    if (block.getId().equals(selectedProgressSessionId)) {
                        list.getSelectionModel().select(block);
                        return;
                    }
                }
            }
            if (!visibleLists.isEmpty()) visibleLists.get(0).getSelectionModel().selectFirst();
        };
        sessionTabs.getSelectionModel().select(progressHistorySelected ? historyTab : pendingTab);
        sessionTabs.getSelectionModel().selectedItemProperty().addListener((obs, oldTab, tab) -> {
            progressHistorySelected = tab == historyTab;
            restoreSelection.run();
        });
        restoreSelection.run();
        TitledPane correctionDetails = new TitledPane("Correction history for selected session", historyList);
        correctionDetails.setExpanded(false);
        VBox editor = createEditor("Session result", new VBox(8, selectionHelp,
                new Label("Partially completed — actual hours:"), partialHoursField, correctionDetails),
                completedButton, partialButton, skippedButton);
        HBox sessionsPanel = new HBox(16, sessionTabs, editor);
        HBox.setHgrow(sessionTabs, Priority.ALWAYS);
        Label warning = new Label(scheduleMayBeOutdated ? "Workload changed. Review Schedule and replan when you choose." : "");
        warning.setWrapText(true);
        TitledPane taskSummary = new TitledPane("Task progress (" + tasks.size() + ") — confirmed hours", taskCards);
        taskSummary.setExpanded(false);
        VBox content = new VBox(16, totals, outcomes, inferenceNote, warning, sessionsPanel, taskSummary);
        return createPage("Progress", "Confirm sessions by task. Recorded results stay in History and can be corrected there.", content);
    }

    private Node createGroupedProgressSessions(
            List<StudyBlock> blocks,
            javafx.beans.property.ObjectProperty<StudyBlock> selection,
            List<ListView<StudyBlock>> sessionLists,
            boolean history) {
        VBox groups = new VBox(10);
        groups.setPadding(new Insets(12, 0, 0, 0));
        if (blocks.isEmpty()) {
            Label empty = new Label(history ? "No recorded sessions yet. Completed, partial, and skipped results appear here."
                    : "No sessions to confirm. Generate a schedule for more work, or review recorded sessions in History.");
            empty.setWrapText(true);
            groups.getChildren().add(empty);
        }
        PlanningViewGroups.sessionsByTask(blocks).forEach((taskId, sessions) -> {
            ListView<StudyBlock> list = createSessionList(sessions, "No sessions.");
            list.setMinHeight(130);
            list.setPrefHeight(Math.min(420, Math.max(160, sessions.size() * 180)));
            sessionLists.add(list);
            list.getSelectionModel().selectedItemProperty().addListener((obs, oldBlock, block) -> {
                if (block == null) return;
                sessionLists.stream().filter(other -> other != list)
                        .forEach(other -> other.getSelectionModel().clearSelection());
                selection.set(block);
            });
            Task task = sessions.get(0).getTask();
            TitledPane group = new TitledPane(taskTitle(task) + " (" + sessions.size() + " sessions)", list);
            group.setExpanded(!history || sessions.stream().anyMatch(block -> block.getId().equals(selectedProgressSessionId)));
            group.setStyle("-fx-border-color: " + taskColour(task) + "; -fx-border-width: 0 0 0 4;");
            list.getSelectionModel().selectedItemProperty().addListener((obs, oldBlock, block) -> {
                if (block != null) group.setExpanded(true);
            });
            groups.getChildren().add(group);
        });
        return groups;
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
        return "Planning window: " + displayedSchedule.getStartDate() + " to "
                + displayedSchedule.getEndDate() + "\n"
                + upcomingSessions(displayedSchedule).size() + " upcoming session(s), "
                + historicalSessions(displayedSchedule).size() + " retained history session(s) · State: "
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

    static String partialPlanMessage(ScheduleInfeasibleException failure) {
        double scheduledHours = upcomingSessions(failure.getPartialSchedule()).stream()
                .mapToDouble(StudyBlock::durationHours).sum();
        return String.format("Partial plan saved: %.2f hours of remaining work scheduled; %.2f hours still unscheduled. "
                        + "Your recorded progress is preserved. Add available study time before the task deadlines, then replan. ",
                scheduledHours, failure.getWorkloadDeficitHours()) + failure.getMessage();
    }

    static List<StudyBlock> upcomingSessions(Schedule schedule) {
        return schedule == null ? List.of() : schedule.getStudyBlocks().stream()
                .filter(b -> b.getProgressStatus() == ProgressStatus.PLANNED).toList();
    }

    static List<StudyBlock> historicalSessions(Schedule schedule) {
        return schedule == null ? List.of() : schedule.getStudyBlocks().stream()
                .filter(b -> b.getProgressStatus() != ProgressStatus.PLANNED).toList();
    }

    static String taskColour(Task task) {
        String[] palette = {"#3568df", "#008575", "#a047b3", "#b35a18", "#c04766", "#53668c"};
        return palette[Math.floorMod(task.getId().hashCode(), palette.length)];
    }

    private ListView<StudyBlock> createSessionList(List<StudyBlock> blocks, String emptyMessage) {
        ListView<StudyBlock> list = new ListView<>(FXCollections.observableArrayList(blocks));
        list.setMinHeight(240); list.setPrefHeight(400);
        Label empty = new Label(emptyMessage); empty.setWrapText(true); list.setPlaceholder(empty);
        list.setCellFactory(view -> new ListCell<>() {
            @Override protected void updateItem(StudyBlock block, boolean empty) {
                super.updateItem(block, empty);
                setText(null);
                if (empty || block == null) { setGraphic(null); return; }
                Task task = block.getTask();
                Label session = new Label("Session " + (getIndex() + 1));
                session.getStyleClass().add("session-number");
                Label title = new Label(task instanceof AcademicTask academic
                        ? academic.getCourse().getCode() + ": " + task.getTitle() : task.getTitle());
                title.setWrapText(true); title.getStyleClass().add("session-title");
                String time = block.getStart().format(DateTimeFormatter.ofPattern("EEE, MMM d · h:mm a"))
                        + " – " + block.getEnd().format(DateTimeFormatter.ofPattern("h:mm a"));
                Label timing = new Label(time); timing.setWrapText(true);
                Label duration = new Label(String.format("%.2f hours", block.durationHours()));
                String outcome = block.isInferred() ? "Tentative — awaiting confirmation"
                        : switch (block.getProgressStatus()) {
                            case PLANNED -> "Planned";
                            case COMPLETED -> "Confirmed completed";
                            case PARTIAL -> "Partially completed";
                            case SKIPPED -> "Skipped";
                        };
                Label status = new Label(outcome); status.setWrapText(true);
                VBox card = new VBox(5, session, title, timing, duration, status);
                card.getStyleClass().add("session-card");
                card.setStyle("-fx-border-color: " + taskColour(task) + ";");
                card.prefWidthProperty().bind(view.widthProperty().subtract(40));
                setGraphic(card);
            }
        });
        return list;
    }

    private String formatProgressRecord(ProgressRecord record) {
        return record.getRecordedAt().format(DateTimeFormatter.ofPattern("MMM d, yyyy · h:mm a"))
                + "\n" + (record.isInferred() ? "Tentative " : "Confirmed ")
                + record.getStatus().name().toLowerCase(java.util.Locale.ROOT)
                + String.format(" · %.2f hours", record.getCompletedHours());
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
        javafx.scene.layout.FlowPane actionBar = new javafx.scene.layout.FlowPane(8, 8, actions);
        for (Node action : actions) {
            if (action instanceof Button button) {
                button.setMinWidth(javafx.scene.layout.Region.USE_PREF_SIZE);
                button.setWrapText(true);
            }
        }
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
        VBox page = new VBox(18, heading, subtitle);
        String instruction = pageInstruction(currentPage);
        if (!instruction.isBlank()) {
            Label instructionLabel = new Label(instruction);
            instructionLabel.setWrapText(true);
            instructionLabel.getStyleClass().add("page-instruction");
            page.getChildren().add(instructionLabel);
        }
        page.getChildren().add(content);
        page.setPadding(new Insets(28, 32, 32, 32));
        page.getStyleClass().add("page-content");
        VBox.setVgrow(content, Priority.ALWAYS);
        ScrollPane scroll = new ScrollPane(page);
        scroll.setFitToWidth(true);
        scroll.getStyleClass().add("page-scroll");
        return scroll;
    }

    private String pageInstruction(Page page) {
        return switch (page) {
            case OVERVIEW -> "";
            case COURSES -> "Add course code and name. Select a course below to edit or remove it; academic tasks use this list.";
            case TASKS -> "Start with task type, title, due date, and estimated hours. Academic tasks also need a course. Priority settings are optional.";
            case AVAILABILITY -> "Add time marked Available for study. To change an existing window, select it, edit the times, and press Save changes. Add classes and other commitments so the planner avoids them.";
            case SCHEDULE -> "Choose dates and generate or replan remaining work. Only upcoming sessions appear below; reported sessions stay in retained history and Progress. Matching colours indicate the same task. Unchanged inputs can produce the same times.";
            case PROGRESS -> "Open a task group in To confirm and select a session. Saved results move to History, where you can correct them. Tentative progress stays here until confirmed. Replanning always requires your action.";
            case AI_ASSISTANT -> "AI tools are optional. Review estimates and schedule recommendations before applying them.";
        };
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
        if (aiBusy.get()) return;
        aiBusy.set(true);
        boolean bound = trigger.disableProperty().isBound();
        if (!bound) trigger.setDisable(true);
        statusLabel.setText(progressMessage);
        javafx.concurrent.Task<T> backgroundTask = new javafx.concurrent.Task<>() {
            @Override
            protected T call() {
                return operation.get();
            }
        };
        backgroundTask.setOnSucceeded(event -> {
            aiBusy.set(false);
            if (!bound) trigger.setDisable(false);
            try {
                onSuccess.accept(backgroundTask.getValue());
            } catch (RuntimeException exception) {
                showError(errorMessage(exception));
            }
        });
        backgroundTask.setOnFailed(event -> {
            aiBusy.set(false);
            if (!bound) trigger.setDisable(false);
            showError(errorMessage(backgroundTask.getException()));
        });
        Thread worker = new Thread(backgroundTask, "planpilot-ai-request");
        worker.setDaemon(true);
        worker.start();
    }

    private void populateTaskForm(
            ParsedTaskData proposal,
            TextField titleField,
            DatePicker deadlineDate,
            TextField deadlineTime,
            TextField effortField,
            ComboBox<Integer> difficultyField,
            ComboBox<PriorityLevel> importanceField,
            ComboBox<String> kindField,
            ComboBox<Course> courseField,
            TextField weightField,
            ComboBox<AcademicTaskType> typeField,
            Runnable updateAcademicFields) {
        titleField.setText(proposal.title());
        deadlineDate.setValue(proposal.deadline().toLocalDate());
        deadlineTime.setText(proposal.deadline().toLocalTime().toString());
        effortField.setText(Double.toString(proposal.estimatedHours()));
        difficultyField.setValue(proposal.difficulty());
        importanceField.setValue(proposal.importance());
        kindField.setValue(proposal.taskKind() == ParsedTaskKind.ACADEMIC
                ? "Academic" : "General");
        if (proposal.courseCode() == null) {
            courseField.setValue(null);
        } else {
            Course matchingCourse = controller.getCourses().stream()
                    .filter(course -> course.getId().equalsIgnoreCase(
                                    proposal.courseCode())
                            || course.getCode().equalsIgnoreCase(
                                    proposal.courseCode())
                            || course.getName().equalsIgnoreCase(
                                    proposal.courseCode()))
                    .findFirst()
                    .orElse(null);
            courseField.setValue(matchingCourse);
        }
        weightField.setText(proposal.assessmentWeight() == null
                ? "" : proposal.assessmentWeight().toString());
        typeField.setValue(proposal.academicTaskType());
        updateAcademicFields.run();
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
        alert.initOwner(mainStage);
        alert.initModality(Modality.WINDOW_MODAL);
        alert.setTitle("PlanPilot Error");
        alert.setHeaderText("PlanPilot couldn't complete this action");
        alert.setContentText(message);
        alert.showAndWait();
        statusLabel.setText("Error: " + message);
    }

    private String formatAvailability(AvailabilityBlock block) {
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("MMM d, h:mm a");
        return availabilityTypeLabel(block.getType()) + " · "
                + block.getStart().format(formatter) + "–"
                + block.getEnd().format(DateTimeFormatter.ofPattern("h:mm a"));
    }

    private String availabilityTypeLabel(AvailabilityType type) {
        if (type == null) {
            return "";
        }
        return switch (type) {
            case AVAILABLE -> "Available time";
            case CLASS -> "Class";
            case WORK -> "Work";
            case APPOINTMENT -> "Appointment";
            case INTERVIEW -> "Interview";
            case UNAVAILABLE -> "Unavailable";
        };
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
            ListView<?> listView, List<T> items, String itemName) {
        int index = listView.getSelectionModel().getSelectedIndex();
        if (index < 0 || index >= items.size()) {
            throw new IllegalArgumentException(
                    "Select a " + itemName + " first.");
        }
        return items.get(index);
    }

    private enum Page {
        OVERVIEW("Overview"),
        COURSES("Courses"),
        TASKS("Tasks"),
        AVAILABILITY("Availability"),
        SCHEDULE("Schedule"),
        PROGRESS("Progress"),
        AI_ASSISTANT("AI Tools");

        private final String label;

        Page(String label) {
            this.label = label;
        }
    }
}

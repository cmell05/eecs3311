package com.planpilot.gui;

import com.planpilot.controller.PlanPilotController;
import com.planpilot.domain.Course;
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
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

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
        stage.setScene(new Scene(root, 960, 640));
        stage.setMinWidth(760);
        stage.setMinHeight(480);
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
        VBox.setVgrow(navigation, Priority.ALWAYS);
        return navigation;
    }

    private void showPage(Page page) {
        currentPage = page;
        root.setCenter(switch (page) {
            case COURSES -> createCoursesPage();
            case TASKS -> createTasksPage();
            case AVAILABILITY -> createPlaceholderPage(
                    "Availability", "Availability forms will be added in a later step.");
            case SCHEDULE -> createPlaceholderPage(
                    "Schedule", "Generate and review schedule controls will be added later.");
            case PROGRESS -> createPlaceholderPage(
                    "Progress", "Progress reporting controls will be added later.");
        });
        statusLabel.setText(page.label);
    }

    private Node createCoursesPage() {
        List<Course> courses = controller.getCourses();
        ListView<String> list = new ListView<>(FXCollections.observableArrayList(
                courses.stream()
                        .map(course -> course.getCode() + " - " + course.getName())
                        .toList()));
        list.setPlaceholder(new Label("No courses added yet."));
        return createPage("Courses", "View and manage academic courses.", list);
    }

    private Node createTasksPage() {
        List<Task> tasks = controller.getOpenTasks();
        ListView<String> list = new ListView<>(FXCollections.observableArrayList(
                tasks.stream()
                        .map(task -> task.getTitle() + " - " + task.getStatus()
                                + " (" + task.getRemainingHours() + "h remaining)")
                        .toList()));
        list.setPlaceholder(new Label("No open tasks."));
        return createPage("Tasks", "View current schedulable work.", list);
    }

    private Node createPlaceholderPage(String title, String description) {
        return createPage(title, description, new Label("Navigation shell ready."));
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

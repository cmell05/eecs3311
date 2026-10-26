package com.planpilot.service;

import com.planpilot.domain.Course;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public class CourseService {
    private final Map<String, Course> courses = new LinkedHashMap<>();

    public Course addCourse(Course courseData) {
        validateCourse(courseData);
        if (courses.containsKey(courseData.getId())) {
            throw new IllegalArgumentException(
                    "A course with id " + courseData.getId() + " already exists.");
        }
        courses.put(courseData.getId(), courseData);
        return courseData;
    }

    public void updateCourse(Course course) {
        validateCourse(course);
        requireExistingCourse(course.getId());
        courses.put(course.getId(), course);
    }

    public void deleteCourse(String courseId) {
        requireCourseId(courseId);
        requireExistingCourse(courseId);
        courses.remove(courseId);
    }

    public List<Course> getCourses() {
        return List.copyOf(courses.values());
    }

    boolean containsCourse(String courseId) {
        return courses.containsKey(courseId);
    }

    private void validateCourse(Course course) {
        Objects.requireNonNull(course, "Course cannot be null.");
        requireCourseId(course.getId());
        requireText(course.getCode(), "Course code");
        requireText(course.getName(), "Course name");
    }

    private void requireExistingCourse(String courseId) {
        if (!courses.containsKey(courseId)) {
            throw new IllegalArgumentException("No course exists with id " + courseId + ".");
        }
    }

    private static void requireCourseId(String courseId) {
        requireText(courseId, "Course id");
    }

    private static void requireText(String value, String fieldName) {
        Objects.requireNonNull(value, fieldName + " cannot be null.");
        if (value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " cannot be blank.");
        }
    }
}

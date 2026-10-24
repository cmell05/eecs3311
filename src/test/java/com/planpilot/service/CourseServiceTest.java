package com.planpilot.service;

import com.planpilot.domain.Course;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CourseServiceTest {
    @Test
    void addsAndReturnsCourses() {
        CourseService service = new CourseService();
        Course course = new Course("C1", "EECS3311", "Software Design");

        Course created = service.addCourse(course);

        assertSame(course, created);
        assertEquals(List.of(course), service.getCourses());
    }

    @Test
    void updatesExistingCourse() {
        CourseService service = new CourseService();
        service.addCourse(new Course("C1", "EECS3311", "Software Design"));
        Course replacement = new Course("C1", "EECS3311", "Software Design Project");

        service.updateCourse(replacement);

        assertSame(replacement, service.getCourses().get(0));
    }

    @Test
    void deletesExistingCourse() {
        CourseService service = new CourseService();
        service.addCourse(new Course("C1", "EECS3311", "Software Design"));

        service.deleteCourse("C1");

        assertTrue(service.getCourses().isEmpty());
    }

    @Test
    void rejectsInvalidOperations() {
        CourseService service = new CourseService();
        Course course = new Course("C1", "EECS3311", "Software Design");
        service.addCourse(course);

        assertThrows(IllegalArgumentException.class, () -> service.addCourse(course));
        assertThrows(IllegalArgumentException.class,
                () -> service.updateCourse(new Course("missing", "EECS1000", "Unknown")));
        assertThrows(IllegalArgumentException.class, () -> service.deleteCourse("missing"));
        assertThrows(NullPointerException.class, () -> service.addCourse(null));
        assertThrows(UnsupportedOperationException.class,
                () -> service.getCourses().add(course));
    }
}

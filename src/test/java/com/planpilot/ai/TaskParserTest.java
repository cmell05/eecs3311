package com.planpilot.ai;

import com.planpilot.domain.AcademicTaskType;
import com.planpilot.domain.PriorityLevel;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TaskParserTest {
    private final TaskParser parser = new TaskParser();

    @Test
    void parsesValidatedAcademicTaskProposal() {
        ParsedTaskData data = parser.parse("""
                {
                  "title": "OS assignment",
                  "deadline": "2026-11-10T17:00:00",
                  "estimatedHours": 4.5,
                  "difficulty": 4,
                  "importance": "HIGH",
                  "taskKind": "ACADEMIC",
                  "courseCode": "EECS3311",
                  "assessmentWeight": 10,
                  "academicTaskType": "ASSIGNMENT"
                }
                """);

        assertEquals("OS assignment", data.title());
        assertEquals(LocalDateTime.of(2026, 11, 10, 17, 0),
                data.deadline());
        assertEquals(4.5, data.estimatedHours(), 0.0001);
        assertEquals(4, data.difficulty());
        assertEquals(PriorityLevel.HIGH, data.importance());
        assertEquals(ParsedTaskKind.ACADEMIC, data.taskKind());
        assertEquals("EECS3311", data.courseCode());
        assertEquals(10, data.assessmentWeight(), 0.0001);
        assertEquals(AcademicTaskType.ASSIGNMENT, data.academicTaskType());
    }

    @Test
    void appliesSafeDefaultsToOptionalGeneralTaskFields() {
        ParsedTaskData data = parser.parse("""
                {
                  "title": "Prepare portfolio",
                  "deadline": "2026-11-12T12:00:00",
                  "estimatedHours": 3
                }
                """);

        assertEquals(3, data.difficulty());
        assertEquals(PriorityLevel.MEDIUM, data.importance());
        assertEquals(ParsedTaskKind.GENERAL, data.taskKind());
        assertNull(data.courseCode());
        assertNull(data.assessmentWeight());
        assertNull(data.academicTaskType());
    }

    @Test
    void rejectsMissingMalformedAndInconsistentData() {
        assertThrows(LLMClientException.class,
                () -> parser.parse("{}"));
        assertThrows(LLMClientException.class,
                () -> parser.parse("""
                        {"title":"Task","deadline":"tomorrow",
                        "estimatedHours":2}
                        """));
        assertThrows(LLMClientException.class,
                () -> parser.parse("""
                        {"title":"Task","deadline":"2026-11-10T17:00:00",
                        "estimatedHours":-1}
                        """));
        assertThrows(LLMClientException.class,
                () -> parser.parse("""
                        {"title":"Task","deadline":"2026-11-10T17:00:00",
                        "estimatedHours":2,"taskKind":"GENERAL",
                        "courseCode":"EECS3311"}
                        """));
    }
}

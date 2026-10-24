package com.planpilot.domain;

import java.util.Objects;

public class Course {
    private final String id;
    private String code;
    private String name;

    public Course(String id, String code, String name) {
        this.id = requireText(id, "Course id");
        this.code = requireText(code, "Course code");
        this.name = requireText(name, "Course name");
    }

    public String getId() { return id; }
    public String getCode() { return code; }
    public String getName() { return name; }

    public void setCode(String code) {
        this.code = requireText(code, "Course code");
    }

    public void rename(String newName) {
        this.name = requireText(newName, "Course name");
    }

    private static String requireText(String value, String fieldName) {
        Objects.requireNonNull(value, fieldName + " cannot be null.");
        if (value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " cannot be blank.");
        }
        return value.trim();
    }
}

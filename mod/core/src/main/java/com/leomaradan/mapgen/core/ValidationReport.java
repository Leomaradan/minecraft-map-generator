package com.leomaradan.mapgen.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Errors (blocking) and warnings (informative) collected while reading or validating a map pack. */
public final class ValidationReport {
    private final List<String> errors = new ArrayList<>();
    private final List<String> warnings = new ArrayList<>();

    public void error(String message) {
        this.errors.add(message);
    }

    public void warn(String message) {
        this.warnings.add(message);
    }

    public boolean hasErrors() {
        return !this.errors.isEmpty();
    }

    public List<String> errors() {
        return Collections.unmodifiableList(this.errors);
    }

    public List<String> warnings() {
        return Collections.unmodifiableList(this.warnings);
    }

    public void addAll(ValidationReport other) {
        this.errors.addAll(other.errors);
        this.warnings.addAll(other.warnings);
    }

    /** Adds the messages of another report, each starting with {@code prefix}. */
    public void addAll(ValidationReport other, String prefix) {
        other.errors.forEach(e -> this.errors.add(prefix + e));
        other.warnings.forEach(w -> this.warnings.add(prefix + w));
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        for (String e : this.errors) {
            sb.append("ERROR: ").append(e).append('\n');
        }
        for (String w : this.warnings) {
            sb.append("WARNING: ").append(w).append('\n');
        }
        return sb.toString();
    }
}

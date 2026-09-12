package com.smartexam.vo;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

public final class StudentImportViews {
    private StudentImportViews() {}

    public record Header(int index, String name) {}

    public record Preview(int totalRows, List<Header> headers, boolean mappingRequired,
                          Integer usernameColumn, Integer nameColumn, List<Row> rows) {}

    public record Result(int createdCount, int enrolledExistingCount, int skippedCount, int failedCount,
                         List<Row> rows, String resultWorkbookBase64) {}

    public record Row(int rowNumber, String username, String name, String status, String statusText,
                      @JsonInclude(JsonInclude.Include.NON_NULL) String initialPassword) {}
}

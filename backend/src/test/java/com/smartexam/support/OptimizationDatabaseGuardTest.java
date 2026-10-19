package com.smartexam.support;

import org.junit.jupiter.api.Test;
import java.lang.reflect.InvocationTargetException;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class OptimizationDatabaseGuardTest {
    private static final String DB = "smart_exam_opt_test_20261019";
    private static final String SCOPED = "GRANT ALL PRIVILEGES ON `smart\\_exam\\_opt\\_test\\_20261019`.* TO `test`@`127.0.0.1`";
    private static final String USAGE = "GRANT USAGE ON *.* TO `test`@`127.0.0.1`";

    private void validate(String actual, List<String> grants) throws Throwable {
        Class<?> type;
        try { type = Class.forName("com.smartexam.support.OptimizationTestDatabaseGuard"); }
        catch (ClassNotFoundException e) { fail("Isolation guard is not implemented", e); return; }
        try { type.getMethod("validate", String.class, List.class).invoke(null, actual, grants); }
        catch (InvocationTargetException e) { throw e.getCause(); }
    }

    @Test void permitsOnlyTheExactTestDatabase() throws Throwable { validate(DB, List.of(USAGE, SCOPED)); }
    @Test void rejectsBusinessDatabase() { assertThrows(IllegalStateException.class, () -> validate("smart_exam", List.of(USAGE, SCOPED))); }
    @Test void rejectsRestoreDatabase() { assertThrows(IllegalStateException.class, () -> validate("smart_exam_opt_restore_20261019", List.of(SCOPED))); }
    @Test void rejectsGlobalAccess() { assertThrows(IllegalStateException.class, () -> validate(DB, List.of("GRANT SELECT ON *.* TO `test`@`127.0.0.1`", SCOPED))); }
    @Test void rejectsGrantOption() { assertThrows(IllegalStateException.class, () -> validate(DB, List.of(SCOPED + " WITH GRANT OPTION"))); }
    @Test void rejectsWildcardSchemaGrants() { assertThrows(IllegalStateException.class, () -> validate(DB, List.of(SCOPED.replace("\\_", "_")))); }
    @Test void rejectsAnotherSchema() { assertThrows(IllegalStateException.class, () -> validate(DB, List.of(SCOPED, "GRANT SELECT ON `another`.* TO `test`@`127.0.0.1`"))); }
    @Test void rejectsMissingPrivileges() { assertThrows(IllegalStateException.class, () -> validate(DB, List.of(USAGE))); }
    @Test void rejectsRoleGrantsIncludingMandatoryRoles() { assertThrows(IllegalStateException.class, () -> validate(DB, List.of(USAGE, SCOPED, "GRANT `broad_role`@`%` TO `test`@`127.0.0.1`"))); }
}

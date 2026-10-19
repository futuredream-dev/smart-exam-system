package com.smartexam.support;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.jdbc.core.JdbcTemplate;
import javax.sql.DataSource;
import java.util.List;
import java.util.regex.Pattern;

/** Test-source-only fail-closed check of the actual Spring datasource and account grants. */
@Configuration(proxyBeanMethods = false)
public class OptimizationTestDatabaseGuard implements InitializingBean {
    public static final String EXPECTED_DATABASE = "smart_exam_opt_test_20261019";
    private static final Pattern SCOPED = Pattern.compile("^GRANT (.+?) ON `([^`]+)`\\.\\* TO .+$");
    private final DataSource source;
    private final Environment environment;

    public OptimizationTestDatabaseGuard(DataSource source, Environment environment) {
        this.source = source;
        this.environment = environment;
    }

    @Override public void afterPropertiesSet() {
        if (!environment.acceptsProfiles(Profiles.of("optimization-test"))) {
            throw new IllegalStateException("ISOLATION_PROFILE_REQUIRED");
        }
        JdbcTemplate jdbc = new JdbcTemplate(source);
        validate(jdbc.queryForObject("SELECT DATABASE()", String.class),
                jdbc.queryForList("SHOW GRANTS", String.class));
    }

    public static void validate(String actualDatabase, List<String> grants) {
        if (!EXPECTED_DATABASE.equals(actualDatabase)) throw new IllegalStateException("DB_TARGET_MISMATCH");
        if (grants == null || grants.isEmpty()) throw new IllegalStateException("MISSING_TEST_PRIVILEGES");
        String allowed = EXPECTED_DATABASE.replace("_", "\\_");
        boolean found = false;
        for (String grant : grants) {
            if (grant == null || grant.contains("WITH GRANT OPTION")) throw new IllegalStateException("UNSAFE_PRIVILEGES");
            if (grant.startsWith("GRANT USAGE ON *.* TO ")) continue;
            var match = SCOPED.matcher(grant);
            if (!match.matches() || !allowed.equals(match.group(2))) throw new IllegalStateException("UNSAFE_PRIVILEGES");
            found = true;
        }
        if (!found) throw new IllegalStateException("MISSING_TEST_PRIVILEGES");
    }

    /** Tests explicitly trigger expiry methods; background scans must not race test transactions. */
    @Bean static BeanFactoryPostProcessor disableBackgroundSchedulingInTests() {
        return factory -> {
            String name = "org.springframework.context.annotation.internalScheduledAnnotationProcessor";
            if (factory instanceof BeanDefinitionRegistry registry && registry.containsBeanDefinition(name)) {
                registry.removeBeanDefinition(name);
            }
        };
    }
}

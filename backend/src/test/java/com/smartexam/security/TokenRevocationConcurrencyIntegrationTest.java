package com.smartexam.security;

import com.smartexam.dto.AdminTeacherRequests;
import com.smartexam.entity.Users;
import com.smartexam.service.AdminTeacherService;
import com.smartexam.service.UsersService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** 独立线程使用真实数据库事务，不能依赖测试外层回滚事务隐藏并发覆盖。 */
@SpringBootTest
class TokenRevocationConcurrencyIntegrationTest {
    @Autowired UsersService users;
    @Autowired AdminTeacherService teachers;
    @Autowired PasswordEncoder encoder;
    @Autowired JdbcTemplate jdbc;
    private final List<Long> created = new ArrayList<>();

    @AfterEach void cleanup() { for (Long id : created) users.removeById(id); }

    @Test void concurrentResetsNeverLoseARevocationVersion() throws Exception {
        Users teacher = teacher();
        parallel(() -> teachers.resetPassword(teacher.getId()), () -> teachers.resetPassword(teacher.getId()));
        assertThat(version(teacher)).isEqualTo(2L);
        assertThat(users.getById(teacher.getId()).getMustChangePassword()).isTrue();
    }

    @Test void concurrentRepeatedDisablingRevokesExactlyOnce() throws Exception {
        Users teacher = teacher();
        parallel(() -> teachers.setStatus(teacher.getId(), new AdminTeacherRequests.Status("DISABLED")),
                () -> teachers.setStatus(teacher.getId(), new AdminTeacherRequests.Status("DISABLED")));
        assertThat(version(teacher)).isEqualTo(1L);
        assertThat(users.getById(teacher.getId()).getStatus()).isZero();
    }

    @Test void concurrentResetAndDisableCannotReenableTheAccountOrLoseAVersion() throws Exception {
        Users teacher = teacher();
        parallel(() -> teachers.resetPassword(teacher.getId()),
                () -> teachers.setStatus(teacher.getId(), new AdminTeacherRequests.Status("DISABLED")));
        assertThat(version(teacher)).isEqualTo(2L);
        Users actual = users.getById(teacher.getId());
        assertThat(actual.getStatus()).isZero();
        assertThat(actual.getMustChangePassword()).isTrue();
    }

    private long version(Users user) { return jdbc.queryForObject("SELECT token_version FROM users WHERE id=?", Long.class, user.getId()); }
    private Users teacher() {
        Users user = new Users(); user.setUsername("t14_race_"+UUID.randomUUID().toString().replace("-", ""));
        user.setName("T14并发合成教师"); user.setRole("TEACHER"); user.setStatus(1); user.setMustChangePassword(false);
        user.setPasswordHash(encoder.encode("initial-pass")); users.save(user); created.add(user.getId()); return user;
    }
    private void parallel(Callable<?> left, Callable<?> right) throws Exception {
        var pool = Executors.newFixedThreadPool(2); var start = new CyclicBarrier(2);
        try {
            var a = pool.submit(() -> { start.await(5, TimeUnit.SECONDS); return left.call(); });
            var b = pool.submit(() -> { start.await(5, TimeUnit.SECONDS); return right.call(); });
            a.get(15, TimeUnit.SECONDS); b.get(15, TimeUnit.SECONDS);
        } finally { pool.shutdownNow(); assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue(); }
    }
}

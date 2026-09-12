package com.smartexam.admin;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.smartexam.config.InitialAdminInitializer;
import com.smartexam.entity.Users;
import com.smartexam.service.UsersService;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class InitialAdminInitializerTest {
    @Test
    void completeConfigurationCreatesOneForcedChangeBcryptAdmin() {
        UsersService users = mock(UsersService.class);
        when(users.count(any(Wrapper.class))).thenReturn(0L);
        BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
        InitialAdminInitializer initializer = configured(users, encoder);

        initializer.run(null);

        var captor = org.mockito.ArgumentCaptor.forClass(Users.class);
        verify(users).save(captor.capture());
        Users admin = captor.getValue();
        assertThat(admin.getUsername()).isEqualTo("root_admin");
        assertThat(admin.getName()).isEqualTo("系统管理员");
        assertThat(admin.getRole()).isEqualTo("ADMIN");
        assertThat(admin.getStatus()).isEqualTo(1);
        assertThat(admin.getMustChangePassword()).isTrue();
        assertThat(admin.getPasswordHash()).startsWith("$2");
        assertThat(encoder.matches("initial-admin-pass", admin.getPasswordHash())).isTrue();
    }

    @Test
    void existingAdminIsNeverCreatedAgainOrReencoded() {
        UsersService users = mock(UsersService.class);
        when(users.count(any(Wrapper.class))).thenReturn(1L);
        var encoder = spy(new BCryptPasswordEncoder());

        configured(users, encoder).run(null);

        verify(users, never()).save(any());
        verify(encoder, never()).encode(any());
    }

    @Test
    void incompleteConfigurationDoesNothing() {
        UsersService users = mock(UsersService.class);
        when(users.count(any(Wrapper.class))).thenReturn(0L);
        InitialAdminInitializer initializer = configured(users, new BCryptPasswordEncoder());
        ReflectionTestUtils.setField(initializer, "initialPassword", "");

        initializer.run(null);

        verify(users, never()).save(any());
    }

    private static InitialAdminInitializer configured(UsersService users, BCryptPasswordEncoder encoder) {
        InitialAdminInitializer initializer = new InitialAdminInitializer(users, encoder);
        ReflectionTestUtils.setField(initializer, "username", "root_admin");
        ReflectionTestUtils.setField(initializer, "initialPassword", "initial-admin-pass");
        ReflectionTestUtils.setField(initializer, "name", "系统管理员");
        return initializer;
    }
}

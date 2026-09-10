package com.smartexam.operations;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 运维检查：访问真实数据库，但不公开连接参数、组件详情或环境变量。 */
@SpringBootTest
@AutoConfigureMockMvc
class HealthEndpointIntegrationTest {
    @Autowired MockMvc mvc;
    @Test void healthIncludesDatabaseWithoutExposingDetails() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk())
            .andExpect(content().string("{\"status\":\"UP\"}"));
    }
    @Test void environmentEndpointIsNotPublic() throws Exception {
        mvc.perform(get("/actuator/env")).andExpect(status().isUnauthorized());
    }
}

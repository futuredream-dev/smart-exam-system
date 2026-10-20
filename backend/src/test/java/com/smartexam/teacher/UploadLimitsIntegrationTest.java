package com.smartexam.teacher;

import com.smartexam.security.JwtService;
import com.smartexam.service.UsersService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.io.ByteArrayOutputStream;
import java.time.Duration;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
class UploadLimitsIntegrationTest {
    @LocalServerPort int port;
    @Autowired JwtService jwt;
    @Autowired UsersService users;
    @Autowired ObjectMapper json;
    HttpResponse<String> upload(int bytes,String path) throws Exception {
        String boundary="OptUploadBoundary";
        var body=new ByteArrayOutputStream();
        body.write(("--"+boundary+"\r\nContent-Disposition: form-data; name=\"file\"; filename=\"limits.xlsx\"\r\nContent-Type: application/vnd.openxmlformats-officedocument.spreadsheetml.sheet\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        body.write(new byte[bytes]);body.write(("\r\n--"+boundary+"--\r\n").getBytes(StandardCharsets.UTF_8));
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).timeout(Duration.ofSeconds(15))
                .header("Authorization","Bearer "+jwt.generate(users.findByUsername("teacher_demo")))
                .header("Content-Type","multipart/form-data; boundary="+boundary).POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray())).build();
        return HttpClient.newHttpClient().send(request,HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }
    @Test void questionBankOneMiBLimitStillAppliesBelowServletLimit() throws Exception {
        var response=upload(1024*1024+1,"/api/teacher/question-banks/1/imports/excel/preview");
        assertThat(response.statusCode()).isEqualTo(400);assertThat(json.readTree(response.body()).path("message").asText()).contains("1MB");
    }
    @Test void servletFiveMiBFileAndSixMiBRequestLimitsReturnReadableJson() throws Exception {
        for(int bytes:new int[]{5*1024*1024+1,6*1024*1024+1}) {
            var response=upload(bytes,"/api/teacher/courses/1/students/import-preview");
            assertThat(response.statusCode()).isEqualTo(413);assertThat(response.headers().firstValue("Content-Type").orElse("")).contains("application/json");
            assertThat(json.readTree(response.body()).path("message").asText()).contains("5MB","6MB");
        }
    }
}

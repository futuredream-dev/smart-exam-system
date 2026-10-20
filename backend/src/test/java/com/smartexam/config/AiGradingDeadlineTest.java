package com.smartexam.config;

import com.smartexam.service.ai.AiGradingProvider;
import com.smartexam.service.ai.AiGradingException;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import java.net.InetSocketAddress;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class AiGradingDeadlineTest {
    private AiGradingProvider.GradeRequest request(){return new AiGradingProvider.GradeRequest("合成测试题",BigDecimal.TEN,"合成参考答案",List.of(),"合成答案");}
    private HttpServer server(long delay) throws Exception {
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/chat/completions",exchange->{
            exchange.getRequestBody().readAllBytes();
            try {Thread.sleep(delay);}catch(InterruptedException e){Thread.currentThread().interrupt();}
            byte[] body="{\"choices\":[{\"message\":{\"content\":\"{\\\"aiScore\\\":8,\\\"aiReason\\\":\\\"ok\\\",\\\"aiComment\\\":\\\"ok\\\",\\\"matchedPoints\\\":[],\\\"missingPoints\\\":[]}\"}}]}".getBytes(StandardCharsets.UTF_8);
            try {exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);}finally{exchange.close();}
        });server.start();return server;
    }
    @Test void aResponseAfterTenSecondsStillSucceedsWithinTheThirtySecondBackendBudget() throws Exception {
        HttpServer server=server(10_500);
        try {
            var provider=new AiGradingConfiguration().aiGradingProvider(RestClient.builder(),"synthetic-test-key","http://127.0.0.1:"+server.getAddress().getPort(),"synthetic-model",30);
            assertThat(provider.grade(request()).score()).isEqualByComparingTo("8");
        }finally{server.stop(0);}
    }
    @Test void configuredReadDeadlineStopsTheLocalProvider() throws Exception {
        HttpServer server=server(2000);
        try {
            var provider=new AiGradingConfiguration().aiGradingProvider(RestClient.builder(),"synthetic-test-key","http://127.0.0.1:"+server.getAddress().getPort(),"synthetic-model",1);
            assertThatThrownBy(()->provider.grade(request())).isInstanceOf(AiGradingException.class).hasMessageContaining("超时或无法连接");
        }finally{server.stop(0);}
    }
}

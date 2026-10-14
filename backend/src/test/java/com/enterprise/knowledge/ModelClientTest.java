package com.enterprise.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class ModelClientTest {
    @Test void ollamaNativeProtocol() throws Exception {
        var json=new ObjectMapper();var request=new AtomicReference<String>();
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/api/embed",exchange->{
            byte[] response="{\"embeddings\":[[1,0,0]]}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200,response.length);exchange.getResponseBody().write(response);exchange.close();
        });
        server.createContext("/api/chat",exchange->{
            request.set(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));
            byte[] response="{\"message\":{\"content\":\"OK [1]\"}}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200,response.length);exchange.getResponseBody().write(response);exchange.close();
        });server.start();
        try {
            var endpoint=new ModelSettings.Endpoint("OLLAMA","http://127.0.0.1:"+server.getAddress().getPort(),"","test");
            var client=new ModelClient(json,new ModelSettings.Config(true,true,endpoint,endpoint,"v1"));
            assertThat(client.embed("测试")).containsExactly(1,0,0);
            assertThat(client.answer("test","[1] OK")).isEqualTo("OK [1]");
            assertThat(json.readTree(request.get()).get("stream").asBoolean()).isFalse();
            assertThat(json.readTree(request.get()).get("options").get("num_predict").asInt()).isEqualTo(1500);
        }finally {server.stop(0);}
    }
    @Test void openAiCompatibleProtocolAndErrorHandling() throws Exception {
        var json=new ObjectMapper();var request=new AtomicReference<String>();
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/v1/embeddings",exchange->{
            request.set(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));
            byte[] response="{\"data\":[{\"embedding\":[0.2,0.4,0.8]}]}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200,response.length);exchange.getResponseBody().write(response);exchange.close();
        });
        server.createContext("/v1/chat/completions",exchange->{
            request.set(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));
            byte[] response="{\"choices\":[{\"message\":{\"content\":\"需要代码审查。[1]\"}}]}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200,response.length);exchange.getResponseBody().write(response);exchange.close();
        });
        server.start();
        try {
            var client=new ModelClient(json,"http://127.0.0.1:"+server.getAddress().getPort()+"/v1/","test-key","chat-test","embed-test","v1",true);
            assertThat(client.embed("研发")).containsExactly(0.2,0.4,0.8);
            assertThat(json.readTree(request.get()).get("model").asText()).isEqualTo("embed-test");
            assertThat(client.answer("需要什么？","[1] 需要代码审查")).contains("[1]");
            assertThat(json.readTree(request.get()).get("messages").size()).isEqualTo(2);
        } finally {server.stop(0);}
    }
}

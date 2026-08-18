package com.example.miniredis.web;

import com.example.miniredis.store.Store;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;

@QuarkusTest
class CmdResourceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Inject
    Store store;

    @BeforeEach
    void reset() {
        store.flushAll();
    }

    private String json(String cmd, List<String> args) throws Exception {
        Map<String, Object> req = new LinkedHashMap<>();
        req.put("cmd", cmd);
        req.put("args", args);
        return MAPPER.writeValueAsString(req);
    }

    @Test
    void postCmd_ping() throws Exception {
        given()
                .contentType("application/json")
                .body(json("PING", List.of()))
                .when().post("/cmd")
                .then()
                .statusCode(200)
                .body("type", equalTo("simple"))
                .body("value", equalTo("PONG"));
    }

    @Test
    void postCmd_setThenGet() throws Exception {
        given()
                .contentType("application/json")
                .body(json("SET", List.of("hello", "world")))
                .when().post("/cmd")
                .then()
                .statusCode(200)
                .body("type", equalTo("simple"))
                .body("value", equalTo("OK"));

        given()
                .contentType("application/json")
                .body(json("GET", List.of("hello")))
                .when().post("/cmd")
                .then()
                .statusCode(200)
                .body("type", equalTo("bulk"))
                .body("value", equalTo("world"))
                .body("nil", is(false));
    }

    @Test
    void postCmd_unknownCommandReturnsError() throws Exception {
        given()
                .contentType("application/json")
                .body(json("NOSUCH", List.of()))
                .when().post("/cmd")
                .then()
                .statusCode(200)
                .body("type", equalTo("error"));
    }

    @Test
    void getStaticIndex() {
        given()
                .when().get("/")
                .then()
                .statusCode(200)
                .body(containsString("miniRedis"));
    }
}

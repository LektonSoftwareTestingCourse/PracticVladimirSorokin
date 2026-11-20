package com.processing.api.support;

import io.restassured.builder.RequestSpecBuilder;
import io.restassured.specification.RequestSpecification;

import static io.restassured.RestAssured.given;

public final class Api {
    public static final RequestSpecification GATEWAY = spec(18080);
    public static final RequestSpecification CARDS = spec(18081);
    public static final RequestSpecification SWITCH = spec(18082);
    public static final RequestSpecification AUTH = spec(18083);
    public static final RequestSpecification MERCHANT = spec(18084);
    public static final RequestSpecification TERMINAL = spec(18085);
    public static final RequestSpecification LOGGER = spec(18088);
    public static final RequestSpecification FAULTS = spec(18090);
    public static final RequestSpecification BINS = spec(18096);

    private Api() {
    }

    public static RequestSpecification gateway() {
        return given().spec(GATEWAY).header("X-Forwarded-For", Data.clientIp());
    }

    private static RequestSpecification spec(int port) {
        return new RequestSpecBuilder().setBaseUri(System.getProperty("api.host", "http://127.0.0.1"))
                .setPort(port).setContentType("application/json").setAccept("application/json")
                .setConfig(io.restassured.config.RestAssuredConfig.config()
                        .httpClient(io.restassured.config.HttpClientConfig.httpClientConfig()
                                .setParam("http.connection.timeout", 5000).setParam("http.socket.timeout", 15000))
                        .logConfig(
                        io.restassured.config.LogConfig.logConfig().enableLoggingOfRequestAndResponseIfValidationFails()))
                .build();
    }

}

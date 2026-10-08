package com.security.bank.support;

import java.net.http.HttpHeaders;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

public record HttpResult(int status, HttpHeaders headers, String body) {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public JsonNode json() {
	try {
	    return MAPPER.readTree(body);
	} catch (Exception e) {
	    throw new AssertionError("Response body is not JSON: " + body, e);
	}
    }

    public String header(String name) {
	return headers.firstValue(name).orElse(null);
    }
}

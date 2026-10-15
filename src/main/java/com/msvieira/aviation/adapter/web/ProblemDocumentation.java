package com.msvieira.aviation.adapter.web;

import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.headers.Header;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.IntegerSchema;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import org.springdoc.core.customizers.OperationCustomizer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;

import java.util.List;
import java.util.Map;

@Component
public class ProblemDocumentation implements OperationCustomizer {

    public static final String SCHEMA_NAME = "Problem";
    public static final String SCHEMA_REF = "#/components/schemas/" + SCHEMA_NAME;

    private static final Map<String, String> SHARED_RESPONSES = Map.of(
            "400",
            "Malformed input, e.g. an ICAO code that is not four letters",
            "502",
            "Provider returned an unusable response",
            "503",
            "Provider unavailable, provider rate limit, local outbound budget exhausted, circuit open, or concurrency limit reached",
            "504",
            "Provider timed out",
            "500",
            "Unexpected error; details are only in the logs");

    @Override
    public Operation customize(final Operation operation, final HandlerMethod handlerMethod) {
        final ApiResponses responses = operation.getResponses();
        SHARED_RESPONSES.forEach((status, description) -> responses.putIfAbsent(status, problem(description)));
        responses
                .get("503")
                .addHeaderObject(
                        HttpHeaders.RETRY_AFTER,
                        new Header()
                                .description("Seconds to wait before retrying, when the provider told us")
                                .schema(new IntegerSchema()));
        return operation;
    }

    public static Schema<Object> schema() {
        final ObjectSchema schema = new ObjectSchema();
        schema.setDescription("RFC 9457 Problem Details with a stable type URN, a timestamp and the correlation id");
        schema.addProperty(
                "type", new StringSchema().format("uri").example("urn:aviation-api-wrapper:problem:airport-not-found"));
        schema.addProperty("title", new StringSchema().example("Airport not found"));
        schema.addProperty("status", new IntegerSchema().example(404));
        schema.addProperty("detail", new StringSchema().example("No airport record available for ICAO code 'ZZZZ'"));
        schema.addProperty("instance", new StringSchema().format("uri").example("/api/v1/airports/ZZZZ"));
        schema.addProperty("timestamp", new StringSchema().format("date-time").example("2026-10-15T15:05:06.579Z"));
        schema.addProperty("correlationId", new StringSchema().example("b0706f23-64b5-4503-8bcc-ddf4f191e177"));
        schema.setRequired(List.of("type", "title", "status"));
        return schema;
    }

    private static ApiResponse problem(final String description) {
        return new ApiResponse()
                .description(description)
                .content(new Content()
                        .addMediaType(
                                MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                                new io.swagger.v3.oas.models.media.MediaType()
                                        .schema(new Schema<>().$ref(SCHEMA_REF))));
    }
}

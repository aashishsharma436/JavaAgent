package com.java.agent;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.matcher.ElementMatchers;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import javax.servlet.http.HttpServletResponse;
import java.lang.instrument.Instrumentation;
import java.util.Arrays;

public class ApiTracingAgent {
    public static void premain(String agentArgs, Instrumentation instrumentation) {
        new AgentBuilder.Default().type(ElementMatchers.any())
                .transform((builder, typeDescription, classLoader, javaModule, protectionDomain) ->
                        builder.method(ElementMatchers.isMethod()
                                        .and(ElementMatchers.returns(ResponseEntity.class)))
                                .intercept(Advice.to(OutboundHttpInterceptor.class)))
                .installOn(instrumentation);

        new AgentBuilder.Default().type(ElementMatchers.any())
                .transform((builder, typeDescription, classLoader, javaModule, protectionDomain) ->
                        builder.method(ElementMatchers.isAnnotatedWith(org.springframework.web.bind.annotation.RequestMapping.class))
                                .intercept(Advice.to(ApiTracingInterceptor.class)))
                .installOn(instrumentation);
    }


    public static class OutboundHttpInterceptor {

        @Advice.OnMethodEnter
        public static void enter(@Advice.Origin String method, @Advice.This Object object) {
            Tracer tracer = OpenTelemetrySdk.builder().build().getTracer("api-tracer");
            Span span = tracer.spanBuilder("Outbound HTTP Call: " + method)
                    .setSpanKind(SpanKind.CLIENT)
                    .startSpan();
            try (Scope scope = span.makeCurrent()) {
                if (object instanceof RestTemplate) {
                    RestTemplate restTemplate = (RestTemplate) object;
                    String url = ""; // Implement logic to dynamically retrieve the URL
                    span.setAttribute("http.method", "GET"); // Assuming all calls are GET requests
                    span.setAttribute("http.url", url);
                }
            }
        }

        @Advice.OnMethodExit(onThrowable = Throwable.class)
        public static void exit(@Advice.Origin String method, @Advice.Thrown Throwable throwable, @Advice.Return Object returnValue) {
            Span span = Span.current();
            if (throwable != null) {
                System.out.println("Outbound HTTP Call (Error): " + method + ", Error: " + throwable.getMessage());
                span.setStatus(io.opentelemetry.api.trace.StatusCode.ERROR);
            } else {
                int statusCode = getStatusCode(returnValue);
                System.out.println("Outbound HTTP Call: " + method + ", Status Code: " + statusCode);
                span.setStatus(io.opentelemetry.api.trace.StatusCode.OK);
                // Optionally, add more attributes to the span based on the response
                span.setAttribute("http.status_code", statusCode);
            }
            span.end();
        }

        public static int getStatusCode(Object returnValue) {
            if (returnValue instanceof ResponseEntity) {
                return ((ResponseEntity) returnValue).getStatusCode().value();
            } else {
                return 200;
            }
        }
    }

    public static class ApiTracingInterceptor {
            @Advice.OnMethodEnter
            public static void enter(@Advice.Origin String method, @Advice.AllArguments Object[] args) {
                System.out.println("API Request: " + method + ", Arguments: " + Arrays.toString(args));
                Tracer tracer = OpenTelemetrySdk.builder().build().getTracer("api-tracer");
                Span span = tracer.spanBuilder("API Request: " + method).startSpan();
                try (Scope scope = span.makeCurrent()) {
                }
            }

            @Advice.OnMethodExit(onThrowable = Throwable.class)
            public static void exit(@Advice.Origin String method, @Advice.Thrown Throwable throwable, @Advice.Return Object returnValue) {
                Span span = Span.current();
                if (throwable != null) {
                    System.out.println("API Response (Error): " + method + ", Error: " + throwable.getMessage());
                    span.setStatus(StatusCode.ERROR);
                } else {
                    int statusCode = getStatusCode(returnValue);
                    System.out.println("API Response: " + method + ", Status Code: " + statusCode);
                }
                span.end();
            }

            public static int getStatusCode(Object returnValue) {
                if (returnValue instanceof HttpServletResponse) {
                    return ((HttpServletResponse) returnValue).getStatus();
                } else {
                    return 200;
                }
            }
    }
}
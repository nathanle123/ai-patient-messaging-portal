package edu.cmpe272.portal.fhir;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.rest.api.EncodingEnum;
import ca.uhn.fhir.rest.client.api.IGenericClient;
import ca.uhn.fhir.rest.client.api.IRestfulClientFactory;
import ca.uhn.fhir.rest.client.api.ServerValidationModeEnum;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** HAPI FHIR R4 structures + generic REST client (no HAPI server). */
@Configuration
public class FhirConfig {

    /** Expensive to build (model scanning), so exactly one per application. Thread-safe. */
    @Bean
    FhirContext fhirContext(FhirProperties properties) {
        FhirContext ctx = FhirContext.forR4();
        IRestfulClientFactory factory = ctx.getRestfulClientFactory();
        factory.setConnectTimeout((int) properties.connectTimeout().toMillis());
        factory.setSocketTimeout((int) properties.readTimeout().toMillis());
        factory.setConnectionRequestTimeout((int) properties.connectTimeout().toMillis());
        factory.setPoolMaxPerRoute(20);
        factory.setPoolMaxTotal(20);
        // The mock serves /metadata, but checking it before the first call adds a request and a
        // startup ordering dependency for no benefit here.
        factory.setServerValidationMode(ServerValidationModeEnum.NEVER);
        return ctx;
    }

    /** Thread-safe; no logging interceptor is registered, so request and response bodies are never logged. */
    @Bean
    IGenericClient fhirClient(FhirContext ctx, FhirProperties properties) {
        IGenericClient client = ctx.newRestfulGenericClient(properties.baseUrl());
        client.setEncoding(EncodingEnum.JSON);
        return client;
    }
}

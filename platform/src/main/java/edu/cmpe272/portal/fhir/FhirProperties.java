package edu.cmpe272.portal.fhir;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param enabled         patient lookup on intake and Communication write-back
 * @param baseUrl         FHIR R4 base, e.g. http://fhir-mock:8000/fhir
 * @param patientCacheTtl how long a user's resolved Patient id is reused (Patients are static in the mock)
 * @param sweepBatch      messages per run of the write-back sweeper
 */
@ConfigurationProperties("app.fhir")
public record FhirProperties(boolean enabled, String baseUrl, Duration connectTimeout, Duration readTimeout,
                             Duration patientCacheTtl, int sweepBatch) {
}

package edu.cmpe272.portal.config;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import edu.cmpe272.portal.analytics.AnalyticsAggregator;
import edu.cmpe272.portal.analytics.AnalyticsController;
import edu.cmpe272.portal.analytics.AnalyticsProperties;
import edu.cmpe272.portal.analytics.AnalyticsQueries;
import edu.cmpe272.portal.analytics.KafkaInsights;
import edu.cmpe272.portal.analytics.LiveMetrics;
import edu.cmpe272.portal.analytics.ServiceHealthMonitor;
import edu.cmpe272.portal.audit.AuditAction;
import edu.cmpe272.portal.audit.AuditController;
import edu.cmpe272.portal.audit.AuditService;
import edu.cmpe272.portal.auth.AuthController;
import edu.cmpe272.portal.auth.AuthService;
import edu.cmpe272.portal.auth.AuthenticatedUser;
import edu.cmpe272.portal.auth.InvalidCredentialsException;
import edu.cmpe272.portal.auth.JwtAuthenticationFilter;
import edu.cmpe272.portal.auth.JwtProperties;
import edu.cmpe272.portal.auth.JwtService;
import edu.cmpe272.portal.common.GlobalExceptionHandler;
import edu.cmpe272.portal.common.SecurityErrorHandlers;
import edu.cmpe272.portal.fhir.FhirAdminController;
import edu.cmpe272.portal.fhir.FhirWriteBackStore;
import edu.cmpe272.portal.inbox.ClinicianController;
import edu.cmpe272.portal.inbox.InboxQueries;
import edu.cmpe272.portal.inbox.SimilarityProperties;
import edu.cmpe272.portal.messaging.DemoMessageController;
import edu.cmpe272.portal.messaging.IntakeService;
import edu.cmpe272.portal.messaging.MessageOriginalRepository;
import edu.cmpe272.portal.messaging.MessageRepository;
import edu.cmpe272.portal.messaging.PatientMessageController;
import edu.cmpe272.portal.user.AppUser;
import edu.cmpe272.portal.user.Role;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** Exercises the real security chain, JWT filter and RBAC annotations; services are mocked. */
@WebMvcTest(controllers = {AuthController.class, AuditController.class, PatientMessageController.class,
        ClinicianController.class, AnalyticsController.class, DemoMessageController.class, FhirAdminController.class})
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, JwtService.class, SecurityErrorHandlers.class,
        GlobalExceptionHandler.class})
@EnableConfigurationProperties({JwtProperties.class, SimilarityProperties.class})
@TestPropertySource(properties = {
        "app.jwt.secret=test-secret-that-is-at-least-32-bytes-long!",
        "app.jwt.ttl=60m",
        "app.jwt.issuer=portal-platform",
        "app.similarity.threshold=0.5"})
class SecurityRulesTest {

    @Autowired MockMvc mvc;
    @Autowired JwtService jwtService;
    @MockitoBean AuthService authService;
    @MockitoBean AuditService auditService;
    @MockitoBean IntakeService intakeService;
    @MockitoBean MessageRepository messageRepository;
    @MockitoBean MessageOriginalRepository originalRepository;
    @MockitoBean InboxQueries inboxQueries;
    @MockitoBean AnalyticsQueries analyticsQueries;
    @MockitoBean AnalyticsAggregator aggregator;
    @MockitoBean LiveMetrics liveMetrics;
    @MockitoBean ServiceHealthMonitor healthMonitor;
    @MockitoBean KafkaInsights kafkaInsights;
    @MockitoBean AnalyticsProperties analyticsProperties;
    @MockitoBean FhirWriteBackStore fhirWriteBackStore;

    private String tokenFor(Role role) {
        return "Bearer " + jwtService.issue(new AppUser("user-" + role, "x", role, "Test", "MRN-1")).token();
    }

    @Test
    void protectedEndpointWithoutTokenIs401() throws Exception {
        mvc.perform(get("/api/admin/audit-log"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401));
    }

    @Test
    void invalidTokenIs401() throws Exception {
        mvc.perform(get("/api/admin/audit-log").header("Authorization", "Bearer not.a.jwt"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void patientAndClinicianCannotReadAuditLogAndDenialIsAudited() throws Exception {
        for (Role role : new Role[] {Role.PATIENT, Role.CLINICIAN}) {
            mvc.perform(get("/api/admin/audit-log").header("Authorization", tokenFor(role)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.status").value(403));
        }
        verify(auditService, org.mockito.Mockito.times(2))
                .record(any(AuthenticatedUser.class), eq(AuditAction.ACCESS_DENIED), eq("endpoint"), any(), any());
        verify(auditService, never()).search(any(), any());
    }

    @Test
    void adminCanReadAuditLogAndTheReadIsAudited() throws Exception {
        when(auditService.search(any(), any())).thenReturn(Page.empty());

        mvc.perform(get("/api/admin/audit-log").header("Authorization", tokenFor(Role.ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isArray());
        verify(auditService).record(any(AuthenticatedUser.class), eq(AuditAction.VIEW_AUDIT_LOG), any(), any(), any());
    }

    @Test
    void loginIsPublicAndBadCredentialsAre401() throws Exception {
        when(authService.login("maria.lopez", "wrong")).thenThrow(new InvalidCredentialsException());

        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"maria.lopez\",\"password\":\"wrong\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Invalid username or password"));
    }

    @Test
    void validationErrorsDoNotEchoSubmittedValues() throws Exception {
        String secretValue = "x".repeat(200);

        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + secretValue + "\",\"password\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors").isArray())
                .andExpect(result -> org.assertj.core.api.Assertions
                        .assertThat(result.getResponse().getContentAsString()).doesNotContain(secretValue));
    }

    @Test
    void onlyPatientsCanSubmitMessages() throws Exception {
        for (Role role : new Role[] {Role.CLINICIAN, Role.ADMIN}) {
            mvc.perform(post("/api/messages").header("Authorization", tokenFor(role))
                            .contentType(MediaType.APPLICATION_JSON).content("{\"text\":\"hello\"}"))
                    .andExpect(status().isForbidden());
        }
        verify(intakeService, never()).submit(any(), any());
    }

    @Test
    void onlyCliniciansCanReadTheInboxOrOriginals() throws Exception {
        String id = java.util.UUID.randomUUID().toString();
        for (Role role : new Role[] {Role.PATIENT, Role.ADMIN}) {
            mvc.perform(get("/api/clinician/inbox").header("Authorization", tokenFor(role)))
                    .andExpect(status().isForbidden());
            mvc.perform(get("/api/clinician/messages/" + id + "/original").header("Authorization", tokenFor(role)))
                    .andExpect(status().isForbidden());
            mvc.perform(post("/api/clinician/messages/" + id + "/review").header("Authorization", tokenFor(role)))
                    .andExpect(status().isForbidden());
        }
        verify(originalRepository, never()).findById(any());
        verify(inboxQueries, never()).markReviewed(any(), any());
    }

    @Test
    void oversizedMessageIsRejectedWithoutEcho() throws Exception {
        String text = "Maria Lopez ".repeat(500);
        mvc.perform(post("/api/messages").header("Authorization", tokenFor(Role.PATIENT))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"text\":\"" + text + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(result -> org.assertj.core.api.Assertions
                        .assertThat(result.getResponse().getContentAsString()).doesNotContain("Maria"));
        verify(intakeService, never()).submit(any(), any());
    }

    @Test
    void analyticsIsAdminOnly() throws Exception {
        for (Role role : new Role[] {Role.PATIENT, Role.CLINICIAN}) {
            for (String path : new String[] {"/api/admin/analytics/summary", "/api/admin/analytics/trends",
                    "/api/admin/analytics/technical"}) {
                mvc.perform(get(path).header("Authorization", tokenFor(role))).andExpect(status().isForbidden());
            }
            mvc.perform(post("/api/admin/analytics/aggregate").header("Authorization", tokenFor(role)))
                    .andExpect(status().isForbidden());
        }
        verify(analyticsQueries, never()).summary(any());
        verify(aggregator, never()).hourly(any(), any());
    }

    @Test
    void fhirAdminIsAdminOnlyAndResyncIsAudited() throws Exception {
        for (Role role : new Role[] {Role.PATIENT, Role.CLINICIAN}) {
            mvc.perform(get("/api/admin/fhir/status").header("Authorization", tokenFor(role)))
                    .andExpect(status().isForbidden());
            mvc.perform(post("/api/admin/fhir/resync").header("Authorization", tokenFor(role)))
                    .andExpect(status().isForbidden());
        }
        verify(fhirWriteBackStore, never()).resetAll();

        when(fhirWriteBackStore.resetAll()).thenReturn(7);
        mvc.perform(post("/api/admin/fhir/resync").header("Authorization", tokenFor(Role.ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reset").value(7));
        verify(auditService).record(any(), eq(AuditAction.FHIR_RESYNC), eq("message"), any(), eq("reset=7"));
    }

    @Test
    void analyticsRejectsUnknownSource() throws Exception {
        mvc.perform(get("/api/admin/analytics/summary?source=everything").header("Authorization", tokenFor(Role.ADMIN)))
                .andExpect(status().isBadRequest());
        verify(analyticsQueries, never()).summary(any());
    }

    @Test
    void demoEndpointIsAbsentUnlessDemoModeIsOn() throws Exception {
        mvc.perform(post("/api/demo/messages").header("Authorization", tokenFor(Role.PATIENT))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"text\":\"hi\",\"source\":\"synthetic\"}"))
                .andExpect(status().isNotFound());
    }
}

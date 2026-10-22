package edu.cmpe272.portal.fhir;

import ca.uhn.fhir.rest.api.MethodOutcome;
import ca.uhn.fhir.rest.client.api.IGenericClient;
import ca.uhn.fhir.rest.server.exceptions.BaseServerResponseException;
import java.util.Optional;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Communication;
import org.hl7.fhir.r4.model.Patient;
import org.springframework.stereotype.Component;

/**
 * The only class that talks to the FHIR server. Every failure becomes a {@link FhirException} that
 * carries the exception class and HTTP status only: HAPI's own exception messages can include the
 * response body, so they are never logged or rethrown.
 */
@Component
public class FhirGateway {

    /** System of Patient.identifier for medical record numbers (matches the mock). */
    public static final String MRN_SYSTEM = "urn:cmpe272:mrn";

    private final IGenericClient client;

    public FhirGateway(IGenericClient client) {
        this.client = client;
    }

    /** @return the logical id of the Patient whose identifier is this MRN, if exactly one exists */
    public Optional<String> findPatientIdByMrn(String mrn) {
        Bundle bundle = call(() -> client.search()
                .forResource(Patient.class)
                .where(Patient.IDENTIFIER.exactly().systemAndCode(MRN_SYSTEM, mrn))
                .returnBundle(Bundle.class)
                .execute());
        if (bundle.getEntry().size() != 1) {
            return Optional.empty();
        }
        return Optional.of(bundle.getEntryFirstRep().getResource().getIdElement().getIdPart());
    }

    public record WriteResult(String id, boolean created) {
    }

    /**
     * Conditional create (If-None-Exist on the message identifier): a retry, a duplicate trigger or a
     * resync never creates a second Communication for the same message.
     */
    public WriteResult createCommunication(Communication communication) {
        var identifier = communication.getIdentifierFirstRep();
        MethodOutcome outcome = call(() -> client.create()
                .resource(communication)
                .conditional()
                .where(Communication.IDENTIFIER.exactly().systemAndIdentifier(identifier.getSystem(), identifier.getValue()))
                .execute());
        if (outcome.getId() == null || outcome.getId().getIdPart() == null) {
            throw new FhirException("MissingId", null, false);
        }
        return new WriteResult(outcome.getId().getIdPart(), Boolean.TRUE.equals(outcome.getCreated()));
    }

    private static <T> T call(java.util.function.Supplier<T> request) {
        try {
            return request.get();
        } catch (BaseServerResponseException e) {
            int status = e.getStatusCode();
            // 4xx means the server will never accept this resource as sent; 408/429 and 5xx may pass later.
            boolean permanent = status >= 400 && status < 500 && status != 408 && status != 429;
            throw new FhirException(e.getClass().getSimpleName(), status, !permanent);
        } catch (RuntimeException e) {
            // FhirClientConnectionException (refused, timeout), parse errors, ...
            throw new FhirException(e.getClass().getSimpleName(), null, true);
        }
    }
}

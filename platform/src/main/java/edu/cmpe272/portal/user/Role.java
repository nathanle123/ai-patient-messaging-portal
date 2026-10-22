package edu.cmpe272.portal.user;

public enum Role {
    PATIENT,
    CLINICIAN,
    ADMIN;

    /** Spring Security authority name, e.g. {@code ROLE_PATIENT}. */
    public String authority() {
        return "ROLE_" + name();
    }
}

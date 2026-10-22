package edu.cmpe272.portal.common;

public class NotFoundException extends RuntimeException {

    public NotFoundException(String what) {
        super(what + " not found");
    }
}

package com.qchm.xyz.imaccess;

public class ImSessionException extends Exception {
    private final int code;
    private final boolean authenticationFailure;

    public ImSessionException(int code, String message, boolean authenticationFailure) {
        super(message);
        this.code = code;
        this.authenticationFailure = authenticationFailure;
    }

    public int getCode() {
        return code;
    }

    public boolean isAuthenticationFailure() {
        return authenticationFailure;
    }
}

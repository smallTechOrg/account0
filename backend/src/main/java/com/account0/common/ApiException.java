package com.account0.common;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

public class ApiException extends ResponseStatusException {
    public ApiException(HttpStatus status, String msg) { super(status, msg); }
    public static ApiException notFound(String what) { return new ApiException(HttpStatus.NOT_FOUND, what + " not found"); }
    public static ApiException bad(String msg) { return new ApiException(HttpStatus.BAD_REQUEST, msg); }
}

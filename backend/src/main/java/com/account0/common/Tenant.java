package com.account0.common;

/** Single-user for now; every query is scoped by tenant id so multi-tenancy is a later auth change only. */
public final class Tenant {
    public static final long DEFAULT = 1L;
    private Tenant() {}
    public static long current() { return DEFAULT; }
}

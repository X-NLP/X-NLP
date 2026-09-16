package com.xnlp.server.security;

/** Stable tenant-membership failure classification for the HTTP error contract. */
public class TenantMembershipException extends RuntimeException {

    public enum Reason {
        MEMBERSHIP_NOT_FOUND,
        LAST_ADMIN_REQUIRED,
        ROLE_INVALID
    }

    private final Reason reason;

    private TenantMembershipException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }

    public static TenantMembershipException notFound() {
        return new TenantMembershipException(
                Reason.MEMBERSHIP_NOT_FOUND, "Tenant membership was not found");
    }

    public static TenantMembershipException lastAdminRequired() {
        return new TenantMembershipException(
                Reason.LAST_ADMIN_REQUIRED, "At least one tenant administrator is required");
    }

    public static TenantMembershipException roleInvalid() {
        return new TenantMembershipException(
                Reason.ROLE_INVALID, "At least one valid tenant role is required");
    }
}

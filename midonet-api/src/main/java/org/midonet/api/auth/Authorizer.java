/*
 * Copyright 2012 Midokura PTE LTD.
 */
package org.midonet.api.auth;

import org.midonet.midolman.state.StateAccessException;

import javax.ws.rs.core.SecurityContext;
import java.io.Serializable;

/**
 * Base class for authorization service.
 */
public abstract class Authorizer <T extends Serializable> {

    public abstract boolean authorize(SecurityContext context,
                                      AuthAction action, T id)
            throws StateAccessException;

    public static boolean isAdmin(SecurityContext context) {
        return (context.isUserInRole(AuthRole.ADMIN));
    }

    public static <T> boolean isOwner(SecurityContext context, T id) {
        return context.getUserPrincipal().getName().equals(id);
    }

    public static <T> boolean isAdminOrOwner(SecurityContext context, T id) {
        return isAdmin(context) || isOwner(context, id);
    }

}
/*
 * Copyright (c) 2014 Midokura Europe SARL, All Rights Reserved.
 */
package org.midonet.cluster.data.neutron;

import org.midonet.cluster.data.Rule;
import org.midonet.midolman.serialization.SerializationException;
import org.midonet.midolman.state.StateAccessException;

import javax.annotation.Nonnull;
import java.util.List;
import java.util.UUID;

public interface L3Api {

    /**
     * Create a new router data in the data store. StatePathExistsException
     * thrown if a router with the same ID already exists.
     *
     * @param router Router object to create
     * @return Created Router object
     */
    public Router createRouter(@Nonnull Router router)
            throws StateAccessException, SerializationException;

    /**
     * Delete a router. Nothing happens if the resource does not exist.
     *
     * @param id ID of the Router object to delete
     */
    public void deleteRouter(@Nonnull UUID id)
            throws StateAccessException, SerializationException;

    /**
     * Retrieve a router. Returns null if the resource does not exist.
     *
     * @param id ID of the Router object to get
     * @return Router object
     */
    public Router getRouter(@Nonnull UUID id)
            throws StateAccessException, SerializationException;

    /**
     * Get all the routers.
     *
     * @return List of Router objects.
     */
    public List<Router> getRouters()
            throws StateAccessException, SerializationException;

    /**
     * Update a router.  NoStatePathException is thrown if the resource does
     * not exist.
     *
     * @param id ID of the Router object to update
     * @return Updated Router object
     */
    public Router updateRouter(@Nonnull UUID id, @Nonnull Router router)
            throws StateAccessException, SerializationException,
            Rule.RuleIndexOutOfBoundsException;

    /**
     * Add an interface on the network to link to a router.
     *
     * @param routerId ID of the router to link
     * @param routerInterface Router interface info
     * @return RouterInterface info created
     */
    public RouterInterface addRouterInterface(
            @Nonnull UUID routerId, @Nonnull RouterInterface routerInterface)
            throws StateAccessException, SerializationException;

    /**
     * Remove an interface on the network linked to a router.
     *
     * @param routerId ID of the router to unlink
     * @param routerInterface Router interface info
     * @return RouterInterface info removed
     */
    public RouterInterface removeRouterInterface(
            @Nonnull UUID routerId, @Nonnull RouterInterface routerInterface);

    /**
     * Create a new floating IP in the data store. StatePathExistsException
     * thrown if a floating IP with the same ID already exists.
     *
     * @param floatingIp FloatingIp object to create
     * @return Created FloatingIp object
     */
    public FloatingIp createFloatingIp(@Nonnull FloatingIp floatingIp)
            throws StateAccessException, SerializationException;

    /**
     * Delete a floating IP. Nothing happens if the resource does not exist.
     *
     * @param id ID of the FloatingIp object to delete
     */
    public void deleteFloatingIp(@Nonnull UUID id)
            throws StateAccessException, SerializationException;

    /**
     * Retrieve a floating IP. Returns null if the resource does not exist.
     *
     * @param id ID of the FloatingIp object to get
     * @return Router object
     */
    public FloatingIp getFloatingIp(@Nonnull UUID id)
            throws StateAccessException, SerializationException;

    /**
     * Get all the floating IP.
     *
     * @return List of FloatingIp objects.
     */
    public List<FloatingIp> getFloatingIps()
            throws StateAccessException, SerializationException;

    /**
     * Update a floating IP..  NoStatePathException is thrown if the resource
     * does not exist.
     *
     * @param id ID of the FloatingIp object to update
     * @return Updated FloatingIp object
     */
    public FloatingIp updateFloatingIp(@Nonnull UUID id,
                                       @Nonnull FloatingIp floatingIp)
            throws StateAccessException, SerializationException,
            Rule.RuleIndexOutOfBoundsException;
}
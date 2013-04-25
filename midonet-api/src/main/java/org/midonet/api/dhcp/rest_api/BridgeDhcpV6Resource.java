/*
 * Copyright 2013 Midokura Europe SARL
 * Copyright 2013 Midokura PTE LTD.
 */

package org.midonet.api.dhcp.rest_api;

import com.google.inject.Inject;
import com.google.inject.assistedinject.Assisted;
import com.google.inject.servlet.RequestScoped;
import org.midonet.api.ResourceUriBuilder;
import org.midonet.api.VendorMediaType;
import org.midonet.api.auth.Authorizer;
import org.midonet.api.auth.ForbiddenHttpException;
import org.midonet.api.dhcp.DhcpSubnet6;
import org.midonet.api.network.auth.BridgeAuthorizer;
import org.midonet.api.rest_api.AbstractResource;
import org.midonet.api.rest_api.NotFoundHttpException;
import org.midonet.api.rest_api.ResourceFactory;
import org.midonet.api.rest_api.RestApiConfig;
import org.midonet.api.auth.AuthAction;
import org.midonet.api.auth.AuthRole;
import org.midonet.api.rest_api.BadRequestHttpException;
import org.midonet.midolman.state.StateAccessException;
import org.midonet.cluster.DataClient;
import org.midonet.cluster.data.dhcp.Subnet6;
import org.midonet.packets.IPv6Subnet;
import org.midonet.packets.IPv6Addr;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.security.PermitAll;
import javax.annotation.security.RolesAllowed;
import javax.validation.ConstraintViolation;
import javax.validation.Validator;
import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.SecurityContext;
import javax.ws.rs.core.UriInfo;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@RequestScoped
public class BridgeDhcpV6Resource extends AbstractResource {

    private final static Logger log = LoggerFactory
            .getLogger(BridgeDhcpV6Resource.class);

    private final UUID bridgeId;
    private final Authorizer authorizer;
    private final Validator validator;
    private final DataClient dataClient;
    private final ResourceFactory factory;

    @Inject
    public BridgeDhcpV6Resource(RestApiConfig config, UriInfo uriInfo,
                                SecurityContext context,
                                BridgeAuthorizer authorizer,
                                Validator validator,
                                DataClient dataClient,
                                ResourceFactory factory,
                                @Assisted UUID bridgeId) {
        super(config, uriInfo, context);
        this.authorizer = authorizer;
        this.validator = validator;
        this.dataClient = dataClient;
        this.factory = factory;
        this.bridgeId = bridgeId;
    }

    /**
     * Host Assignments resource locator for dhcp.
     *
     * @returns DhcpV6HostsResource object to handle sub-resource requests.
     */
    @Path("/{prefix}" + ResourceUriBuilder.DHCPV6_HOSTS)
    public DhcpV6HostsResource getDhcpV6AssignmentsResource(
            @PathParam("prefix") IPv6Subnet prefix) {
        return factory.getDhcpV6AssignmentsResource(bridgeId, prefix);
    }

    /**
     * Handler for creating a DHCPV6 subnet configuration.
     *
     * @param subnet
     *            DHCPV6 subnet configuration object.
     * @throws StateAccessException
     *             Data access error.
     * @returns Response object with 201 status code set if successful.
     */
    @POST
    @RolesAllowed({AuthRole.ADMIN, AuthRole.TENANT_ADMIN})
    @Consumes({ VendorMediaType.APPLICATION_DHCPV6_SUBNET_JSON,
            MediaType.APPLICATION_JSON })
    public Response create(DhcpSubnet6 subnet) throws StateAccessException {

        if (!authorizer.authorize(context, AuthAction.WRITE, bridgeId)) {
            throw new ForbiddenHttpException(
                    "Not authorized to configure DHCPV6 for this bridge.");
        }

        Set<ConstraintViolation<DhcpSubnet6>> violations =
            validator.validate(subnet);
        if (!violations.isEmpty()) {
            throw new BadRequestHttpException(violations);
        }

        dataClient.dhcpSubnet6Create(bridgeId, subnet.toData());

        URI dhcpsUri = ResourceUriBuilder.getBridgeDhcpV6s(getBaseUri(),
                bridgeId);
        return Response.created(
                ResourceUriBuilder.getBridgeDhcpV6(
                    dhcpsUri,
                    new IPv6Subnet(IPv6Addr.fromString(subnet.getPrefix()),
                                       subnet.getPrefixLength()))).build();
    }

    /**
     * Handler to updating a host assignment.
     *
     * @param prefix
     *            Identifier of the DHCPV6 subnet configuration.
     * @param subnet
     *            DHCPV6 subnet configuration object.
     * @throws StateAccessException
     *             Data access error.
     */
    @PUT
    @RolesAllowed({AuthRole.ADMIN, AuthRole.TENANT_ADMIN})
    @Path("/{prefix}")
    @Consumes({ VendorMediaType.APPLICATION_DHCPV6_SUBNET_JSON,
            MediaType.APPLICATION_JSON })
    public Response update(@PathParam("prefix") IPv6Subnet prefix,
            DhcpSubnet6 subnet)
            throws StateAccessException {

        if (!authorizer.authorize(context, AuthAction.WRITE, bridgeId)) {
            throw new ForbiddenHttpException(
                    "Not authorized to update this bridge's dhcpv6 config.");
        }

        // Make sure that the DhcpSubnet6 has the same IP address as the URI.
        subnet.setPrefix(prefix.getAddress().toString());
        subnet.setPrefixLength(prefix.getPrefixLen());
        dataClient.dhcpSubnet6Update(bridgeId, subnet.toData());
        return Response.ok().build();
    }

    /**
     * Handler to getting a DHCPV6 subnet configuration.
     *
     * @param prefix
     *            Subnet IP from the request.
     * @throws StateAccessException
     *             Data access error.
     * @return A Bridge object.
     */
    @GET
    @PermitAll
    @Path("/{prefix}")
    @Produces({ VendorMediaType.APPLICATION_DHCPV6_SUBNET_JSON,
            MediaType.APPLICATION_JSON })
    public DhcpSubnet6 get(@PathParam("prefix") IPv6Subnet prefix)
            throws StateAccessException {

        if (!authorizer.authorize(context, AuthAction.READ, bridgeId)) {
            throw new ForbiddenHttpException(
                    "Not authorized to view this bridge's dhcpv6 config.");
        }

        Subnet6 subnetConfig = dataClient.dhcpSubnet6Get(bridgeId, prefix);
        if (subnetConfig == null) {
            throw new NotFoundHttpException(
                    "The requested resource was not found.");
        }

        DhcpSubnet6 subnet = new DhcpSubnet6(subnetConfig);
        subnet.setParentUri(ResourceUriBuilder.getBridgeDhcpV6s(getBaseUri(),
                bridgeId));

        return subnet;
    }

    /**
     * Handler to deleting a DHCPV6 subnet configuration.
     *
     * @throws org.midonet.midolman.state.StateAccessException
     *             Data access error.
     */
    @DELETE
    @RolesAllowed({AuthRole.ADMIN, AuthRole.TENANT_ADMIN})
    @Path("/{prefix}")
    public void delete(@PathParam("prefix") IPv6Subnet prefix)
            throws StateAccessException {

        if (!authorizer.authorize(context, AuthAction.WRITE, bridgeId)) {
            throw new ForbiddenHttpException(
                    "Not authorized to delete dhcpv6 configuration of "
                            + "this bridge.");
        }

        dataClient.dhcpSubnet6Delete(bridgeId, prefix);
    }

    /**
     * Handler to list DHCPV6 subnet configurations.
     *
     * @throws StateAccessException
     *             Data access error.
     * @return A list of DhcpSubnet6 objects.
     */
    @GET
    @PermitAll
    @Produces({ VendorMediaType.APPLICATION_DHCPV6_SUBNET_COLLECTION_JSON })
    public List<DhcpSubnet6> list() throws StateAccessException {

        if (!authorizer.authorize(context, AuthAction.READ, bridgeId)) {
            throw new ForbiddenHttpException(
                    "Not authorized to view DHCPV6 config of this bridge.");
        }

        List<Subnet6> subnetConfigs =
                dataClient.dhcpSubnet6sGetByBridge(bridgeId);

        List<DhcpSubnet6> subnets = new ArrayList<DhcpSubnet6>();
        URI dhcpsUri = ResourceUriBuilder.getBridgeDhcpV6s(getBaseUri(),
                bridgeId);
        for (Subnet6 subnetConfig : subnetConfigs) {
            DhcpSubnet6 subnet = new DhcpSubnet6(subnetConfig);
            subnet.setParentUri(dhcpsUri);
            subnets.add(subnet);
        }

        return subnets;
    }

}
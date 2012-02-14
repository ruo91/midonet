/*
 * Copyright 2011 Midokura KK
 */

package com.midokura.midolman.layer3;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import javax.management.Attribute;
import javax.management.AttributeList;
import javax.management.AttributeNotFoundException;
import javax.management.DynamicMBean;
import javax.management.InvalidAttributeValueException;
import javax.management.MBeanException;
import javax.management.MBeanInfo;
import javax.management.MBeanNotificationInfo;
import javax.management.ReflectionException;
import javax.management.openmbean.CompositeDataSupport;
import javax.management.openmbean.CompositeType;
import javax.management.openmbean.OpenDataException;
import javax.management.openmbean.OpenMBeanAttributeInfoSupport;
import javax.management.openmbean.OpenMBeanConstructorInfoSupport;
import javax.management.openmbean.OpenMBeanInfoSupport;
import javax.management.openmbean.OpenMBeanOperationInfoSupport;
import javax.management.openmbean.OpenMBeanParameterInfoSupport;
import javax.management.openmbean.SimpleType;
import javax.management.openmbean.TabularData;
import javax.management.openmbean.TabularDataSupport;
import javax.management.openmbean.TabularType;

import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.KeeperException.NoNodeException;
import org.openflow.protocol.OFMatch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.midokura.midolman.L3DevicePort;
import com.midokura.midolman.eventloop.Reactor;
import com.midokura.midolman.openflow.MidoMatch;
import com.midokura.midolman.packets.ARP;
import com.midokura.midolman.packets.Ethernet;
import com.midokura.midolman.packets.ICMP;
import com.midokura.midolman.packets.IPv4;
import com.midokura.midolman.packets.IntIPv4;
import com.midokura.midolman.packets.MAC;
import com.midokura.midolman.rules.RuleEngine;
import com.midokura.midolman.rules.RuleResult;
import com.midokura.midolman.state.ArpCacheEntry;
import com.midokura.midolman.state.ArpTable;
import com.midokura.midolman.state.PortDirectory;
import com.midokura.midolman.util.Callback;
import com.midokura.midolman.util.Net;

/**
 * This class coordinates the routing logic for a single virtual router. It
 * delegates much of its internal logic to other class instances:
 * ReplicatedRoutingTable maintains the routing table and matches flows to
 * routes; RuleEngine maintains the rule chains and applies their logic to
 * packets in the router; NatLeaseManager (in RuleEngine) manages NAT mappings
 * and ip:port reservations.
 *
 * Router is directly responsible for three main tasks:
 * 1) Shepherding a packet through the pre-routing/routing/post-routing flow.
 * 2) Handling all ARP-related packets and maintaining ARP caches (per port).
 * 3) Populating the replicated routing table with routes to this router's
 *    ports that are materialized on the local host.
 *
 * @version ?
 * @author Pino de Candia
 */
public class Router {

    private static final Logger log = LoggerFactory.getLogger(Router.class);

    public static final long ARP_RETRY_MILLIS = 10 * 1000;
    public static final long ARP_TIMEOUT_MILLIS = 60 * 1000;
    public static final long ARP_EXPIRATION_MILLIS = 3600 * 1000;
    public static final long ARP_STALE_MILLIS = 1800 * 1000;

    public static final String PRE_ROUTING = "pre_routing";
    public static final String POST_ROUTING = "post_routing";

    public enum Action {
        BLACKHOLE, NOT_IPV4, NO_ROUTE, FORWARD, REJECT, CONSUMED;
    }

    /**
     * Clients of Router create and partially populate an instance of
     * ForwardInfo to call Router.process(fInfo). The Router populates a number
     * of field to indicate various decisions: the next action for the packet,
     * the next hop gateway address, the egress port, the packet at egress
     * (i.e. after possible modifications).
     */
    public static class ForwardInfo {
        // These fields are filled by the caller of Router.process():
        public UUID inPortId;
        public Ethernet pktIn;
        public MidoMatch flowMatch; // (original) match of any eventual flows
        public MidoMatch matchIn; // the match as it enters the router

        // These fields are filled by Router.process():
        public Action action;
        public UUID outPortId;
        public int nextHopNwAddr;
        public MidoMatch matchOut; // the match as it exits the router
        public boolean trackConnection;

        ForwardInfo() {
        }

        @Override
        public String toString() {
            return "ForwardInfo [inPortId=" + inPortId +
                   ", pktIn=" + pktIn + ", matchIn=" + matchIn +
                   ", action=" + action + ", outPortId=" + outPortId +
                   ", nextHopNwAddr=" + nextHopNwAddr + ", matchOut="
                    + matchOut + ", trackConnection=" + trackConnection + "]";
        }
    }

    /**
     * Router uses one instance of this class to get a callback when there are
     * changes to the routes of any local materialized ports. The callback
     * keeps mirrors those changes to the shared/replicated routing table.
     * of any
     */
    private class PortListener implements L3DevicePort.Listener {
        @Override
        public void configChanged(UUID portId,
                PortDirectory.MaterializedRouterPortConfig old,
                PortDirectory.MaterializedRouterPortConfig current) {
            // Do nothing here. We get the non-routes configuration from the
            // L3DevicePort each time we use it.
        }

        @Override
        public void routesChanged(UUID portId, Collection<Route> added,
                Collection<Route> removed) {
            log.debug("routesChanged: added {} removed {}", added, removed);

            // The L3DevicePort keeps the routes up-to-date and notifies us
            // of changes so we can keep the replicated routing table in sync.
            for (Route rt : added) {
                log.debug("{} routesChanged adding {} to table", this, rt
                        .toString());
                try {
                    table.addRoute(rt);
                } catch (KeeperException e) {
                    log.warn("routesChanged", e);
                } catch (InterruptedException e) {
                    log.warn("routesChanged", e);
                }
            }
            for (Route rt : removed) {
                log.debug("{} routesChanged removing {} from table", this,
                        rt.toString());
                try {
                    table.deleteRoute(rt);
                } catch (KeeperException e) {
                    log.warn("routesChanged", e);
                } catch (InterruptedException e) {
                    log.warn("routesChanged", e);
                }
            }
        }
    }

    protected UUID routerId;
    protected RuleEngine ruleEngine;
    protected ReplicatedRoutingTable table;
    // Note that only materialized ports are tracked. Package visibility for
    // testing.
    Map<UUID, L3DevicePort> devicePorts;
    private PortListener portListener;
    private ArpTable arpTable;
    private Map<UUID, Map<Integer, List<Callback<MAC>>>> arpCallbackLists;
    private Reactor reactor;
    private LoadBalancer loadBalancer;

    public Router(UUID routerId, RuleEngine ruleEngine,
                  ReplicatedRoutingTable table, ArpTable arpTable,
                  Reactor reactor)
            throws OpenDataException {
        this.routerId = routerId;
        this.ruleEngine = ruleEngine;
        this.table = table;
        this.devicePorts = new HashMap<UUID, L3DevicePort>();
        this.portListener = new PortListener();
        this.arpTable = arpTable;
        this.arpCallbackLists = new HashMap<UUID, Map<Integer, List<Callback<MAC>>>>();
        this.reactor = reactor;
        this.loadBalancer = new DummyLoadBalancer(table);
    }

    public String toString() {
        return routerId.toString();
    }

    // This should only be called for materialized ports, not logical ports.
    public void addPort(L3DevicePort port) throws KeeperException,
            InterruptedException {
        devicePorts.put(port.getId(), port);
        log.debug("{} addPort {} with number {}", new Object[] { this,
                port.getId(), port.getNum() });
        port.addListener(portListener);
        for (Route rt : port.getVirtualConfig().getRoutes()) {
            log.debug("{} adding route {} to table", this, rt.toString());
            table.addRoute(rt);
        }
        arpCallbackLists.put(port.getId(),
                new HashMap<Integer, List<Callback<MAC>>>());
    }

    // This should only be called for materialized ports, not logical ports.
    public void removePort(L3DevicePort port) throws KeeperException,
            InterruptedException {
        devicePorts.remove(port.getId());
        log.debug("{} removePort {} with number", new Object[] { this,
                port.getId(), port.getNum() });
        port.removeListener(portListener);
        for (Route rt : port.getVirtualConfig().getRoutes()) {
            log.debug("{} removing route {} from table", this, rt
                    .toString());
            try {
                table.deleteRoute(rt);
            } catch (NoNodeException e) {
                log.warn("removePort got NoNodeException removing route.");
            }
        }
        arpCallbackLists.remove(port.getId());
    }

    /**
     * Get the mac address for an ip address that is accessible from a local
     * L3 port. The callback is invoked immediately within this method if the
     * address is in the ARP cache, otherwise the callback is invoked
     * asynchronously when the address is resolved or the ARP request times out,
     * whichever comes first.
     *
     * @param portId
     * @param nwAddr
     * @param cb
     */
    public void getMacForIp(UUID portId, int nwAddr, Callback<MAC> cb) {
        IntIPv4 intNwAddr = new IntIPv4(nwAddr);
        log.debug("getMacForIp: port {} ip {}", portId, intNwAddr);

        L3DevicePort devPort = devicePorts.get(portId);
        String nwAddrStr = intNwAddr.toString();
        if (null == devPort) {
            log.warn("getMacForIp: {} cannot get mac for {} on port {} - " +
                     "port not local.", new Object[] {this, nwAddrStr, portId});

            throw new IllegalArgumentException(String.format("%s cannot get "
                    + "mac for %s on port %s - port not local.", this,
                    nwAddrStr, portId.toString()));
        }
        // The nwAddr should be in the port's localNwAddr/localNwLength.
        int shift = 32 - devPort.getVirtualConfig().localNwLength;
        if ((nwAddr >>> shift) != (devPort.getVirtualConfig().localNwAddr >>> shift)) {
            log.warn("getMacForIp: {} cannot get mac for {} - address not in network "
                    + "segment of port {}", new Object[] { this, nwAddrStr,
                    portId.toString() });
            // TODO(pino): should this call be invoked asynchronously?
            cb.call(null);
            return;
        }
        ArpCacheEntry entry = arpTable.get(intNwAddr);
        long now = reactor.currentTimeMillis();
        if (null != entry && null != entry.macAddr) {
            if (entry.stale < now && entry.lastArp + ARP_RETRY_MILLIS < now) {
                // Note that ARP-ing to refresh a stale entry doesn't retry.
                log.debug("getMacForIp: {} getMacForIp refreshing ARP cache entry for {}",
                        this, nwAddrStr);
                generateArpRequest(nwAddr, portId);
            }
            // TODO(pino): should this call be invoked asynchronously?
            cb.call(entry.macAddr);
            return;
        }
        // Generate an ARP if there isn't already one in flight and someone
        // is waiting for the reply. Note that there is one case when we
        // generate the ARP even if there's one in flight: when the previous
        // ARP was generated by a stale entry that was subsequently expired.
        Map<Integer, List<Callback<MAC>>> cbLists =
                        arpCallbackLists.get(portId);
        if (null == cbLists) {
            // This should never happen.
            log.error("getMacForIp: {} getMacForIp found null arpCallbacks map "
                    + "for port {} but arpCache was not null", this,
                    portId.toString());
            cb.call(null);
        }
        List<Callback<MAC>> cbList = cbLists.get(nwAddr);
        if (null == cbList) {
            cbList = new ArrayList<Callback<MAC>>();
            cbLists.put(nwAddr, cbList);
            log.debug("getMacForIp: {} getMacForIp generating ARP request for {}", this,
                    nwAddrStr);
            generateArpRequest(nwAddr, portId);
            try {
                arpTable.put(intNwAddr, 
                    new ArpCacheEntry(null, now + ARP_TIMEOUT_MILLIS,
                                      now + ARP_RETRY_MILLIS, now));
            } catch (KeeperException e) {
                log.error("KeeperException adding ARP table entry", e);
            } catch (InterruptedException e) {
                log.error("InterruptedException adding ARP table entry", e);
            }
            // Schedule ARP retry and expiration.
            reactor.schedule(new ArpRetry(nwAddr, portId), ARP_RETRY_MILLIS,
                    TimeUnit.MILLISECONDS);
            reactor.schedule(new ArpExpiration(nwAddr, portId),
                    ARP_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
        }
        cbList.add(cb);
    }

    public void process(ForwardInfo fwdInfo) {
        log.debug("{} process fwdInfo {}", this, fwdInfo);

        // Handle ARP first.
        if (fwdInfo.matchIn.getDataLayerType() == ARP.ETHERTYPE) {
            processArp(fwdInfo.pktIn, fwdInfo.inPortId);
            fwdInfo.action = Action.CONSUMED;
            return;
        }
        if (fwdInfo.matchIn.getDataLayerType() != IPv4.ETHERTYPE) {
            fwdInfo.action = Action.NOT_IPV4;
            return;
        }
        // Check if it's addressed to the port itself.
        L3DevicePort devPort = devicePorts.get(fwdInfo.inPortId);
        if (null != devPort && devPort.getVirtualConfig().portAddr ==
                fwdInfo.matchIn.getNetworkDestination()) {
            log.debug("process: received pkt addressed to the ingress port.");
            // Handle ICMP only for now.
            if (fwdInfo.matchIn.getNetworkProtocol() == ICMP.PROTOCOL_NUMBER)
                processICMPtoLocal(fwdInfo.pktIn, devPort);
            // Packets addressed to the port should not be routed. Consume them.
            fwdInfo.action = Action.CONSUMED;
            return;
        }

        log.debug(
                "{} apply pre-routing rules on pkt from port {} from {} to "
                        + "{}",
                new Object[] {
                        this,
                        null == fwdInfo.inPortId ? "ICMP" : fwdInfo.inPortId
                                .toString(),
                        IPv4.fromIPv4Address(fwdInfo.matchIn.getNetworkSource()),
                        IPv4.fromIPv4Address(fwdInfo.matchIn
                                .getNetworkDestination()) });
        // Apply pre-routing rules. Clone the original match in order to avoid
        // changing it.
        RuleResult res = ruleEngine.applyChain(PRE_ROUTING, fwdInfo.flowMatch,
                fwdInfo.matchIn, fwdInfo.inPortId, null);
        if (res.action.equals(RuleResult.Action.DROP)) {
            fwdInfo.action = Action.BLACKHOLE;
            return;
        }
        if (res.action.equals(RuleResult.Action.REJECT)) {
            fwdInfo.action = Action.REJECT;
            return;
        }
        if (!res.action.equals(RuleResult.Action.ACCEPT))
            throw new RuntimeException("Pre-routing returned an action other "
                    + "than ACCEPT, DROP or REJECT.");
        fwdInfo.trackConnection = res.trackConnection;

        log.debug("{} send pkt to routing table.", this);
        // Do a routing table lookup.
        Route rt = loadBalancer.lookup(res.match);
        if (null == rt) {
            fwdInfo.action = Action.NO_ROUTE;
            return;
        }
        if (rt.nextHop.equals(Route.NextHop.BLACKHOLE)) {
            fwdInfo.action = Action.BLACKHOLE;
            return;
        }
        if (rt.nextHop.equals(Route.NextHop.REJECT)) {
            fwdInfo.action = Action.REJECT;
            return;
        }
        if (!rt.nextHop.equals(Route.NextHop.PORT))
            throw new RuntimeException("Routing table returned next hop "
                    + "that isn't one of BLACKHOLE, NO_ROUTE, PORT or REJECT.");
        if (null == rt.nextHopPort) {
            log.error(
                    "{} route indicates forward to port, but no portId given",
                    this);
            // TODO(pino): should we remove this route?
            // For now just drop packets that match this route.
            fwdInfo.action = Action.BLACKHOLE;
            return;
        }

        log.debug("{} pkt next hop {} and egress port {} - applying "
                + "post-routing.", new Object[] { this,
                IPv4.fromIPv4Address(rt.nextHopGateway),
                rt.nextHopPort.toString() });
        // Apply post-routing rules.
        res = ruleEngine.applyChain(POST_ROUTING, fwdInfo.flowMatch, res.match,
                fwdInfo.inPortId, rt.nextHopPort);
        if (res.action.equals(RuleResult.Action.DROP)) {
            fwdInfo.action = Action.BLACKHOLE;
            return;
        }
        if (res.action.equals(RuleResult.Action.REJECT)) {
            fwdInfo.action = Action.REJECT;
            return;
        }
        if (!res.action.equals(RuleResult.Action.ACCEPT))
            throw new RuntimeException("Post-routing returned an action other "
                    + "than ACCEPT, DROP or REJECT.");
        if (!fwdInfo.trackConnection && res.trackConnection)
            fwdInfo.trackConnection = true;

        fwdInfo.outPortId = rt.nextHopPort;
        fwdInfo.matchOut = res.match;
        fwdInfo.nextHopNwAddr = (Route.NO_GATEWAY == rt.nextHopGateway) ? res.match
                .getNetworkDestination() : rt.nextHopGateway;
        fwdInfo.action = Action.FORWARD;
        return;
    }

    private void processICMPtoLocal(Ethernet ethPkt, L3DevicePort port) {
        IPv4 ipPkt = (IPv4)ethPkt.getPayload();
        if (ipPkt.getProtocol() != ICMP.PROTOCOL_NUMBER) {
            log.debug("processICMPtoLocal drop pkt with nwProto != ICMP");
            return;
        }
        ICMP icmpPkt = (ICMP)ipPkt.getPayload();
        // Only handle ICMP echo requests: type 8, code 0
        if (icmpPkt.getType() != ICMP.TYPE_ECHO_REQUEST ||
                icmpPkt.getCode() != ICMP.CODE_NONE) {
            log.debug("processICMPtoLocal drop pkt; only handle echo request.");
            return;
        }
        // Generate the echo reply.
        ICMP icmpReply = new ICMP();
        icmpReply.setEchoReply(icmpPkt.getIdentifier(),
                icmpPkt.getSequenceNum(), icmpPkt.getData());
        IPv4 ipReply = new IPv4();
        ipReply.setPayload(icmpReply);
        ipReply.setProtocol(ICMP.PROTOCOL_NUMBER);
        // TODO(pino): should we verify that this IP address would be
        // routed to this port? And drop the packet if that's not the case?
        ipReply.setDestinationAddress(ipPkt.getSourceAddress());
        ipReply.setSourceAddress(port.getVirtualConfig().portAddr);
        Ethernet ethReply = new Ethernet();
        ethReply.setPayload(ipReply);
        ethReply.setDestinationMACAddress(ethPkt.getSourceMACAddress());
        ethReply.setSourceMACAddress(port.getMacAddr());
        ethReply.setEtherType(IPv4.ETHERTYPE);
        log.debug("processICMPtoLocal sending echo reply from {} to {}",
                IPv4.fromIPv4Address(port.getVirtualConfig().portAddr),
                IPv4.fromIPv4Address(ipPkt.getSourceAddress()));
        port.send(ethReply.serialize());
    }

    private void processArp(Ethernet etherPkt, UUID inPortId) {
        log.debug("processArp: etherPkt {} from port {}", etherPkt, inPortId);

        if (!(etherPkt.getPayload() instanceof ARP)) {
            log.warn("{} ignoring packet with ARP ethertype but non-ARP "
                    + "payload.", this);
            return;
        }
        ARP arpPkt = (ARP) etherPkt.getPayload();
        // Discard the arp if its protocol type is not IP.
        if (arpPkt.getProtocolType() != ARP.PROTO_TYPE_IP) {
            log.warn("{} ignoring an ARP packet with protocol type {}",
                    this, arpPkt.getProtocolType());
            return;
        }
        L3DevicePort devPort = devicePorts.get(inPortId);
        if (null == devPort) {
            log.warn("{} ignoring an ARP on {} - port is not local.", this,
                    inPortId.toString());
            return;
        }
        if (arpPkt.getOpCode() == ARP.OP_REQUEST) {
            // ARP requests should broadcast or multicast. Ignore otherwise.
            // TODO(pino): ok to accept if it's addressed to us?
            if (etherPkt.isMcast()
                    || devPort.getMacAddr().equals(
                            etherPkt.getDestinationMACAddress()))
                processArpRequest(arpPkt, devPort);
            else
                log.warn("{} ignoring an ARP with a non-bcast/mcast dlDst {}",
                        this, etherPkt.getDestinationMACAddress());
        } else if (arpPkt.getOpCode() == ARP.OP_REPLY)
            processArpReply(arpPkt, devPort);

        // We ignore any other ARP packets: they may be malicious, bogus,
        // gratuitous ARP announcement requests, etc.
        // TODO(pino): above comment ported from Python, but I don't get it
        // since there are only two possible ARP operations. Was this meant to
        // go somewhere else?
    }

    private static boolean spoofL2Network(int hostNwAddr, int nwAddr,
            int nwLength, int localNwAddr, int localNwLength) {
        // Return true (spoof arp replies for hostNwAddr) if hostNwAddr is in
        // the nwAddr, but not in the localNwAddr.
        int shift = 32 - nwLength;
        boolean inNw = true;
        if (32 > shift)
            inNw = (hostNwAddr >>> shift) == (nwAddr >>> shift);
        if (!inNw)
            return false;
        shift = 32 - localNwLength;
        inNw = true;
        if (32 > shift)
            inNw = (hostNwAddr >>> shift) == (localNwAddr >>> shift);
        return !inNw;
    }

    private void processArpRequest(ARP arpPkt, L3DevicePort devPortIn) {
        log.debug("processArpRequest: arpPkt {} from port {}", arpPkt, devPortIn);

        // If the request is for the ingress port's own address, it's for us.
        // Respond with the port's Mac address.
        // If the request is for an IP address which we emulate
        // switching for, we spoof the target host and respond to the ARP
        // with our own MAC address. These addresses are those in nw_prefix
        // but not local_nw_prefix for the in_port. (local_nw_prefix addresses
        // are assumed to be already handled by a switch.)

        // First get the ingress port's mac address
        PortDirectory.MaterializedRouterPortConfig portConfig = devPortIn
                .getVirtualConfig();
        int tpa = IPv4.toIPv4Address(arpPkt.getTargetProtocolAddress());
        if (tpa != devPortIn.getVirtualConfig().portAddr
                && !spoofL2Network(tpa, portConfig.nwAddr, portConfig.nwLength,
                        portConfig.localNwAddr, portConfig.localNwLength)) {
            // It's an ARP for someone else. Ignore it.
            return;
        }
        // TODO(pino): If this ARP is for the port's own address don't reply
        // unless we've already confirmed no-one else is using this IP.
        ARP arp = new ARP();
        arp.setHardwareType(ARP.HW_TYPE_ETHERNET);
        arp.setProtocolType(ARP.PROTO_TYPE_IP);
        arp.setHardwareAddressLength((byte) 6);
        arp.setProtocolAddressLength((byte) 4);
        arp.setOpCode(ARP.OP_REPLY);
        MAC portMac = devPortIn.getMacAddr();
        arp.setSenderHardwareAddress(portMac);
        arp.setSenderProtocolAddress(arpPkt.getTargetProtocolAddress());
        arp.setTargetHardwareAddress(arpPkt.getSenderHardwareAddress());
        arp.setTargetProtocolAddress(arpPkt.getSenderProtocolAddress());
        int spa = IPv4.toIPv4Address(arpPkt.getSenderProtocolAddress());

        log.debug("{} replying to ARP request from {} for {} with own mac {}",
                new Object[] { this, IPv4.fromIPv4Address(spa),
                        IPv4.fromIPv4Address(tpa),
                        portMac});

        // TODO(pino) logging.
        Ethernet pkt = new Ethernet();
        pkt.setPayload(arp);
        pkt.setSourceMACAddress(portMac);
        pkt.setDestinationMACAddress(arpPkt.getSenderHardwareAddress());
        pkt.setEtherType(ARP.ETHERTYPE);

        // Now send it from the port.
        devPortIn.send(pkt.serialize());
    }

    private void processArpReply(ARP arpPkt, L3DevicePort devPortIn) {
        log.debug("processArpReply: arpPkt {} from port {}", arpPkt, devPortIn);

        // Verify that the reply was meant for us: tpa is the port's nw addr,
        // and tha is the port's mac.
        // TODO(pino): only a suggestion in the Python, I implemented it. OK?
        UUID inPortId = devPortIn.getId();
        PortDirectory.MaterializedRouterPortConfig portConfig = devPortIn
                .getVirtualConfig();
        int tpa = IPv4.toIPv4Address(arpPkt.getTargetProtocolAddress());
        MAC tha = arpPkt.getTargetHardwareAddress();
        if (tpa != portConfig.portAddr
                || !tha.equals(devPortIn.getMacAddr())) {
            log.debug("{} ignoring ARP reply because its tpa or tha don't "
                    + "match ip addr or mac of port {}", this, inPortId
                    .toString());
            return;
        }
        // Question: Should we make noise if an ARP reply disagrees with the
        // existing arp_cache entry?

        MAC sha = arpPkt.getSenderHardwareAddress();
        int spa = IPv4.toIPv4Address(arpPkt.getSenderProtocolAddress());
        log.debug("{} received an ARP reply with spa {} and sha {}",
                new Object[] { this, IPv4.fromIPv4Address(spa), sha });
        long now = reactor.currentTimeMillis();
        ArpCacheEntry entry = new ArpCacheEntry(sha, now
                + ARP_EXPIRATION_MILLIS, now + ARP_STALE_MILLIS, 0);
        log.debug("Putting in ARP cache entry for {} on port {}", spa, inPortId);
        try {
            arpTable.put(new IntIPv4(spa), entry);
        } catch (KeeperException e) {
            log.error("KeeperException storing ARP table entry", e);
        } catch (InterruptedException e) {
            log.error("InterruptedException storing ARP table entry", e);
        }
        reactor.schedule(new ArpExpiration(spa, inPortId),
                ARP_EXPIRATION_MILLIS, TimeUnit.MILLISECONDS);
        Map<Integer, List<Callback<MAC>>> cbLists = arpCallbackLists
                .get(inPortId);
        if (null == cbLists) {
            log.debug("processArpReply: cbLists is null");
            return;
        }

        List<Callback<MAC>> cbList = cbLists.remove(spa);
        if (null != cbList)
            for (Callback<MAC> cb : cbList)
                cb.call(sha);
    }

    private class ArpExpiration implements Runnable {

        int nwAddr;
        UUID portId;

        ArpExpiration(int nwAddr, UUID inPortId) {
            this.nwAddr = nwAddr;
            this.portId = inPortId;
        }

        @Override
        public void run() {
            IntIPv4 intNwAddr = new IntIPv4(nwAddr);
            log.debug("ArpExpiration.run: ip {} port {}", intNwAddr, portId);

            ArpCacheEntry entry = arpTable.get(intNwAddr);
            if (null == entry) {
                // The entry has already been removed.
                log.debug("{} ARP expiration triggered for {} but cache "
                        + "entry has already been removed", this, intNwAddr);
                return;
            }
            if (entry.expiry <= reactor.currentTimeMillis()) {
                log.debug("{} expiring ARP cache entry for {}", this,
                        intNwAddr);
                try {
                    arpTable.remove(intNwAddr);
                } catch (KeeperException e) {
                    log.error("KeeperException while removing ARP table entry", e);
                } catch (InterruptedException e) {
                    log.error("InterruptedException while removing ARP table entry", e);
                }
                Map<Integer, List<Callback<MAC>>> cbLists =
                                arpCallbackLists.get(portId);
                if (null != cbLists) {
                    List<Callback<MAC>> cbList = cbLists.remove(nwAddr);
                    if (null != cbList) {
                        for (Callback<MAC> cb : cbList)
                            cb.call(null);
                    }
                }
            }
            // Else the expiration was rescheduled by an ARP reply arriving
            // while the entry was stale. Do nothing.
        }
    }

    private class ArpRetry implements Runnable {
        int nwAddr;
        UUID portId;
        String nwAddrStr;

        ArpRetry(int nwAddr, UUID inPortId) {
            this.nwAddr = nwAddr;
            nwAddrStr = IPv4.fromIPv4Address(nwAddr);
            this.portId = inPortId;
        }

        @Override
        public void run() {
            log.debug("ArpRetry.run: ip {} port {}", Net.convertIntAddressToString(nwAddr), portId);

            ArpCacheEntry entry = arpTable.get(new IntIPv4(nwAddr));
            if (null == entry) {
                // The entry has already been removed.
                log.debug("{} ARP retry triggered for {} but cache "
                        + "entry has already been removed", this, nwAddrStr);
                return;
            }
            if (null != entry.macAddr)
                // An answer arrived.
                return;
            // Re-ARP and schedule again.
            log.debug("{} retry ARP request for {} on port {}", new Object[] {
                    this, nwAddrStr, portId.toString() });
            generateArpRequest(nwAddr, portId);
            reactor.schedule(this, ARP_RETRY_MILLIS, TimeUnit.MILLISECONDS);
        }
    }

    private void generateArpRequest(int nwAddr, UUID portId) {
        log.debug("generateArpRequest: ip {} port {}",
                  Net.convertIntAddressToString(nwAddr), portId);

        L3DevicePort devPort = devicePorts.get(portId);
        if (null == devPort) {
            log.warn("{} generateArpRequest could not find device port for "
                    + "{} - was port removed?", this, portId.toString());
            return;
        }
        PortDirectory.MaterializedRouterPortConfig portConfig = devPort
                .getVirtualConfig();

        ARP arp = new ARP();
        arp.setHardwareType(ARP.HW_TYPE_ETHERNET);
        arp.setProtocolType(ARP.PROTO_TYPE_IP);
        arp.setHardwareAddressLength((byte) 6);
        arp.setProtocolAddressLength((byte) 4);
        arp.setOpCode(ARP.OP_REQUEST);
        MAC portMac = devPort.getMacAddr();
        arp.setSenderHardwareAddress(portMac);
        arp.setTargetHardwareAddress(MAC.fromString("00:00:00:00:00:00"));
        arp.setSenderProtocolAddress(IPv4
                .toIPv4AddressBytes(portConfig.portAddr));
        arp.setTargetProtocolAddress(IPv4.toIPv4AddressBytes(nwAddr));
        Ethernet pkt = new Ethernet();
        pkt.setPayload(arp);
        pkt.setSourceMACAddress(portMac);
        pkt.setDestinationMACAddress(MAC.fromString("ff:ff:ff:ff:ff:ff"));
        pkt.setEtherType(ARP.ETHERTYPE);

        // Now send it from the port.
        log.debug("generateArpRequest: sending {}", pkt);
        devPort.send(pkt.serialize());
        try {
            ArpCacheEntry entry = arpTable.get(new IntIPv4(nwAddr));
            long now = reactor.currentTimeMillis();
            if (null != entry) {
                // Modifying an uncloned entry confuses Directory.
                entry = entry.clone();
                entry.lastArp = now;
                arpTable.put(new IntIPv4(nwAddr), entry);
            }
        } catch (KeeperException e) {
            log.error("Got KeeperException trying to update lastArp", e);
        } catch (InterruptedException e) {
            log.error("Got InterruptedException trying to update lastArp", e);
        }
    }

    public void freeFlowResources(OFMatch match) {
        log.debug("freeFlowResources: match {}", match);

        ruleEngine.freeFlowResources(match);
    }

    // // OpenMBean stuff for JMX
    // // TODO: Replace this crap with something sane, like Thrift.
    // public Object getAttribute(String attribute)
    //         throws AttributeNotFoundException, MBeanException {
    //     if (attribute.equals("PortSet")) {
    //        try {
    //            return getPortTable();
    //        } catch (OpenDataException e) {
    //            throw new MBeanException(e);
    //        }
    //     }
    //     throw new AttributeNotFoundException("Cannot find "
    //            + attribute + " attribute");
    // }
    // public void setAttribute(Attribute attribute)
    //         throws AttributeNotFoundException {
    //     throw new AttributeNotFoundException("Cannot set " + attribute.getName());
    // }
    // public AttributeList getAttributes(String[] attributeNames) {
    //     AttributeList resultList = new AttributeList();
    //     if (attributeNames.length == 0)
    //         return resultList;
    //     try {
    //         for (int i = 0; i < attributeNames.length; i++) {
    //             Object value = getAttribute(attributeNames[i]);
    //             resultList.add(new Attribute(attributeNames[i], value));
    //         }
    //         return resultList;
    //     } catch (RuntimeException e) {
    //         throw e;
    //     } catch (Exception e) {
    //         throw new RuntimeException(e);
    //     }
    // }
    // public AttributeList setAttributes(AttributeList attributes) {
    //     if (attributes.size() == 0) {
    //         return new AttributeList();
    //     }
    //     // That's right folks, AttributeList.get() returns Object.
    //     Attribute attr = (Attribute) attributes.get(0);
    //     throw new RuntimeException("Cannot set " + attr.getName());
    // }
    // public Object invoke(String actionName, Object params[], String signature[])
    //         throws MBeanException, ReflectionException {
    //     if (actionName.equals("getArpCacheEntry")) {
    //         if (signature.length != 2 ||
    //             !signature[0].equals("java.lang.String") ||
    //             !signature[1].equals("int")) {
    //             throw new ReflectionException(new IllegalArgumentException(
    //                     "Invalid signature for " + actionName));
    //         }
    //         if (params.length != 2) {
    //             throw new ReflectionException(new IllegalArgumentException(
    //                     "Invalid parameter list for " + actionName));
    //         }
    //         UUID portUuid = UUID.fromString((String)params[0]);
    //         int ipAddr = ((Integer)params[1]).intValue();
    //         return getArpCacheEntry(portUuid, ipAddr);
    //     } else if (actionName.equals("getArpCacheKeys")) {
    //         if (signature.length != 1 ||
    //             !signature[0].equals("java.lang.String")) {
    //             throw new ReflectionException(new IllegalArgumentException(
    //                     "Invalid signature for " + actionName));
    //         }
    //         if (params.length != 1) {
    //             throw new ReflectionException(new IllegalArgumentException(
    //                     "Invalid parameter list for " + actionName));
    //         }
    //         UUID portUuid = UUID.fromString((String)params[0]);
    //         try {
    //             return getArpCacheKeyTable(portUuid);
    //         } catch (OpenDataException e) {
    //             throw new MBeanException(e);
    //         }
    //     } else if (actionName.equals("getArpCacheTable")) {
    //         if (signature.length != 1 ||
    //             !signature[0].equals("java.lang.String")) {
    //             throw new ReflectionException(new IllegalArgumentException(
    //                     "Invalid signature for " + actionName));
    //         }
    //         if (params.length != 1) {
    //             throw new ReflectionException(new IllegalArgumentException(
    //                     "Invalid parameter list for " + actionName));
    //         }
    //         UUID portUuid = UUID.fromString((String)params[0]);
    //         try {
    //             return getArpCacheTable(portUuid);
    //         } catch (OpenDataException e) {
    //             throw new MBeanException(e);
    //         }
    //     } else {
    //         throw new ReflectionException(new NoSuchMethodException(actionName));
    //     }
    // }
    public MBeanInfo getMBeanInfo() {
        OpenMBeanAttributeInfoSupport[] attributes =
                new OpenMBeanAttributeInfoSupport[1];
        OpenMBeanConstructorInfoSupport[] constructors =
                new OpenMBeanConstructorInfoSupport[0];
        OpenMBeanOperationInfoSupport[] operations =
                new OpenMBeanOperationInfoSupport[3];
        MBeanNotificationInfo[] notifications = new MBeanNotificationInfo[0];
        OpenMBeanParameterInfoSupport[] ackSignature =
                new OpenMBeanParameterInfoSupport[1];
        OpenMBeanParameterInfoSupport[] aceSignature =
                new OpenMBeanParameterInfoSupport[2];

        ackSignature[0] = new OpenMBeanParameterInfoSupport("portUuid",
                                "Port UUID", SimpleType.STRING);
        aceSignature[0] = new OpenMBeanParameterInfoSupport("portUuid",
                                "Port UUID", SimpleType.STRING);
        aceSignature[1] = new OpenMBeanParameterInfoSupport("ipAddr",
                                "IP number", SimpleType.INTEGER);

        attributes[0] = new OpenMBeanAttributeInfoSupport("PortSet",
                "list of ports", portSetType, true, false, false);
        operations[0] = new OpenMBeanOperationInfoSupport("getArpCacheKeys",
                "keys of the ARP cache", ackSignature, intListType,
                OpenMBeanOperationInfoSupport.INFO);
        operations[1] = new OpenMBeanOperationInfoSupport("getArpCacheTable",
                "the ARP cache (as a table)", ackSignature, intStrListType,
                OpenMBeanOperationInfoSupport.INFO);
        operations[2] = new OpenMBeanOperationInfoSupport("getArpCacheEntry",
                "an entry in the ARP cache", aceSignature, SimpleType.STRING,
                OpenMBeanOperationInfoSupport.INFO);

        return new OpenMBeanInfoSupport(this.getClass().getName(),
                "Router - Open - MBean", attributes, constructors,
                operations, notifications);
    }
    CompositeType stringType = new CompositeType("string", "String",
        new String[] { "string" }, new String[] { "String" },
        new SimpleType[] { SimpleType.STRING });
    TabularType portSetType = new TabularType("portSet", "List of ports",
                                              stringType,
                                              new String[] { "string" });
    CompositeType intType = new CompositeType("int", "Integer",
        new String[] { "int" }, new String[] { "Integer" },
        new SimpleType[] { SimpleType.INTEGER });
    TabularType intListType = new TabularType("intList", "List of integers",
                                              intType,
                                              new String[] { "int" });
    CompositeType intStrType = new CompositeType("int-string",
        "Integer+String tuple",
        new String[] { "int", "string" }, new String[] { "Integer", "String" },
        new SimpleType[] { SimpleType.INTEGER, SimpleType.STRING });
    TabularType intStrListType = new TabularType("intStrList",
                                              "Integer to String Map",
                                              intStrType,
                                              new String[] { "int", "string" });

    // public String getArpCacheEntry(UUID portUuid, int ipAddress) {
    //     log.debug("JMX asking for {} on port {}", ipAddress, portUuid);
    //     ArpCacheEntry entry = arpTable.get(new IntIPv4(ipAddress));
    //     return entry == null ? null : entry.toString();
    // }

    // public TabularData getArpCacheKeyTable(UUID portUuid)
    //         throws OpenDataException {
    //     TabularDataSupport table = new TabularDataSupport(intListType);
    //     for (IntIPv4 i : arpTable.keySet()) {
    //         CompositeDataSupport entry =
    //             new CompositeDataSupport(intType,
    //                                      new String[] { "int" },
    //                                      new Object[] { i.address });
    //         table.put(entry);
    //     }
    //     return table;
    // }

    // public TabularData getArpCacheTable(UUID portUuid)
    //         throws OpenDataException {
    //     TabularDataSupport table = new TabularDataSupport(intStrListType);
    //     for (Map.Entry<IntIPv4, ArpCacheEntry> entry : arpTable.entrySet()) {
    //         String arpEntry = entry.getValue().toString();
    //         CompositeDataSupport row =
    //             new CompositeDataSupport(intStrType,
    //                                      new String[] { "int", "string" },
    //                                      new Object[] { entry.getKey().address,
    //                                                     arpEntry });
    //         table.put(row);
    //     }
    //     return table;
    // }
}

/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.odp.flows;

import com.midokura.netlink.NetlinkMessage;
import com.midokura.netlink.messages.BaseBuilder;

public class FlowKeyICMP implements FlowKey<FlowKeyICMP> {
    /*__u8*/ byte icmp_type;
    /*__u8*/ byte icmp_code;

    @Override
    public void serialize(BaseBuilder builder) {
        builder.addValue(icmp_type);
        builder.addValue(icmp_code);
    }

    @Override
    public boolean deserialize(NetlinkMessage message) {
        try {
            icmp_type = message.getByte();
            icmp_code = message.getByte();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public NetlinkMessage.AttrKey<FlowKeyICMP> getKey() {
        return FlowKeyAttr.ICMP;
    }

    @Override
    public FlowKeyICMP getValue() {
        return this;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        FlowKeyICMP that = (FlowKeyICMP) o;

        if (icmp_code != that.icmp_code) return false;
        if (icmp_type != that.icmp_type) return false;

        return true;
    }

    @Override
    public int hashCode() {
        int result = (int) icmp_type;
        result = 31 * result + (int) icmp_code;
        return result;
    }

    @Override
    public String toString() {
        return String.format("FlowKeyICMP{icmp_type=0x%X, icmp_code=%d}",
                             icmp_type, icmp_code);
    }

    public byte getType() {
        return icmp_type;
    }

    public FlowKeyICMP setType(byte type) {
        this.icmp_type = type;
        return this;
    }

    public byte getCode() {
        return icmp_code;
    }

    public FlowKeyICMP setCode(byte code) {
        this.icmp_code = code;
        return this;
    }
}
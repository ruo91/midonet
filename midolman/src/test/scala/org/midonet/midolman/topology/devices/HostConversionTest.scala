/*
 * Copyright 2014 Midokura SARL
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.midonet.midolman.topology.devices

import java.util.UUID

import scala.collection.JavaConversions._

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.scalatest.{FeatureSpec, Matchers}

import org.midonet.cluster.data.ZoomConvert
import org.midonet.cluster.models.Topology
import org.midonet.cluster.models.Topology.Host.PortBinding
import org.midonet.cluster.util.UUIDUtil._

@RunWith(classOf[JUnitRunner])
class HostConversionTest extends FeatureSpec with Matchers {

    feature("Conversion for host") {
        scenario("Test conversion from Protocol Buffer message") {
            val proto = newProto
            val zoomObj = ZoomConvert.fromProto(proto, classOf[Host])

            assertEquals(proto, zoomObj)
        }

        scenario("Test conversion to Protocol Buffer message") {
            val zoomObj = newZoomObj
            val proto = ZoomConvert.toProto(zoomObj, classOf[Topology.Host])

            assertEquals(proto, zoomObj)
        }
    }

    private def assertEquals(proto: Topology.Host, zoomObj: Host) = {
        proto.getId.asJava shouldBe zoomObj.id
        proto.getPortBindingsCount shouldBe zoomObj.portBindings.size
        for (binding <- proto.getPortBindingsList) {
            val protoInterface = binding.getInterfaceName
            val zoomInterface = zoomObj.portBindings.get(binding.getPortId)
            zoomInterface should be(Some(protoInterface))
        }
        proto.getTunnelZoneIdsCount shouldBe zoomObj.tunnelZoneIds.toParArray.length
        for (tunnelId <- proto.getTunnelZoneIdsList) {
            zoomObj.tunnelZoneIds should contain(tunnelId.asJava)
        }

        // The tunnelZones field of the zoomObj should be an empty map since no
        // such field exists in the proto.
        zoomObj.tunnelZones shouldBe Map.empty
    }

    private def newPortBinding(interface: String): PortBinding = {
        PortBinding.newBuilder
            .setPortId(UUID.randomUUID().asProto)
            .setInterfaceName(interface)
            .build()
    }

    private def newZoomObj = {
        val zoomObj = new Host
        zoomObj.id = UUID.randomUUID()
        zoomObj.portBindings = Map(UUID.randomUUID() -> "eth0",
                                   UUID.randomUUID() -> "eth1")
        zoomObj.tunnelZoneIds = Set(UUID.randomUUID(),
                                    UUID.randomUUID())
        zoomObj
    }

    private def newProto = {
        Topology.Host.newBuilder
            .setId(UUID.randomUUID().asProto)
            .addPortBindings(newPortBinding("foo"))
            .addPortBindings(newPortBinding("bar"))
            .addTunnelZoneIds(UUID.randomUUID().asProto)
            .addTunnelZoneIds(UUID.randomUUID().asProto)
            .build()
    }
}

/*
 * Copyright 2015 Midokura SARL
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

package org.midonet.brain.services.c3po.translators

import org.midonet.cluster.models.Commons.UUID
import org.midonet.cluster.models.Topology.Chain
import org.midonet.cluster.util.UUIDUtil.asRichProtoUuid

/**
 * Contains chain-related operations shared by multiple translators.
 */
trait ChainManager {
    protected case class ChainIds(inChainId: UUID, outChainId: UUID)

    /** Deterministically generate inbound chain ID from device ID. */
    protected def inChainId(deviceId: UUID) =
        deviceId.xorWith(0xc84db1f73554442bL, 0x8e7b38f2c703ee6aL)

    /** Deterministically generate outbound chain ID from device ID. */
    protected def outChainId(deviceId: UUID) =
        deviceId.xorWith(0x20940fb2cac401eL, 0x9ffaf9c05d04b524L)

    protected def newChain(id: UUID, name: String,
                           ruleIds: Seq[UUID] = Seq()): Chain = {
        val bldr = Chain.newBuilder.setId(id).setName(name)
        ruleIds.foreach(bldr.addRuleIds)
        bldr.build()
    }
}

package com.example.apkautomation.wifi

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap

data class MeshNodeInfo(
    val nodeId: Int,
    val nodeName: String,
    val lastSeenMs: Long,
    val hopDistance: Int,
    val address: InetAddress? = null
)

/**
 * Tactical Multi-Hop Mesh Router
 * Manages autonomous packet de-duplication, TTL decrementing, and multi-hop forwarding
 * across daisy-chained Wi-Fi Direct devices without any central server or cellular network.
 */
class WifiMeshRouter(
    val localNodeId: Int,
    val localNodeName: String
) {
    companion object {
        const val MAX_HOPS: Byte = 4
        private const val CACHE_EXPIRATION_MS = 12_000L
    }

    private var localSequence = 0

    // De-duplication cache: Key = "originId:sequenceNumber", Value = timestamp received
    private val seenPackets = ConcurrentHashMap<String, Long>()

    // Known mesh nodes in the active daisy chain
    private val _meshNodes = MutableStateFlow<Map<Int, MeshNodeInfo>>(emptyMap())
    val meshNodes = _meshNodes.asStateFlow()

    fun nextSequenceNumber(): Int {
        localSequence++
        return localSequence
    }

    /**
     * Checks if this packet has already been routed/received recently.
     * Returns true if packet is NEW (should be processed), false if DUPLICATE (drop).
     */
    fun checkAndRecordPacket(originSenderId: Int, sequenceNumber: Int): Boolean {
        cleanupExpiredCache()
        val key = "$originSenderId:$sequenceNumber"
        val existing = seenPackets.putIfAbsent(key, System.currentTimeMillis())
        return existing == null
    }

    /**
     * Records or updates a node in the mesh topology table.
     */
    fun registerNode(nodeId: Int, name: String, hopDistance: Int, addr: InetAddress?) {
        if (nodeId == localNodeId) return
        val current = _meshNodes.value.toMutableMap()
        current[nodeId] = MeshNodeInfo(
            nodeId = nodeId,
            nodeName = name,
            lastSeenMs = System.currentTimeMillis(),
            hopDistance = hopDistance,
            address = addr
        )
        _meshNodes.value = current
    }

    /**
     * Prunes nodes that haven't sent a heartbeat in over 15 seconds.
     */
    fun pruneStaleNodes() {
        val now = System.currentTimeMillis()
        val current = _meshNodes.value.toMutableMap()
        val iterator = current.entries.iterator()
        var changed = false
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (now - entry.value.lastSeenMs > 15_000L) {
                iterator.remove()
                changed = true
            }
        }
        if (changed) {
            _meshNodes.value = current
        }
    }

    private fun cleanupExpiredCache() {
        val now = System.currentTimeMillis()
        if (seenPackets.size > 200) {
            val iterator = seenPackets.entries.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                if (now - entry.value > CACHE_EXPIRATION_MS) {
                    iterator.remove()
                }
            }
        }
    }

    fun clear() {
        seenPackets.clear()
        _meshNodes.value = emptyMap()
    }
}

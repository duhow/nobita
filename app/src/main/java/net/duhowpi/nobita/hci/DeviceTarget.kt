package net.duhowpi.nobita.hci

import java.util.UUID

data class DeviceTarget(
    val addresses: Set<String> = emptySet(),
    val name: String? = null,
    val serviceUuids: Set<UUID> = emptySet(),
    val manufacturerId: Int? = null,
    val selectedAt: Long = System.currentTimeMillis(),
) {
    fun matches(connection: Connection?): Boolean {
        if (connection == null) return false
        val address = normalizeAddress(connection.address)
        return addresses.any { address.startsWith(normalizeAddress(it)) } ||
            (!name.isNullOrBlank() && connection.name?.contains(name, ignoreCase = true) == true)
    }

    companion object {
        fun fromQuery(query: String): DeviceTarget {
            val compact = normalizeAddress(query)
            return if (compact.length in 2..12 && compact.all { it in "0123456789abcdefABCDEF" }) {
                DeviceTarget(addresses = setOf(compact))
            } else {
                DeviceTarget(name = query.trim())
            }
        }

        fun normalizeAddress(address: String) = address.filter { it.isLetterOrDigit() }.uppercase()
    }
}

package com.metrolist.music.pulse

object PulseReleasePolicy {
    fun validRepository(value: String): Boolean = Regex("[A-Za-z0-9][A-Za-z0-9-]{0,38}/[A-Za-z0-9_.-]{1,100}").matches(value)

    fun isNewer(candidate: String, current: String): Boolean {
        fun parts(value: String): List<Int>? {
            val match = Regex("^v?(\\d+)\\.(\\d+)\\.(\\d+)(?:\\+[^\\s]+)?$").matchEntire(value.trim()) ?: return null
            return match.groupValues.drop(1).map { it.toIntOrNull() ?: return null }
        }
        val a = parts(candidate) ?: return false
        val b = parts(current) ?: return false
        for (index in a.indices) if (a[index] != b[index]) return a[index] > b[index]
        return false
    }
}

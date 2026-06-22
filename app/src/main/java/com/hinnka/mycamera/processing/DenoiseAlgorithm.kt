package com.hinnka.mycamera.processing

import com.hinnka.mycamera.utils.DeviceUtil

enum class DenoiseAlgorithm(val persistedName: String) {
    Fast("FAST"),
    HighQuality("HIGH_QUALITY");

    companion object {
        val DEFAULT = if (DeviceUtil.canShowPhantom) HighQuality else Fast

        fun fromPersistedName(
            value: String?,
            fallback: DenoiseAlgorithm = DEFAULT
        ): DenoiseAlgorithm {
            if (value.isNullOrBlank()) return fallback
            return entries.firstOrNull {
                it.persistedName.equals(value, ignoreCase = true) ||
                    it.name.equals(value, ignoreCase = true)
            } ?: fallback
        }
    }
}

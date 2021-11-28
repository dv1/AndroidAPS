package info.nightscout.androidaps.plugins.pump.combov2

import info.nightscout.androidaps.interfaces.Profile as AAPSProfile
import info.nightscout.comboctl.main.NUM_BASAL_PROFILE_FACTORS

class BasalProfile() {
    private val _factors = MutableList(NUM_BASAL_PROFILE_FACTORS) { 0 }

    val factors: List<Int> = _factors

    constructor(otherFactors: List<Int>): this() {
        require(otherFactors.size == _factors.size)
        otherFactors.forEachIndexed { index, factor -> _factors[index] = factor }
    }

    constructor(aapsProfile: AAPSProfile): this() {
        for (index in _factors.indices) {
            val aapsRate = (aapsProfile.getBasalTimeFromMidnight(index * 60 * 60) * 1000.0).toInt()

            /* The Combo uses the following granularity:
             *   0.00 IU to 0.05 IU  : increment in 0.05 IU steps
             *   0.05 IU to 1.00 IU  : increment in 0.01 IU steps
             *   1.00 IU to 10.00 IU : increment in 0.05 IU steps
             */

            val granularity = when (aapsRate) {
                in 50..1000 -> 10
                else -> 50
            }

            // Round the AAPS factor with integer math
            // to conform to the Combo granularity.
            _factors[index] = ((aapsRate + granularity / 2) / granularity) * granularity
        }
    }

    fun getFactor(index: Int) = _factors[index]

    override fun toString() = factors.mapIndexed { index, factor ->
        "hour %d: factor %.3f".format(index, factor.cctlBasalToIU())
    }.joinToString("; ")

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as BasalProfile

        if (_factors != other._factors) return false

        return true
    }

    override fun hashCode(): Int {
        return _factors.hashCode()
    }
}
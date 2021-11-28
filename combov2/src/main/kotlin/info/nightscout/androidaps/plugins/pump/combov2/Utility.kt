package info.nightscout.androidaps.plugins.pump.combov2

import info.nightscout.comboctl.base.DateTime as ComboCtlDateTime
import org.joda.time.DateTimeZone

// Utility extension functions for clearer conversion between
// ComboCtl units and AAPS units. ComboCtl uses integer-encoded
// decimals. For basal values, the last 3 digits of an integer
// make up the decimal, so for example, 1568 actually means
// 1.568 IU, and 419 means 0.419 IU etc. Similarly, for bolus
// values, the last digit makes up the decimal. 57 means 5.7 IU,
// 4 means 0.4 IU etc.
// To emphasize better that such a conversion is taking place,
// these extension functions are put in place.
internal fun Int.cctlBasalToIU() = this.toDouble() / 1000.0
internal fun Int.cctlBolusToIU() = this.toDouble() / 10.0

internal fun ComboCtlDateTime.toJodaDateTime() =
    org.joda.time.DateTime(
        this.year,
        this.month,
        this.day,
        this.hour,
        this.minute,
        DateTimeZone.UTC
    )

internal fun org.joda.time.DateTime.toComboCtlDateTime() =
    ComboCtlDateTime(
        year = this.year,
        month = this.monthOfYear,
        day = this.dayOfMonth,
        hour = this.hourOfDay,
        minute = this.minuteOfHour,
        second = this.secondOfMinute
    )

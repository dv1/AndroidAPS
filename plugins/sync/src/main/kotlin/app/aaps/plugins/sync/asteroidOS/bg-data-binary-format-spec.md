# Prerequisites

This is version 1 of this message format spec.

All values are encoded in little-endian order.

The following values can be encoded:

* INT16 / INT64 : 16/64 bit signed integers, using 2-complement for negative values.
  32-bit ones are not currently being used.
* FLOAT32 : 32 bit IEEE 745 floating point.
* UINT8 : 8 bit unsigned integer.

Insulin quantities are stored as 16-bit fixed point (decimal fixed points). The quantity is
multiplied by 100. The result is rounded and stored as integer. Example: 6.255 U -> 626.
This is called "insulin units". The value 0xFFFF is a sentinel for "unknown quantity".

Glucose units are stored as 16-bit fixed point (decimal fixed point). The quantity is
multiplied by 100 if the unit is mmol/L, and by 10 if the unit is mg/dL. Example:
127.2 mg/dL -> 1272 ; 4.31 mmol/L -> 431 . The value 0xFFFF is a sentinel for "unknown quantity".
The difference in multiplication exists to make maximal use of the 16-bit fixed point
range. With mg/dL, more than one decimal digit is unnecessary - but it _is_ possible
to reach very high values like 500 mg/dL. With mmol/L, two decimal digits are common,
but quantities like 500 mmol/L are generally incompatible with life.


# Format

This section describes the message format structure by listing the included values, in order
of appearance in the dataset. The first byte contains the version number and glucose unit.
Then, blocks follow. Each block starts with a byte that contains the block ID in its 4 LSB.
The upper 4 MSB can be used for block specific flags or values (or are ignored if the block
does not need them).

## Header

UINT8  version number and glucose unit

The most significant bit (MSB) specifies the glucose unit. If it is set, the unit is mg/dL.
Otherwise, it is mmol/L. (That bit is never interpreted as a sign bit.) The 7 LSB are the
version number.

## Basal rate block

UINT8  block ID byte
* Bits 0..3 : Set to block ID 1

INT16  base basal rate, stored in insulin units
The "base basal rate" is the current basal rate without any active TBR factored in.

INT16  current basal rate, stored in insulin units
The "current basal rate" is the current basal rate _with_ any active TBR factored in.

## IOB / COB block

UINT8  block ID byte
* Bits 0..3 : Set to block ID 2

Unknown quantities are set to the sentinel value 0xFFFF.

INT16  basal IOB, stored in insulin units
INT16  bolus IOB, stored in insulin units
INT16  currently active carbs
INT16  carbs that will become active in the future

## Loop status block

UINT8  block ID byte
* Bits 0..3 : Set to block ID 3
* Bits 4..7 : Set to the loop state
  Valid values:
    * 0: UNKNOWN
    * 1: DISABLED
    * 2: DISCONNECTED
    * 3: PAUSED
    * 4: LGS
    * 5: CLOSED
    * 6: OPEN

INT64    last loop run timestamp
UTC timestamp in seconds from when the last time the closed-loop was run.
Set to 0 if the timestamp is not known.

## BG status block

UINT8  block ID byte
* Bits 0..3 : Set to block ID 4
* Bits 4..7 : Set to the trend arrow
  Valid values:
    * 0: no arrow
    * 1: ↑↑↑ triple up
    * 2: ↑↑ double up
    * 3: ↑ single up
    * 4: ↗ forty-five up
    * 5: → flat
    * 6: ↘ forty-five down
    * 7: ↓ single down
    * 8: ↓↓ double down
    * 9: ↓↓↓ triple down

INT16  current BG, in glucose units

INT16  BG delta, in glucose units

INT64    UTC timestamp in seconds from when this BG status was taken
Set to 0 if the timestamp is not known.

Next come time series data points. These encode the latest N BG values that are
used for drawing BG time series graphs. Both the BG values and the timestamps are
stored as time series data points with 2 values each. These 2 values encode the
timestamp and BG value of the associated BG value from the source graph, respectively.

Both values are normalized to the 0-255 range to save space in this binary dataset,
where 0 is the minimum and 255 the maximum for BG values, while for timestamps, 0 is
the oldest, 255 the newest timestamp. 255 corresponds to "now".

UINT8  number of time series data points, and time series type flag
* Bits 0..6 : Number of time series data points, meaning that the time series can contain
  a maximum of 127 data points
* Bit 7 : If set, then this time series is meant to be shown as a line graph on screen;
  otherwise, it is meant to be shown as a series of dots

For each data point:
UINT8  timestamp (normalized to the 0-255 range)
UINT8  BG value (normalized to the 0-255 range)

## Low/high BG threshold block

UINT8  block ID byte
* Bits 0..3 : Set to block ID 5

INT16  low BG threshold, in glucose units
INT16  high BG threshold, in glucose units
UINT8  low BG threshold, in BG time series
UINT8  high BG threshold, in BG time series

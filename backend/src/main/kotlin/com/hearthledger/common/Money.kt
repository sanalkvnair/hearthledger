package com.hearthledger.common

import java.math.BigDecimal
import java.math.RoundingMode

fun BigDecimal.money(): BigDecimal = setScale(4, RoundingMode.HALF_UP)

package com.campmeds.app.domain

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.distinctUntilChanged
import java.time.LocalDate

/** Re-evaluate after process restart and while collecting, including wall-clock/time-zone changes.
 * A one-second check also catches resume and manual clock changes without a database write.
 */
fun localDays() = flow {
    while (true) { emit(LocalDate.now()); delay(1000) }
}.distinctUntilChanged()

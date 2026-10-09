package com.campmeds.app.data.entity

/** Roles per spec section 6. Ordered by increasing privilege. */
enum class Role {
    PROVIDER,
    LEVEL1_OVERRIDE,
    ADMIN
}

/** Dose outcome per spec section 3 (DoseLog). */
enum class DoseStatus {
    GIVEN,
    HELD,
    REFUSED,
    MISSED
}

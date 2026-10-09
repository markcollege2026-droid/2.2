package com.campmeds.app.auth

import com.campmeds.app.data.entity.Role
import com.campmeds.app.data.entity.User
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Single-device, single-session-at-a-time login state (spec: local PIN login, not a network
 * account; no multi-device concerns in this version). Cleared on process death by design —
 * every shift starts with a fresh PIN entry.
 */
object Session {
    private val _currentUser = MutableStateFlow<User?>(null)
    val currentUser: StateFlow<User?> = _currentUser

    fun login(user: User) {
        _currentUser.value = user
    }

    fun logout() {
        _currentUser.value = null
    }

    /**
     * Reactive-friendly variant: pass the user collected with `Session.currentUser.collectAsState()`
     * so a composable re-evaluates permission whenever the session changes. (The no-argument version
     * reads the value once per composition and is not observed, which is how permission-gated buttons
     * could get stuck hidden.)
     */
    fun hasAtLeast(user: User?, role: Role): Boolean =
        user != null && user.role.ordinal >= role.ordinal

    fun hasAtLeast(role: Role): Boolean {
        val current = _currentUser.value ?: return false
        return current.role.ordinal >= role.ordinal
    }
}

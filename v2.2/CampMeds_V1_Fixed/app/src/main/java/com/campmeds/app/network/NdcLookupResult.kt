package com.campmeds.app.network

sealed class NdcLookupResult {
    data class Found(
        val ndc: String,
        val name: String,
        val strength: String,
        val form: String
    ) : NdcLookupResult()

    /** No match in openFDA — staff must use the manual override (isException = true). */
    object NotFound : NdcLookupResult()

    data class Error(val message: String) : NdcLookupResult()
}

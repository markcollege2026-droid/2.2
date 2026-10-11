package com.campmeds.app.network

/**
 * Outcome of an NDC lookup. Every case is distinct on purpose, so the Add/Edit Medication screen can say
 * what actually happened instead of a single vague "lookup failed":
 *
 *  - [Found]         a record was found and it resolves to ONE product (package included)
 *  - [NotFound]      the code is shaped like a valid NDC but the FDA NDC Directory has no such record
 *  - [Ambiguous]     more than one product matched — nothing is guessed
 *  - [InvalidInput]  the text cannot be an NDC at all (letters, wrong digit count, wrong arrangement)
 *  - [NetworkError]  openFDA could not be reached
 *  - [ApiError]      openFDA answered, but with an error or an unusable payload
 *
 * A failed lookup never blocks manual entry: the form keeps its "Manual entry" path, which is allowed to
 * save a medication with an empty NDC and no placeholder value.
 */
sealed class NdcLookupResult {

    /**
     * [ndc] is exactly what the user typed (kept for display and troubleshooting).
     * [matchedNdc] is the NDC form that the directory record actually matched — the two differ whenever
     * the input was a billing code (a zero was removed from one segment) or an ambiguous digit string.
     * [ambiguousInput] is true when the typed digits had more than one legal reading; the screen then asks
     * staff to confirm [matchedNdc] against the physical label instead of assuming it.
     */
    data class Found(
        val ndc: String,
        val matchedNdc: String,
        val name: String,
        val strength: String,
        val form: String,
        val isPackage: Boolean,
        val ambiguousInput: Boolean = false,
        val alternatives: List<String> = emptyList()
    ) : NdcLookupResult()

    object NotFound : NdcLookupResult()

    /** [interpretations] are the readings that were searched, [products] the distinct product NDCs found. */
    data class Ambiguous(
        val interpretations: List<String>,
        val products: List<String>
    ) : NdcLookupResult()

    data class InvalidInput(val message: String) : NdcLookupResult()

    data class NetworkError(val message: String) : NdcLookupResult()

    data class ApiError(val message: String) : NdcLookupResult()
}

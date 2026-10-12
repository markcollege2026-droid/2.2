package com.campmeds.app.network

import com.campmeds.app.domain.NdcNormalizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Thin client for the openFDA NDC Directory API — the ONLY network call the app makes. One lookup per
 * medication; the confirmed result is stored on the Medication row, so every later read is offline.
 *
 * How a lookup works now:
 *  1. [NdcNormalizer] turns the typed text into the NDC forms the directory really stores
 *     (10-digit product `product_ndc` and package `packaging.package_ndc` values). Text that is not an
 *     NDC at all stops here as [NdcLookupResult.InvalidInput] — it is never sent to openFDA.
 *  2. Every legal reading is searched, so an unhyphenated number cannot silently resolve to the wrong
 *     split: if more than one distinct product matches, the answer is [NdcLookupResult.Ambiguous] and
 *     nothing is selected.
 *  3. Each request names exactly one field (`product_ndc` or `packaging.package_ndc`) with the dashed
 *     value the directory uses, and is built with HttpUrl so quotes and dashes are encoded properly.
 *  4. Failures stay failures: openFDA's 404 means "no such record" ([NdcLookupResult.NotFound]), a
 *     transport problem is [NdcLookupResult.NetworkError], anything else is [NdcLookupResult.ApiError].
 *     A lookup is never satisfied by a guess, and never blocks the form's manual-entry path.
 *  5. One reading failing does not sink the others: the readings are all searched, a 5xx/429 answer is
 *     retried once (openFDA's own answers are momentary), and a match found for one reading is returned even
 *     if another reading could not be checked — the result then names the readings it could not check
 *     ([NdcLookupResult.Found.uncheckedReadings]) so the screen asks for the label to be re-checked. Only
 *     when NOTHING matched does a failed request become the reported outcome.
 */
class NdcApiClient(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build(),
    /** Pause before the single retry of a server-side failure; 0 in tests. */
    private val retryDelayMillis: Long = 250
) {
    companion object {
        private const val BASE_URL = "https://api.fda.gov/drug/ndc.json"

        // No `fields` parameter: openFDA's NDC endpoint answers "Invalid parameter: fields" for it, so the
        // whole record is read and only the parts the form shows are kept (see [parseRecords]).

        /** Per request; one candidate can only ever resolve to one product record in practice. */
        private const val LIMIT = "5"
    }

    /** One directory record that matched, reduced to what the medication form needs. */
    data class Match(
        val productNdc: String,
        /** The candidate value (dashed form) this record was found for. */
        val matchedValue: String,
        val packageNdc: String?,
        val name: String,
        val strength: String,
        val form: String
    )

    suspend fun lookup(ndc: String): NdcLookupResult = withContext(Dispatchers.IO) {
        val parsed = when (val p = NdcNormalizer.parse(ndc)) {
            is NdcNormalizer.Parsed.Invalid -> return@withContext NdcLookupResult.InvalidInput(p.message)
            is NdcNormalizer.Parsed.Ok -> p
        }

        val matches = LinkedHashMap<String, Match>()
        // Readings openFDA could not answer for, and the first failure to report if nothing else resolves.
        val unchecked = mutableListOf<String>()
        var failure: NdcLookupResult? = null

        for (candidate in parsed.candidates) {
            when (val outcome = queryWithOneRetry(candidate.value, candidate.isPackage)) {
                is QueryOutcome.Matched -> matches[candidate.value] = outcome.match.copy(matchedValue = candidate.value)
                QueryOutcome.NoMatch -> Unit
                is QueryOutcome.Failed -> {
                    // Keep checking the remaining readings: throwing away a record that another reading of
                    // the same digits found turns a working lookup into "lookup failed" for no reason.
                    if (failure == null) failure = outcome.result
                    unchecked += candidate.value
                }
            }
        }

        if (matches.isEmpty()) {
            // Nothing matched at all. A request that failed is reported as a failure — never as "no such
            // record", because the directory never actually answered for that number.
            return@withContext failure ?: NdcLookupResult.NotFound(notFoundNotes(parsed))
        }

        val products = matches.values.map { it.productNdc }.distinct()
        if (products.size > 1) {
            return@withContext NdcLookupResult.Ambiguous(
                interpretations = matches.keys.toList(),
                products = products
            )
        }

        val (matchedValue, match) = matches.entries.first()
        NdcLookupResult.Found(
            ndc = parsed.entered,
            matchedNdc = matchedValue,
            name = match.name,
            strength = match.strength,
            form = match.form,
            isPackage = parsed.candidates.firstOrNull { it.value == matchedValue }?.isPackage
                ?: (matchedValue.count { it == '-' } == 2),
            ambiguousInput = parsed.ambiguous,
            alternatives = parsed.candidates.map { it.value }.filter { it != matchedValue },
            uncheckedReadings = unchecked
        )
    }

    /**
     * Why a code with a legal shape can still have no record — the parts the user can act on. Built only from
     * what was typed, so it can never send anyone to a code the app made up.
     */
    private fun notFoundNotes(parsed: NdcNormalizer.Parsed.Ok): List<String> {
        val notes = mutableListOf<String>()

        val searched = parsed.candidates.joinToString(", ") { candidate ->
            (if (candidate.isPackage) "package " else "product ") + candidate.value
        }
        notes += "Searched the FDA NDC Directory for $searched — no record."

        // The digits of an undashed number are searched in every legal reading, so this only says something
        // new when the dashes the user typed pinned the grouping down to one of those readings.
        val typed = parsed.candidates.map { it.value }.toSet()
        val otherReadings = NdcNormalizer.candidates(parsed.digits)
            .map { it.value }
            .filter { it !in typed }
            .distinct()
        if (otherReadings.isNotEmpty()) {
            notes += "The same digits (${parsed.digits}) also read as ${otherReadings.joinToString(", ")}. " +
                "Only the grouping as typed was searched — if the carton shows one of those, enter it."
        }

        // "00904-617-10": a 5-digit labeler that starts with 0 is the padding of the 11-digit billing format,
        // not an assigned labeler code (the directory has no product under a labeler like that).
        val labeler = parsed.candidates.firstOrNull()?.value?.substringBefore('-').orEmpty()
        if (labeler.length == 5 && labeler.startsWith("0")) {
            notes += "The labeler segment \"$labeler\" starts with 0. A zero-leading 5-digit labeler is the " +
                "padding of the 11-digit billing format; the directory stores the labeler without it " +
                "(\"${labeler.substring(1)}\", not \"$labeler\"). Type the NDC exactly as the carton prints " +
                "it: 10 digits, or all 11 with the zero."
        }

        return notes
    }

    private sealed class QueryOutcome {
        data class Matched(val match: Match) : QueryOutcome()
        object NoMatch : QueryOutcome()
        /**
         * The request did not produce an answer. [retryable] marks the ones a second attempt can plausibly
         * fix (openFDA answering 5xx or 429, or an empty body) — a transport failure is not retried, so a
         * dead connection costs one timeout instead of two.
         */
        data class Failed(val result: NdcLookupResult, val retryable: Boolean) : QueryOutcome()
    }

    /** One retry for the failures that are usually momentary on openFDA's side. */
    private fun queryWithOneRetry(value: String, isPackage: Boolean): QueryOutcome {
        val first = query(value, isPackage)
        if (first !is QueryOutcome.Failed || !first.retryable) return first
        if (retryDelayMillis > 0) Thread.sleep(retryDelayMillis)
        return query(value, isPackage)
    }

    private fun query(value: String, isPackage: Boolean): QueryOutcome {
        // Field names and values exactly as the NDC Directory stores them: 10-digit, dashed.
        val field = if (isPackage) "packaging.package_ndc" else "product_ndc"
        val url = BASE_URL.toHttpUrl().newBuilder()
            .addQueryParameter("search", "$field:\"$value\"")
            .addQueryParameter("limit", LIMIT)
            .build()
        val request = Request.Builder().url(url).get().build()

        return try {
            client.newCall(request).execute().use { response ->
                when {
                    // openFDA answers 404 with {"error":{"code":"NOT_FOUND"}} when nothing matches.
                    response.code == 404 -> QueryOutcome.NoMatch
                    response.code == 429 -> QueryOutcome.Failed(
                        NdcLookupResult.ApiError("openFDA is limiting requests right now. Wait a minute and try again."),
                        retryable = true
                    )
                    !response.isSuccessful -> QueryOutcome.Failed(
                        NdcLookupResult.ApiError("openFDA returned HTTP ${response.code} for this lookup."),
                        // 4xx is this client's own fault (a bad query) — retrying it only wastes a request.
                        retryable = response.code >= 500
                    )
                    else -> {
                        val body = response.body?.string()
                        if (body.isNullOrBlank()) QueryOutcome.Failed(
                            NdcLookupResult.ApiError("openFDA returned an empty response."),
                            retryable = true
                        )
                        else {
                            val records = parseRecords(body, value)
                            if (records.isEmpty()) QueryOutcome.NoMatch else QueryOutcome.Matched(records.first())
                        }
                    }
                }
            }
        } catch (e: IOException) {
            // No connection, DNS failure, timeout, connection reset … all "could not reach openFDA".
            QueryOutcome.Failed(NdcLookupResult.NetworkError(e.message ?: "Could not reach openFDA."), retryable = false)
        } catch (e: Exception) {
            // Malformed JSON and anything else unexpected: reported, never thrown out of the coroutine.
            QueryOutcome.Failed(
                NdcLookupResult.ApiError("Unexpected response from openFDA (${e.javaClass.simpleName})."),
                retryable = false
            )
        }
    }

    /**
     * Parses a successful openFDA payload into matches. An `error` object or an empty/missing `results`
     * array both mean "no record for this value" — the API uses both shapes.
     */
    internal fun parseRecords(body: String, searchedValue: String): List<Match> {
        val json = JSONObject(body)
        json.optJSONObject("error")?.let { error ->
            val code = error.optString("code", "")
            if (code.equals("NOT_FOUND", ignoreCase = true)) return emptyList()
        }
        val results = json.optJSONArray("results") ?: return emptyList()
        return (0 until results.length()).mapNotNull { i ->
            val record = results.optJSONObject(i) ?: return@mapNotNull null
            val productNdc = record.optString("product_ndc").takeIf { it.isNotBlank() } ?: return@mapNotNull null

            val packaging = record.optJSONArray("packaging")
            var matchedPackage: String? = null
            if (packaging != null) {
                for (p in 0 until packaging.length()) {
                    val entry = packaging.optJSONObject(p) ?: continue
                    val packageNdc = entry.optString("package_ndc")
                    if (packageNdc.equals(searchedValue, ignoreCase = true)) matchedPackage = packageNdc
                }
            }

            Match(
                productNdc = productNdc,
                matchedValue = matchedPackage ?: productNdc,
                packageNdc = matchedPackage,
                name = record.optString("brand_name").ifBlank {
                    record.optString("generic_name").ifBlank { "Unknown medication" }
                },
                strength = strengthOf(record),
                form = record.optString("dosage_form").ifBlank { "Unknown form" }
            )
        }
    }

    private fun strengthOf(record: JSONObject): String = buildString {
        val ingredients = record.optJSONArray("active_ingredients")
        if (ingredients != null) {
            for (i in 0 until ingredients.length()) {
                val value = ingredients.optJSONObject(i)?.optString("strength", "").orEmpty()
                if (value.isNotBlank()) {
                    if (isNotEmpty()) append(", ")
                    append(value)
                }
            }
        }
        if (isEmpty()) append("Not specified")
    }
}

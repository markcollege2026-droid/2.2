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
 */
class NdcApiClient(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()
) {
    companion object {
        private const val BASE_URL = "https://api.fda.gov/drug/ndc.json"

        /** Ask for only the fields the form shows. */
        private const val FIELDS = "product_ndc,packaging,generic_name,brand_name,dosage_form,active_ingredients"

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
        for (candidate in parsed.candidates) {
            when (val outcome = query(candidate.value, candidate.isPackage)) {
                is QueryOutcome.Matched -> matches[candidate.value] = outcome.match.copy(matchedValue = candidate.value)
                QueryOutcome.NoMatch -> Unit
                // A failed request means we cannot rule out a second product, so the lookup reports the
                // failure instead of returning a possibly-ambiguous match.
                is QueryOutcome.Failed -> return@withContext outcome.result
            }
        }

        if (matches.isEmpty()) return@withContext NdcLookupResult.NotFound

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
            alternatives = parsed.candidates.map { it.value }.filter { it != matchedValue }
        )
    }

    private sealed class QueryOutcome {
        data class Matched(val match: Match) : QueryOutcome()
        object NoMatch : QueryOutcome()
        data class Failed(val result: NdcLookupResult) : QueryOutcome()
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
                        NdcLookupResult.ApiError("openFDA is limiting requests right now. Wait a minute and try again.")
                    )
                    !response.isSuccessful -> QueryOutcome.Failed(
                        NdcLookupResult.ApiError("openFDA returned HTTP ${response.code} for this lookup.")
                    )
                    else -> {
                        val body = response.body?.string()
                        if (body.isNullOrBlank()) QueryOutcome.Failed(NdcLookupResult.ApiError("openFDA returned an empty response."))
                        else {
                            val records = parseRecords(body, value)
                            if (records.isEmpty()) QueryOutcome.NoMatch else QueryOutcome.Matched(records.first())
                        }
                    }
                }
            }
        } catch (e: IOException) {
            // No connection, DNS failure, timeout, connection reset … all "could not reach openFDA".
            QueryOutcome.Failed(NdcLookupResult.NetworkError(e.message ?: "Could not reach openFDA."))
        } catch (e: Exception) {
            // Malformed JSON and anything else unexpected: reported, never thrown out of the coroutine.
            QueryOutcome.Failed(
                NdcLookupResult.ApiError("Unexpected response from openFDA (${e.javaClass.simpleName}).")
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

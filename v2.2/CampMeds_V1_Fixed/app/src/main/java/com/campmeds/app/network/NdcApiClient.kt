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
 * Thin client for the openFDA NDC Directory API. This is the ONLY network call the app makes
 * (spec section 2/7) — one lookup per medication, whose result is cached in Room afterward so
 * the app works fully offline from then on.
 *
 * Fixes vs. the original client:
 *  - accepts package NDCs, 11-digit NDCs and undashed digits (see [NdcNormalizer]); the original only
 *    matched an exact dashed 2-segment product code;
 *  - an undashed number that matches more than one product is reported instead of silently picking one;
 *  - any parsing/unexpected failure becomes an [NdcLookupResult.Error] instead of crashing the app
 *    (only IOException was caught before; a JSON problem escaped into the coroutine and killed the process);
 *  - the query is built with HttpUrl so quotes/spaces are encoded correctly.
 */
class NdcApiClient(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()
) {
    companion object {
        private const val BASE_URL = "https://api.fda.gov/drug/ndc.json"
        const val INVALID_NDC_MESSAGE =
            "That doesn't look like an NDC. Use digits and dashes, e.g. 0069-2587 or 0069-2587-68."
    }

    suspend fun lookup(ndc: String): NdcLookupResult = withContext(Dispatchers.IO) {
        val typed = ndc.trim()
        if (typed.isBlank()) return@withContext NdcLookupResult.Error("NDC is empty")

        val candidates = NdcNormalizer.candidates(typed)
        if (candidates.isEmpty()) return@withContext NdcLookupResult.Error(INVALID_NDC_MESSAGE)

        val search = candidates.joinToString(" OR ") {
            if (it.isPackage) "packaging.package_ndc:\"${it.value}\"" else "product_ndc:\"${it.value}\""
        }
        val url = BASE_URL.toHttpUrl().newBuilder()
            .addQueryParameter("search", search)
            .addQueryParameter("limit", "5")
            .build()
        val request = Request.Builder().url(url).get().build()

        try {
            client.newCall(request).execute().use { response ->
                when {
                    response.code == 404 -> NdcLookupResult.NotFound // openFDA: 404 NOT_FOUND = zero matches
                    response.code == 429 ->
                        NdcLookupResult.Error("openFDA is limiting requests right now. Wait a minute and try again.")
                    !response.isSuccessful -> NdcLookupResult.Error("HTTP ${response.code}")
                    else -> {
                        val body = response.body?.string()
                        if (body.isNullOrBlank()) NdcLookupResult.Error("Empty response body")
                        else parse(typed, body)
                    }
                }
            }
        } catch (e: IOException) {
            NdcLookupResult.Error(e.message ?: "Network error")
        } catch (e: Exception) {
            NdcLookupResult.Error("Unexpected response from openFDA (${e.javaClass.simpleName}).")
        }
    }

    internal fun parse(ndc: String, body: String): NdcLookupResult {
        val json = JSONObject(body)
        val results = json.optJSONArray("results") ?: return NdcLookupResult.NotFound
        if (results.length() == 0) return NdcLookupResult.NotFound

        val products = (0 until results.length()).map { results.getJSONObject(it) }
        val distinctProducts = products.map { it.optString("product_ndc") }.distinct()
        if (distinctProducts.size > 1) {
            return NdcLookupResult.Error(
                "This number matches more than one product (${distinctProducts.joinToString(", ")}). " +
                    "Enter the NDC with its dashes, exactly as printed on the label."
            )
        }

        val first = products.first()
        val name = first.optString("brand_name").ifBlank {
            first.optString("generic_name").ifBlank { "Unknown medication" }
        }
        val form = first.optString("dosage_form").ifBlank { "Unknown form" }

        val strength = buildString {
            val ingredients = first.optJSONArray("active_ingredients")
            if (ingredients != null) {
                for (i in 0 until ingredients.length()) {
                    val strengthVal = ingredients.getJSONObject(i).optString("strength", "")
                    if (strengthVal.isNotBlank()) {
                        if (isNotEmpty()) append(", ")
                        append(strengthVal)
                    }
                }
            }
            if (isEmpty()) append("Not specified")
        }

        return NdcLookupResult.Found(ndc = ndc, name = name, strength = strength, form = form)
    }
}

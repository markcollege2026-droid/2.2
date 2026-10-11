package com.campmeds.app.network

import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * Query construction, response parsing and failure handling of the openFDA NDC Directory integration.
 *
 * The API is faked at the HTTP layer: every test states which request it expects and what openFDA would
 * answer, so the assertions are about this client's behaviour, not about the live directory.
 */
class NdcApiClientTest {

    private fun client(
        respond: (HttpUrl) -> Pair<Int, String>,
        seen: MutableList<String> = mutableListOf()
    ) = NdcApiClient(
        OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
            seen += chain.request().url.toString()
            val (code, body) = respond(chain.request().url)
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(code)
                .message("test")
                .body(body.toResponseBody("application/json".toMediaType()))
                .build()
        }).build()
    )

    private fun q(value: String) = "\"" + value + "\""

    private fun product(
        ndc: String,
        brand: String = "Zyrtec",
        generic: String = "cetirizine hydrochloride",
        strength: String = "10 mg/1",
        form: String = "TABLET",
        packageNdc: String? = null
    ): String {
        val packaging = if (packageNdc == null) "" else ",\"packaging\":[{\"package_ndc\":${q(packageNdc)}}]"
        return "{\"product_ndc\":${q(ndc)},\"brand_name\":${q(brand)},\"generic_name\":${q(generic)}," +
            "\"dosage_form\":${q(form)},\"active_ingredients\":[{\"name\":\"X\",\"strength\":${q(strength)}}]$packaging}"
    }

    private fun results(vararg records: String) = """{"meta":{"results":{"total":${records.size}}},"results":[${records.joinToString(",")}]}"""

    private fun searchOf(url: HttpUrl): String = url.queryParameter("search").orEmpty()

    // ---------------------------------------------------------------------------------------------
    // Query construction
    // ---------------------------------------------------------------------------------------------

    @Test
    fun packageNdcIsSearchedInThePackageFieldAndResolvesTheRightPackage() = runBlocking {
        val seen = mutableListOf<String>()
        val client = client({ url ->
            if (searchOf(url) == "packaging.package_ndc:\"0069-2587-68\"") {
                200 to results(product("0069-2587", packageNdc = "0069-2587-68"))
            } else 404 to """{"error":{"code":"NOT_FOUND"}}"""
        }, seen)

        val result = client.lookup("0069-2587-68")

        assertTrue(result is NdcLookupResult.Found)
        result as NdcLookupResult.Found
        assertEquals("0069-2587-68", result.ndc)
        assertEquals("0069-2587-68", result.matchedNdc)
        assertEquals("Zyrtec", result.name)
        assertEquals("10 mg/1", result.strength)
        assertEquals("TABLET", result.form)
        assertTrue(result.isPackage)
        assertFalse(result.ambiguousInput)
        assertEquals(1, seen.size)
    }

    @Test
    fun productNdcIsSearchedInTheProductField() = runBlocking {
        val seen = mutableListOf<String>()
        val client = client({ url ->
            if (searchOf(url) == "product_ndc:\"0069-2587\"") 200 to results(product("0069-2587"))
            else 404 to """{"error":{"code":"NOT_FOUND"}}"""
        }, seen)

        val result = client.lookup("0069-2587") as NdcLookupResult.Found

        assertEquals("0069-2587", result.matchedNdc)
        assertFalse(result.isPackage)
        assertTrue(seen.single().contains("search=product_ndc"))
    }

    @Test
    fun elevenDigitBillingCodeIsSearchedAsItsTenDigitFormAndTheTypedValueIsKept() = runBlocking {
        val seen = mutableListOf<String>()
        val client = client({ url ->
            if (searchOf(url) == "packaging.package_ndc:\"0069-2587-68\"") {
                200 to results(product("0069-2587", packageNdc = "0069-2587-68"))
            } else 404 to """{"error":{"code":"NOT_FOUND"}}"""
        }, seen)

        val result = client.lookup("00069258768") as NdcLookupResult.Found

        assertEquals("00069258768", result.ndc)        // as typed, for display/troubleshooting
        assertEquals("0069-2587-68", result.matchedNdc) // what the directory actually matched
        assertTrue(seen.single().contains("0069-2587-68"))
        assertFalse(seen.single().contains("00069258768"))
    }

    @Test
    fun theQueryIsBuiltWithTheExactFieldAndDashedValue() = runBlocking {
        val seen = mutableListOf<String>()
        val client = client({ 404 to "" }, seen)
        client.lookup("0069-2587")

        // Decoded, so this asserts the contract with openFDA (field:value with quotes) rather than how
        // OkHttp happens to percent-encode it.
        val url = seen.single().toHttpUrl()
        assertEquals("product_ndc:\"0069-2587\"", url.queryParameter("search"))
        assertEquals("5", url.queryParameter("limit"))
        assertTrue(seen.single().startsWith("https://api.fda.gov/drug/ndc.json"))
    }

    // ---------------------------------------------------------------------------------------------
    // Ambiguity: every reading is tried, and nothing is guessed
    // ---------------------------------------------------------------------------------------------

    @Test
    fun undashedNumberThatMatchesTwoProductsIsAmbiguousAndReportsBoth() = runBlocking {
        val seen = mutableListOf<String>()
        val client = client({ url ->
            when (searchOf(url)) {
                "packaging.package_ndc:\"0069-2587-68\"" -> 200 to results(product("0069-2587", packageNdc = "0069-2587-68"))
                "packaging.package_ndc:\"00692-587-68\"" -> 200 to results(product("00692-587", brand = "Other", packageNdc = "00692-587-68"))
                else -> 404 to """{"error":{"code":"NOT_FOUND"}}"""
            }
        }, seen)

        val result = client.lookup("0069258768")

        assertTrue(result is NdcLookupResult.Ambiguous)
        result as NdcLookupResult.Ambiguous
        assertEquals(listOf("0069-2587-68", "00692-587-68"), result.interpretations)
        assertEquals(listOf("0069-2587", "00692-587"), result.products)
        assertEquals(3, seen.size) // all three readings were checked
    }

    @Test
    fun oneMatchingReadingIsReportedWithTheOtherReadingsAsAlternatives() = runBlocking {
        val client = client({ url ->
            if (searchOf(url) == "packaging.package_ndc:\"0069-2587-68\"") {
                200 to results(product("0069-2587", packageNdc = "0069-2587-68"))
            } else 404 to """{"error":{"code":"NOT_FOUND"}}"""
        })

        val result = client.lookup("0069258768") as NdcLookupResult.Found

        assertTrue(result.ambiguousInput)
        assertEquals("0069-2587-68", result.matchedNdc)
        assertEquals(listOf("00692-587-68", "00692-5876-8"), result.alternatives)
    }

    @Test
    fun hyphenatedInputIsNotFlaggedAsAmbiguous() = runBlocking {
        val client = client({ 200 to results(product("0069-2587", packageNdc = "0069-2587-68")) })
        val result = client.lookup("0069-2587-68") as NdcLookupResult.Found
        assertFalse(result.ambiguousInput)
        assertTrue(result.alternatives.isEmpty())
    }

    // ---------------------------------------------------------------------------------------------
    // Failures: each kind is reported as itself
    // ---------------------------------------------------------------------------------------------

    @Test
    fun noRecordIsNotFoundNotAnError() = runBlocking {
        val result = client({ 404 to """{"error":{"code":"NOT_FOUND"}}""" }).lookup("0069-2587")
        assertEquals(NdcLookupResult.NotFound, result)
    }

    @Test
    fun errorObjectWith200AndEmptyResultsBothMeanNotFound() = runBlocking {
        assertEquals(
            NdcLookupResult.NotFound,
            client({ 200 to """{"error":{"code":"NOT_FOUND","message":"No matches found!"}}""" }).lookup("0069-2587")
        )
        assertEquals(
            NdcLookupResult.NotFound,
            client({ 200 to """{"meta":{},"results":[]}""" }).lookup("0069-2587")
        )
    }

    @Test
    fun invalidInputIsRejectedBeforeAnyRequestIsMade() = runBlocking {
        val seen = mutableListOf<String>()
        val result = client({ 200 to "{}" }, seen).lookup("not-an-ndc")

        assertTrue(result is NdcLookupResult.InvalidInput)
        assertTrue((result as NdcLookupResult.InvalidInput).message.contains("digits and dashes"))
        assertTrue(seen.isEmpty())
        assertTrue(client({ 200 to "{}" }, seen).lookup("12345") is NdcLookupResult.InvalidInput)
        assertTrue(seen.isEmpty())
    }

    @Test
    fun rateLimitingIsAnApiErrorWithAdvice() = runBlocking {
        val result = client({ 429 to "" }).lookup("0069-2587")
        assertTrue(result is NdcLookupResult.ApiError)
        assertTrue((result as NdcLookupResult.ApiError).message.contains("limiting requests"))
    }

    @Test
    fun serverErrorIsAnApiError() = runBlocking {
        val result = client({ 500 to "boom" }).lookup("0069-2587")
        assertTrue(result is NdcLookupResult.ApiError)
        assertTrue((result as NdcLookupResult.ApiError).message.contains("HTTP 500"))
    }

    @Test
    fun transportProblemIsANetworkError() = runBlocking {
        val throwing = NdcApiClient(
            OkHttpClient.Builder().addInterceptor(Interceptor { throw IOException("Unable to resolve host api.fda.gov") }).build()
        )
        val result = throwing.lookup("0069-2587")
        assertTrue(result is NdcLookupResult.NetworkError)
        assertTrue((result as NdcLookupResult.NetworkError).message.contains("api.fda.gov"))
    }

    @Test
    fun malformedPayloadIsAnApiErrorAndNeverACrash() = runBlocking {
        assertTrue(client({ 200 to "<html>oops</html>" }).lookup("0069-2587") is NdcLookupResult.ApiError)
        assertTrue(client({ 200 to "" }).lookup("0069-2587") is NdcLookupResult.ApiError)
    }

    @Test
    fun aFailedReadingStopsTheLookupInsteadOfReturningAPossiblyAmbiguousMatch() = runBlocking {
        // The first reading matches, the second fails: the failure is reported, the match is not guessed.
        val seen = mutableListOf<String>()
        val client = client({ url ->
            when (searchOf(url)) {
                "packaging.package_ndc:\"0069-2587-68\"" -> 200 to results(product("0069-2587", packageNdc = "0069-2587-68"))
                else -> 429 to ""
            }
        }, seen)
        val result = client.lookup("0069258768")
        assertTrue(result is NdcLookupResult.ApiError)
        assertEquals(2, seen.size)
    }

    @Test
    fun strengthFallsBackWhenTheDirectoryHasNone() = runBlocking {
        val body = """{"results":[{"product_ndc":"0069-2587","generic_name":"cetirizine"}]}"""
        val result = client({ 200 to body }).lookup("0069-2587") as NdcLookupResult.Found
        assertEquals("cetirizine", result.name)
        assertEquals("Not specified", result.strength)
        assertEquals("Unknown form", result.form)
    }
}

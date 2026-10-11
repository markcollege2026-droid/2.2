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
        }).build(),
        // No pause between a failed request and its retry: the tests should not sleep.
        retryDelayMillis = 0
    )

    private val notFound = """{"error":{"code":"NOT_FOUND","message":"No matches found!"}}"""

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

    /** What openFDA answers when the value is a perfectly valid NDC with no record. */
    private fun notFoundPair(): Pair<Int, String> = 404 to notFound

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

        assertTrue("expected a match, got $result", result is NdcLookupResult.Found)
        val found = result as NdcLookupResult.Found
        assertEquals("0069-2587-68", found.ndc)
        assertEquals("0069-2587-68", found.matchedNdc)
        assertEquals("Zyrtec", found.name)
        assertEquals("10 mg/1", found.strength)
        assertEquals("TABLET", found.form)
        assertTrue(found.isPackage)
        assertFalse(found.ambiguousInput)
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

        assertTrue("expected ambiguity, got $result", result is NdcLookupResult.Ambiguous)
        val ambiguous = result as NdcLookupResult.Ambiguous
        assertEquals(listOf("0069-2587-68", "00692-587-68"), ambiguous.interpretations)
        assertEquals(listOf("0069-2587", "00692-587"), ambiguous.products)
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
    fun aReadingThatKeepsFailingIsNamedAlongsideTheMatchInsteadOfReplacingIt() = runBlocking {
        // The 4-4-2 reading matches; the other two readings keep answering 429. The match is returned — with
        // the readings that could not be checked named on it — so the number is never silently resolved to
        // the reading that happened to be answered first.
        val seen = mutableListOf<String>()
        val client = client({ url ->
            when (searchOf(url)) {
                "packaging.package_ndc:\"0069-2587-68\"" -> 200 to results(product("0069-2587", packageNdc = "0069-2587-68"))
                else -> 429 to ""
            }
        }, seen)

        val result = client.lookup("0069258768")

        assertTrue("expected the match, got $result", result is NdcLookupResult.Found)
        val found = result as NdcLookupResult.Found
        assertEquals("0069-2587-68", found.matchedNdc)
        assertEquals(listOf("00692-587-68", "00692-5876-8"), found.uncheckedReadings)
        assertEquals(5, seen.size) // the match, plus the two failing readings tried twice each
    }

    // ---------------------------------------------------------------------------------------------
    // 5-3-2 (and any other multi-reading input): one bad answer must not throw away a good one
    // ---------------------------------------------------------------------------------------------

    @Test
    fun fiveThreeTwoWithoutDashesResolvesToTheFiveThreeTwoReading() = runBlocking {
        // "1069511418" is a real 5-3-2 package, 10695-114-18 (Willow Birch Pharma). Its 4-4-2 and 5-4-1
        // readings have no record, and they are asked for FIRST — which is why this case used to fail.
        val seen = mutableListOf<String>()
        val client = client({ url ->
            if (searchOf(url) == "packaging.package_ndc:\"10695-114-18\"") {
                200 to results(product("10695-114", brand = "Vancomycin", packageNdc = "10695-114-18"))
            } else notFoundPair()
        }, seen)

        val result = client.lookup("1069511418")

        assertTrue("expected a match, got $result", result is NdcLookupResult.Found)
        val found = result as NdcLookupResult.Found
        assertEquals("10695-114-18", found.matchedNdc)
        assertTrue(found.isPackage)
        assertTrue(found.ambiguousInput)
        assertEquals(listOf("1069-5114-18", "10695-1141-8"), found.alternatives)
        assertTrue(found.uncheckedReadings.isEmpty())
        assertEquals(3, seen.size)
    }

    @Test
    fun fiveThreeTwoWithDashesIsOneRequestAndIsNotAmbiguous() = runBlocking {
        val seen = mutableListOf<String>()
        val client = client({ 200 to results(product("68180-613", brand = "Quetiapine", packageNdc = "68180-613-07")) }, seen)

        val result = client.lookup("68180-613-07")

        val found = result as NdcLookupResult.Found
        assertEquals("68180-613-07", found.matchedNdc)
        assertTrue(found.isPackage)
        assertFalse(found.ambiguousInput)
        assertEquals(1, seen.size)
    }

    @Test
    fun aMomentaryServerErrorOnOneReadingIsRetriedAndTheMatchIsKept() = runBlocking {
        var failedOnce = false
        val seen = mutableListOf<String>()
        val client = client({ url ->
            when (searchOf(url)) {
                "packaging.package_ndc:\"68180-613-07\"" ->
                    200 to results(product("68180-613", brand = "Quetiapine", packageNdc = "68180-613-07"))
                // openFDA answers 5xx for a moment and then serves the same query normally.
                "packaging.package_ndc:\"6818-0613-07\"" ->
                    if (!failedOnce) {
                        failedOnce = true
                        500 to "Internal Server Error"
                    } else notFoundPair()
                else -> notFoundPair()
            }
        }, seen)

        val result = client.lookup("6818061307")

        assertTrue("expected the match, got $result", result is NdcLookupResult.Found)
        val found = result as NdcLookupResult.Found
        assertEquals("68180-613-07", found.matchedNdc)
        assertTrue(found.uncheckedReadings.isEmpty())
        assertEquals(2, seen.count { it.contains("6818-0613-07") }) // the failure and its retry
    }

    @Test
    fun aReadingThatNeverAnswersIsNamedOnTheResultSoTheLabelCanBeChecked() = runBlocking {
        val client = client({ url ->
            when (searchOf(url)) {
                "packaging.package_ndc:\"68180-613-07\"" ->
                    200 to results(product("68180-613", brand = "Quetiapine", packageNdc = "68180-613-07"))
                else -> 500 to "Internal Server Error"
            }
        })

        val result = client.lookup("6818061307")

        assertTrue("expected the match to survive, got $result", result is NdcLookupResult.Found)
        val found = result as NdcLookupResult.Found
        assertEquals("68180-613-07", found.matchedNdc)
        assertEquals(listOf("6818-0613-07", "68180-6130-7"), found.uncheckedReadings)
    }

    @Test
    fun whenNothingMatchesAServerErrorIsAFailureAndNeverNotFound() = runBlocking {
        val result = client({ 503 to "Service Unavailable" }).lookup("6818061307")
        assertTrue("expected a failure, got $result", result is NdcLookupResult.ApiError)
        assertTrue((result as NdcLookupResult.ApiError).message.contains("HTTP 503"))
    }

    @Test
    fun aClientSideRejectionIsNotRetried() = runBlocking {
        // 4xx means this client asked something openFDA cannot answer; asking again would just waste a request.
        val seen = mutableListOf<String>()
        client({ 400 to """{"error":{"code":"BAD_REQUEST"}}""" }, seen).lookup("0069-2587")
        assertEquals(1, seen.size)
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

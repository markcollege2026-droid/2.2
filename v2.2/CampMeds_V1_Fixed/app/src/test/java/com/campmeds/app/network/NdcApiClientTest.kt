package com.campmeds.app.network

import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NdcApiClientTest {
    private fun client(code: Int, body: String, seen: MutableList<String> = mutableListOf()) = NdcApiClient(
        OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
            seen += chain.request().url.toString()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code).message("x")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }).build()
    )

    private fun product(ndc: String, brand: String = "Zyrtec") =
        """{"product_ndc":"$ndc","brand_name":"$brand","dosage_form":"TABLET","active_ingredients":[{"name":"X","strength":"10 mg/1"}]}"""

    @Test fun packageNdcIsSearchedAsPackageAndParsed() = runBlocking {
        val seen = mutableListOf<String>()
        val r = client(200, """{"results":[${product("0069-2587")}]}""", seen).lookup("0069-2587-68")
        assertEquals(NdcLookupResult.Found("0069-2587-68", "Zyrtec", "10 mg/1", "TABLET"), r)
        assertTrue(seen.single().contains("packaging.package_ndc"))
    }

    @Test fun notFound404() = runBlocking {
        assertEquals(NdcLookupResult.NotFound, client(404, """{"error":{"code":"NOT_FOUND"}}""").lookup("0069-2587"))
    }

    @Test fun ambiguousUndashedNumberIsReportedNotGuessed() = runBlocking {
        val body = """{"results":[${product("0069-2587")},${product("00692-587", "Other")}]}"""
        val r = client(200, body).lookup("0069258768")
        assertTrue(r is NdcLookupResult.Error && r.message.contains("more than one product"))
    }

    @Test fun garbageAndBadJsonBecomeErrorsInsteadOfCrashes() = runBlocking {
        assertTrue(client(200, "{}").lookup("not-an-ndc") is NdcLookupResult.Error)
        assertTrue(client(200, "<html>oops</html>").lookup("0069-2587") is NdcLookupResult.Error)
        assertTrue(client(429, "").lookup("0069-2587") is NdcLookupResult.Error)
    }
}

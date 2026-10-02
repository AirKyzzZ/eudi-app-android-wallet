package eu.europa.ec.networklogic.util

import eu.europa.ec.businesslogic.controller.log.LogController
import io.ktor.client.HttpClient
import io.ktor.client.request.accept
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.content.TextContent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

data class VeranaHttpResponse(
    val status: Int,
    val body: String,
)

class VeranaHttpClient(
    private val httpClient: HttpClient,
    private val logController: LogController,
) {
    suspend fun get(
        url: String,
        accept: ContentType = ContentType.Application.Json,
    ): VeranaHttpResponse? = request(url) {
        httpClient.get(url) { accept(accept) }
    }

    suspend fun postJson(url: String, body: String): VeranaHttpResponse? = request(url) {
        httpClient.post(url) {
            accept(ContentType.Application.Json)
            setBody(TextContent(body, ContentType.Application.Json))
        }
    }

    private suspend fun request(url: String, block: suspend () -> HttpResponse): VeranaHttpResponse? = try {
        withTimeoutOrNull(REQUEST_TIMEOUT_MILLIS) {
            val response = block()
            VeranaHttpResponse(status = response.status.value, body = response.bodyAsText())
        }.also { if (it == null) logController.w(TAG) { "Verana request to $url timed out" } }
    } catch (error: CancellationException) {
        throw error
    } catch (error: Throwable) {
        logController.w(TAG) { "Verana request to $url failed: $error" }
        null
    }

    private companion object {
        const val TAG = "VeranaTrust"
        const val REQUEST_TIMEOUT_MILLIS = 15_000L
    }
}

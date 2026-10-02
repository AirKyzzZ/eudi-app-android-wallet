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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
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

    // Cancelling an in-flight request on the ktor Android engine disconnects it from the cancelling thread, Main here, and crashes the app.
    private val requests = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private suspend fun request(url: String, block: suspend () -> HttpResponse): VeranaHttpResponse? {
        val call = requests.async {
            try {
                val response = block()
                VeranaHttpResponse(status = response.status.value, body = response.bodyAsText())
            } catch (error: Throwable) {
                logController.w(TAG) { "Verana request to $url failed: $error" }
                null
            }
        }
        return withTimeoutOrNull(REQUEST_TIMEOUT_MILLIS) { call.await() }
            .also { if (it == null && !call.isCompleted) logController.w(TAG) { "Verana request to $url timed out" } }
    }

    private companion object {
        const val TAG = "VeranaTrust"
        const val REQUEST_TIMEOUT_MILLIS = 15_000L
    }
}

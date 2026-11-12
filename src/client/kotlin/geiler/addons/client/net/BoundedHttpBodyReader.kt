package geiler.addons.client.net

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.nio.ByteBuffer
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.ExecutionException
import java.util.concurrent.Flow
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** Reads an HTTP response body with both a strict byte cap and an end-to-end deadline. */
object BoundedHttpBodyReader {
    @JvmStatic
    @Throws(IOException::class, InterruptedException::class)
    fun send(
        client: HttpClient?,
        request: HttpRequest?,
        maximumBytes: Int,
        deadline: Duration?,
    ): HttpResponse<ByteArray> {
        if (client == null || request == null || maximumBytes < 0 || deadline == null ||
            deadline.isZero || deadline.isNegative
        ) {
            throw IllegalArgumentException("Invalid HTTP body limits")
        }
        val handler = HttpResponse.BodyHandler<ByteArray> { LimitedSubscriber(maximumBytes) }
        val response = client.sendAsync(request, handler)
        try {
            return response.get(maxOf(1L, deadline.toMillis()), TimeUnit.MILLISECONDS)
        } catch (timeout: TimeoutException) {
            response.cancel(true)
            throw HttpTimeoutException("HTTP response body exceeded its ${deadline.toSeconds()} second deadline")
        } catch (failed: ExecutionException) {
            val cause = failed.cause
            if (cause is IOException) throw cause
            if (cause is RuntimeException) throw cause
            throw IOException("Could not read HTTP response body", cause)
        } catch (interrupted: InterruptedException) {
            response.cancel(true)
            throw interrupted
        }
    }

    private class LimitedSubscriber(private val maximumBytes: Int) : HttpResponse.BodySubscriber<ByteArray> {
        private val output = ByteArrayOutputStream(minOf(maximumBytes, COPY_BUFFER_SIZE))
        private val body = CompletableFuture<ByteArray>()
        private val copyBuffer = ByteArray(COPY_BUFFER_SIZE)
        private var subscription: Flow.Subscription? = null
        private var received = 0

        override fun getBody(): CompletionStage<ByteArray> = body

        override fun onSubscribe(next: Flow.Subscription) {
            if (subscription != null) {
                next.cancel()
                return
            }
            subscription = next
            next.request(1)
        }

        override fun onNext(buffers: List<ByteBuffer>) {
            if (body.isDone) return
            try {
                for (source in buffers) {
                    while (source.hasRemaining()) {
                        val remaining = maximumBytes - received
                        if (remaining <= 0) throw IOException("HTTP response exceeded $maximumBytes bytes")
                        val count = minOf(source.remaining(), minOf(copyBuffer.size, remaining))
                        source.get(copyBuffer, 0, count)
                        output.write(copyBuffer, 0, count)
                        received += count
                    }
                }
                subscription!!.request(1)
            } catch (error: IOException) {
                subscription!!.cancel()
                body.completeExceptionally(error)
            }
        }

        override fun onError(error: Throwable) {
            body.completeExceptionally(error)
        }

        override fun onComplete() {
            body.complete(output.toByteArray())
        }

        private companion object {
            const val COPY_BUFFER_SIZE = 8 * 1024
        }
    }
}

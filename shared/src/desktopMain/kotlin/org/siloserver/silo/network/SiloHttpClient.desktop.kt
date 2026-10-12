package org.siloserver.silo.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp

actual fun createPlatformHttpClient(retryOnConnectionFailure: Boolean): HttpClient =
    HttpClient(OkHttp) {
        engine {
            config { retryOnConnectionFailure(retryOnConnectionFailure) }
        }
    }

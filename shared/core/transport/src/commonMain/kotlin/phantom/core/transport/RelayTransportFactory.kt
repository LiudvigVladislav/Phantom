package phantom.core.transport

import io.ktor.client.HttpClient

expect fun createHttpClient(): HttpClient

package `in`.dragonbra.javasteam.steam.cdn

import `in`.dragonbra.javasteam.steam.steamclient.SteamClient
import okhttp3.OkHttpClient
import java.io.Closeable
import `in`.dragonbra.javasteam.types.ChunkData
import `in`.dragonbra.javasteam.types.DepotManifest
import `in`.dragonbra.javasteam.steam.handlers.steamcontent.SteamContent
import `in`.dragonbra.javasteam.util.SteamKitWebRequestException
import `in`.dragonbra.javasteam.util.Utils
import `in`.dragonbra.javasteam.util.log.LogManager
import `in`.dragonbra.javasteam.util.log.Logger
import kotlinx.coroutines.*
import okhttp3.HttpUrl
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.zip.*

/**
 * The [Client] class is used for downloading game content from the Steam servers.
 * @constructor Initializes a new instance of the [Client] class.
 * @param steamClient The [SteamClient] this instance will be associated with.
 * The SteamClient instance must be connected and logged onto Steam.
 */
class Client(steamClient: SteamClient) : Closeable {
    private val httpClient: OkHttpClient = steamClient.configuration.httpClient

    companion object {
        /**
         * Default timeout to use when making requests
         */
        var requestTimeout = 10000L

        /**
         * Default timeout to use when reading the response body
         */
        var responseBodyTimeout = 60000L

        private val logger: Logger = LogManager.getLogger(Client::class.java)

        fun buildCommand(
            server: Server,
            command: String,
            query: String? = null,
            proxyServer: Server? = null
        ): HttpUrl {
            val uriBuilder = HttpUrl.Builder()
                .scheme(if (server.protocol == Server.ConnectionProtocol.HTTP) "http" else "https")
                .host(server.vHost)
                .port(server.port)
                .encodedPath(command)
                .query(query ?: "")

            if (proxyServer != null && proxyServer.useAsProxy && proxyServer.proxyRequestPathTemplate != null) {
                var pathTemplate = proxyServer.proxyRequestPathTemplate!!
                pathTemplate = pathTemplate.replace("%host%", server.vHost)
                pathTemplate = pathTemplate.replace("%path%", "/${command}")
                uriBuilder.scheme(if (proxyServer.protocol == Server.ConnectionProtocol.HTTP) "http" else "https")
                uriBuilder.host(proxyServer.vHost)
                uriBuilder.port(proxyServer.port)
                uriBuilder.encodedPath(pathTemplate)
            }

            return uriBuilder.build()
        }
    }

    /**
     * Disposes of this object.
     */
    override fun close() {
        httpClient.connectionPool.evictAll()
    }

    /**
     * Downloads the depot manifest specified by the given manifest ID, and optionally decrypts the manifest's filenames if the depot decryption key has been provided.
     * @param depotId The id of the depot being accessed.
     * @param manifestId The unique identifier of the manifest to be downloaded.
     * @param manifestRequestCode The manifest request code for the manifest that is being downloaded.
     * @param server The content server to connect to.
     * @param depotKey The depot decryption key for the depot that will be downloaded.
     * This is used for decrypting filenames (if needed) in depot manifests.
     * @param proxyServer Optional content server marked as UseAsProxy which transforms the request.
     * @param cdnAuthToken CDN auth token for CDN content server endpoints if necessary. Get one with [SteamContentOld.getCDNAuthToken].
     * @return A [DepotManifest] instance that contains information about the files present within a depot.
     * @exception IllegalArgumentException [server] was null.
     * @exception IOException A network error occurred when performing the request.
     * @exception SteamKitWebRequestException A network error occurred when performing the request.
     * @exception DataFormatException When the data received is not as expected
     */
     suspend fun downloadManifest(
        depotId: Int,
        manifestId: Long,
        manifestRequestCode: Long,
        server: Server,
        depotKey: ByteArray? = null,
        proxyServer: Server? = null,
        cdnAuthToken: String? = null
    ): DepotManifest {
        require(server != null) { "server cannot be null" }

        val MANIFEST_VERSION = 5U
        val url = if (manifestRequestCode > 0) {
            "depot/$depotId/manifest/$manifestId/$MANIFEST_VERSION/$manifestRequestCode"
        } else {
            "depot/$depotId/manifest/$manifestId/$MANIFEST_VERSION"
        }

        val request = Request.Builder()
            .url(buildCommand(server, url, cdnAuthToken, proxyServer))
            .build()

        return withTimeout(requestTimeout) {
            val response = httpClient.newCall(request).execute()

            if (!response.isSuccessful) {
                throw SteamKitWebRequestException("Response status code does not indicate success: ${response.code} (${response.message})", response)
            }

            val depotManifest = withTimeout(responseBodyTimeout) {

                val contentLength = response.header("Content-Length")?.toIntOrNull()
//                val buffer = contentLength?.let { ByteArray(it) }

                if (contentLength == null) {
                    logger.debug("Manifest response does not have Content-Length, falling back to unbuffered read.")
                }

                val inputStream = response.body.byteStream()

                ByteArrayOutputStream().use { ms ->
                    val bytesRead = inputStream.copyTo(ms, contentLength ?: DEFAULT_BUFFER_SIZE)
                    if (bytesRead != contentLength?.toLong()) {
                        throw DataFormatException("Length mismatch after downloading depot manifest! (was $bytesRead, but should be $contentLength)")
                    }
                    val contentBytes = ms.toByteArray()
                    ZipInputStream(contentBytes.inputStream()).use { zip ->
                        var entryCount = 0
                        while (zip.nextEntry != null) {
                            entryCount++
                        }
                        if (entryCount > 1) {
                            logger.debug("Expected the zip to contain only one file")
                        }
                    }
                    // Decompress the zipped manifest data
                    ZipInputStream(contentBytes.inputStream()).use { zip ->
                        DepotManifest.deserialize(zip).first
                    }
                }
            }

            depotKey?.let { key ->
                // if we have the depot key, decrypt the manifest filenames
                depotManifest.decryptFilenames(key)
            }

            depotManifest
        }
    }

    /**
     * Downloads the specified depot chunk, and optionally processes the chunk and verifies the checksum if the depot decryption key has been provided.
     * This function will also validate the length of the downloaded chunk with the value of [ChunkData.compressedLength],
     * if it has been assigned a value.
     * @param depotId The id of the depot being accessed.
     * @param chunk A [ChunkData] instance that represents the chunk to download.
     * This value should come from a manifest downloaded with [downloadManifest].
     * @param server The content server to connect to.
     * @param destination The buffer to receive the chunk data. If [depotKey] is provided, this will be the decompressed buffer.
     * Allocate or rent a buffer that is equal or longer than [ChunkData.uncompressedLength]
     * @param depotKey The depot decryption key for the depot that will be downloaded.
     * This is used to process the chunk data.
     * @param proxyServer Optional content server marked as UseAsProxy which transforms the request.
     * @param cdnAuthToken CDN auth token for CDN content server endpoints if necessary. Get one with [SteamContent.getCDNAuthToken].
     * @return The total number of bytes written to [destination].
     * @exception IllegalArgumentException Thrown if the chunk's [ChunkData.chunkID] was null or if the [destination] buffer is too small.
     * @exception IllegalStateException Thrown if the downloaded data does not match the expected length.
     * @exception SteamKitWebRequestException A network error occurred when performing the request.
     */
    suspend fun downloadDepotChunk(
        depotId: Int,
        chunk: ChunkData,
        server: Server,
        destination: ByteArray,
        depotKey: ByteArray? = null,
        proxyServer: Server? = null,
        cdnAuthToken: String? = null
    ): Int {
        require(server != null) { "server cannot be null" }
        require(chunk != null) { "chunk cannot be null" }
        require(destination != null) { "destination cannot be null" }

        if (chunk.chunkID == null) {
            throw IllegalArgumentException("Chunk must have a ChunkID.")
        }

        if (depotKey == null) {
            if (destination.size < chunk.compressedLength) {
                throw IllegalArgumentException("The destination buffer must be longer than the chunk CompressedLength (since no depot key was provided).")
            }
        } else {
            if (destination.size < chunk.uncompressedLength) {
                throw IllegalArgumentException("The destination buffer must be longer than the chunk UncompressedLength.")
            }
        }

        val chunkID = Utils.encodeHexString(chunk.chunkID)
        val url = "depot/$depotId/chunk/$chunkID"

        val request = Request.Builder()
            .url(buildCommand(server, url, cdnAuthToken, proxyServer))
            .build()

        return withTimeout(requestTimeout) {
            val response = httpClient.newCall(request).execute()

            if (!response.isSuccessful) {
                throw SteamKitWebRequestException("Response status code does not indicate success: ${response.code} (${response.message})", response)
            }

            var contentLength = chunk.compressedLength

            response.header("Content-Length")?.toLongOrNull()?.let { responseContentLength ->
                contentLength = responseContentLength.toInt()

                // assert that lengths match only if the chunk has a length assigned.
                if (chunk.compressedLength > 0 && contentLength != chunk.compressedLength) {
                    throw IllegalStateException("Content-Length mismatch for depot chunk! (was $contentLength, but should be ${chunk.compressedLength})")
                }
            } ?: run {
                if (contentLength > 0) {
                    logger.debug("Response does not have Content-Length, falling back to chunk.compressedLength.")
                } else {
                    throw SteamKitWebRequestException("Response does not have Content-Length and chunk.compressedLength is not set.", response)
                }
            }

            // If no depot key is provided, stream into the destination buffer without renting
            if (depotKey == null) {
                val bytesRead = response.body.byteStream().use { input ->
                    input.read(destination, 0, contentLength)
                }

                if (bytesRead != contentLength) {
                    throw IOException("Length mismatch after downloading depot chunk! (was $bytesRead, but should be $contentLength)")
                }

                return@withTimeout contentLength
            }

            // We have to stream into a temporary buffer because a decryption will need to be performed
            val buffer = ByteArray(contentLength)

            try {
                val bytesRead = response.body.byteStream().use { input ->
                    input.read(buffer, 0, contentLength)
                }

                if (bytesRead != contentLength) {
                    throw IOException("Length mismatch after downloading depot chunk! (was $bytesRead, but should be $contentLength)")
                }

                // process the chunk immediately
                DepotChunk.process(chunk, buffer, destination, depotKey)
            } catch (ex: Exception) {
                logger.debug("Failed to download a depot chunk ${request.url}: ${ex.message}")
                throw ex
            }
        }
    }
}
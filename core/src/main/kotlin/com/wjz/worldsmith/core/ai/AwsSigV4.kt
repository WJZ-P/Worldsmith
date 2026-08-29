package com.wjz.worldsmith.core.ai

import java.net.URI
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.HexFormat
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * AWS Signature Version 4, enough of it to call Bedrock.
 *
 * Bedrock is the one provider that cannot be reached with a bearer token, so
 * this exists rather than pulling in the AWS SDK for a single POST.
 *
 * The canonical URI is the request path as written. The spec asks for a second
 * round of encoding for non-S3 services, but that only matters for paths
 * containing characters which had to be escaped in the first place; a Bedrock
 * model id is made of letters, digits, `.`, `-` and `:`, all of which are legal
 * in a path segment. This is the one part of the signature that cannot be
 * checked without a live call.
 */
object AwsSigV4 {
    private const val ALGORITHM = "AWS4-HMAC-SHA256"
    private const val TERMINATOR = "aws4_request"
    private val AMZ_DATE = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)
    private val DATE_STAMP = DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC)

    /**
     * Returns [headers] plus the signature headers.
     *
     * `host` is signed but not returned: the HTTP client sets it itself and
     * refuses to have it set by hand.
     */
    fun signedHeaders(
        method: String,
        url: URI,
        headers: Map<String, String>,
        payload: String,
        accessKeyId: String,
        secretAccessKey: String,
        region: String,
        service: String,
        timestamp: Instant,
    ): Map<String, String> {
        if (accessKeyId.isBlank() || secretAccessKey.isBlank()) {
            throw LlmException("AWS Bedrock needs both an access key id and a secret access key")
        }

        val amzDate = AMZ_DATE.format(timestamp)
        val dateStamp = DATE_STAMP.format(timestamp)

        val toSign = sortedMapOf<String, String>()
        headers.forEach { (name, value) -> toSign[name.lowercase()] = value.trim() }
        toSign["host"] = url.host
        toSign["x-amz-date"] = amzDate

        val canonicalHeaders = toSign.entries.joinToString("") { (name, value) -> "$name:$value\n" }
        val signedHeaderNames = toSign.keys.joinToString(";")

        val canonicalRequest = listOf(
            method,
            url.rawPath.ifEmpty { "/" },
            url.rawQuery.orEmpty(),
            canonicalHeaders,
            signedHeaderNames,
            hex(sha256(payload)),
        ).joinToString("\n")

        val scope = "$dateStamp/$region/$service/$TERMINATOR"
        val stringToSign = listOf(ALGORITHM, amzDate, scope, hex(sha256(canonicalRequest))).joinToString("\n")

        var key = "AWS4$secretAccessKey".toByteArray(Charsets.UTF_8)
        for (part in listOf(dateStamp, region, service, TERMINATOR)) {
            key = hmac(key, part)
        }
        val signature = hex(hmac(key, stringToSign))

        return headers + mapOf(
            "x-amz-date" to amzDate,
            "authorization" to "$ALGORITHM Credential=$accessKeyId/$scope, " +
                "SignedHeaders=$signedHeaderNames, Signature=$signature",
        )
    }

    private fun sha256(value: String): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))

    private fun hmac(key: ByteArray, value: String): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(value.toByteArray(Charsets.UTF_8))
    }

    private fun hex(bytes: ByteArray): String = HexFormat.of().formatHex(bytes)
}

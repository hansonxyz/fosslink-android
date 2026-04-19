/*
 * SPDX-FileCopyrightText: 2026 Brian Hanson
 * SPDX-License-Identifier: MIT
 */
package xyz.hanson.fosslink.sms

import java.io.ByteArrayOutputStream
import java.util.UUID

/**
 * Builds raw MMS SendReq PDUs conforming to OMA-WAP-MMS-ENC v1.2.
 * Constructs the binary format expected by SmsManager.sendMultimediaMessage()
 * and parseable by Android's internal PduParser.
 *
 * PDU layout:
 *   [MMS Headers]
 *     X-Mms-Message-Type: m-send-req
 *     X-Mms-Transaction-Id: <uuid>
 *     X-Mms-MMS-Version: 1.2
 *     From: insert-address-token
 *     To: <phone/TYPE=PLMN>
 *     Content-Type: application/vnd.wap.multipart.mixed  (MUST be last header)
 *   [Multipart Body]
 *     nparts (uintvar)
 *     For each part: headersLen, dataLen, headers, data
 */
class MmsPduBuilder {

    data class MmsAttachment(
        val fileName: String,
        val mimeType: String,
        val data: ByteArray
    )

    fun buildSendReq(
        recipientNumbers: List<String>,
        textBody: String?,
        attachments: List<MmsAttachment>
    ): ByteArray {
        val out = ByteArrayOutputStream()
        writeHeaders(out, recipientNumbers)
        writeMultipartBody(out, textBody, attachments)
        return out.toByteArray()
    }

    private fun writeHeaders(out: ByteArrayOutputStream, recipientNumbers: List<String>) {
        // X-Mms-Message-Type: m-send-req
        out.write(HEADER_MESSAGE_TYPE)
        out.write(MESSAGE_TYPE_SEND_REQ)

        // X-Mms-Transaction-Id: <uuid>\0
        out.write(HEADER_TRANSACTION_ID)
        out.write(UUID.randomUUID().toString().substring(0, 8).toByteArray(Charsets.US_ASCII))
        out.write(0)

        // X-Mms-MMS-Version: 1.2
        out.write(HEADER_MMS_VERSION)
        out.write(MMS_VERSION_1_2)

        // From: insert-address-token (tells MMSC to fill in sender address)
        out.write(HEADER_FROM)
        out.write(0x01) // Value-length: 1 byte follows
        out.write(0x81) // Insert-address-token

        // To: one header per recipient (OMA-WAP-MMS-ENC allows multiple To headers)
        for (number in recipientNumbers) {
            out.write(HEADER_TO)
            out.write(formatPhoneNumber(number).toByteArray(Charsets.US_ASCII))
            out.write(0)
        }

        // Content-Type: application/vnd.wap.multipart.mixed
        // MUST be the last header. Using Content-general-form with value-length
        // so PduParser can handle it consistently.
        out.write(HEADER_CONTENT_TYPE)
        out.write(0x01) // Value-length: 1 byte follows
        out.write(CT_MULTIPART_MIXED or 0x80) // well-known short-integer
    }

    private fun writeMultipartBody(
        out: ByteArrayOutputStream,
        textBody: String?,
        attachments: List<MmsAttachment>
    ) {
        var nparts = attachments.size
        if (!textBody.isNullOrEmpty()) nparts++

        writeUintvar(out, nparts)

        if (!textBody.isNullOrEmpty()) {
            writeTextPart(out, textBody)
        }

        for ((index, att) in attachments.withIndex()) {
            writeMediaPart(out, att, index)
        }
    }

    private fun writeTextPart(out: ByteArrayOutputStream, text: String) {
        val data = text.toByteArray(Charsets.UTF_8)

        // Build part headers
        val headers = ByteArrayOutputStream()

        // Content-Type: text/plain; charset=utf-8
        // Using Content-general-form: value-length, media-type, charset-param
        headers.write(0x03) // Value-length: 3 bytes follow
        headers.write(CT_TEXT_PLAIN or 0x80)  // text/plain as short-integer
        headers.write(0x81) // Well-known parameter: charset
        headers.write(0xEA.toInt()) // UTF-8 charset (MIBenum 106 as short-integer: 106|0x80=0xEA)

        // Content-Location: text_0.txt
        writeContentLocation(headers, "text_0.txt")

        val headerBytes = headers.toByteArray()
        writeUintvar(out, headerBytes.size)
        writeUintvar(out, data.size)
        out.write(headerBytes)
        out.write(data)
    }

    private fun writeMediaPart(out: ByteArrayOutputStream, att: MmsAttachment, index: Int) {
        val contentLocation = att.fileName.ifEmpty { "attachment_$index" }

        val headers = ByteArrayOutputStream()

        // Content-Type
        val wellKnown = WELL_KNOWN_CONTENT_TYPES[att.mimeType.lowercase()]
        if (wellKnown != null) {
            headers.write(wellKnown or 0x80)
        } else {
            // Extension-media: text string encoding
            headers.write(att.mimeType.toByteArray(Charsets.US_ASCII))
            headers.write(0)
        }

        // Content-Location
        writeContentLocation(headers, contentLocation)

        val headerBytes = headers.toByteArray()
        writeUintvar(out, headerBytes.size)
        writeUintvar(out, att.data.size)
        out.write(headerBytes)
        out.write(att.data)
    }

    private fun writeContentLocation(out: ByteArrayOutputStream, location: String) {
        out.write(HEADER_CONTENT_LOCATION)
        out.write(location.toByteArray(Charsets.US_ASCII))
        out.write(0)
    }

    private fun formatPhoneNumber(number: String): String {
        val cleaned = number.replace(Regex("[^+0-9]"), "")
        return "$cleaned/TYPE=PLMN"
    }

    companion object {
        // MMS header field IDs (OMA-WAP-MMS-ENC Table 8)
        private const val HEADER_MESSAGE_TYPE = 0x8C
        private const val HEADER_TRANSACTION_ID = 0x98
        private const val HEADER_MMS_VERSION = 0x8D
        private const val HEADER_FROM = 0x89
        private const val HEADER_TO = 0x97
        private const val HEADER_CONTENT_TYPE = 0x84

        // Part header field IDs
        private const val HEADER_CONTENT_LOCATION = 0x8E

        // Message type values
        private const val MESSAGE_TYPE_SEND_REQ = 0x80

        // MMS version: 1.2 = (1 << 4) | 2 = 0x12, as short-integer: 0x12 | 0x80 = 0x92
        private const val MMS_VERSION_1_2 = 0x92

        // Well-known content types (WAP-203 Table 40)
        private const val CT_MULTIPART_MIXED = 0x23
        private const val CT_TEXT_PLAIN = 0x03

        private val WELL_KNOWN_CONTENT_TYPES = mapOf(
            "image/jpeg" to 0x1F,
            "image/jpg" to 0x1F,
            "image/png" to 0x20,
            "image/gif" to 0x1D,
            "image/bmp" to 0x21,
            "video/mp4" to 0x32,
            "video/3gpp" to 0x35,
            "audio/amr" to 0x31,
            "audio/mpeg" to 0x2B,
        )

        fun writeUintvar(out: ByteArrayOutputStream, value: Int) {
            if (value < 0x80) {
                out.write(value)
                return
            }
            val bytes = mutableListOf<Int>()
            var v = value
            bytes.add(v and 0x7F)
            v = v shr 7
            while (v > 0) {
                bytes.add((v and 0x7F) or 0x80)
                v = v shr 7
            }
            for (b in bytes.reversed()) {
                out.write(b)
            }
        }
    }
}

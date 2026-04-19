/*
 * SPDX-FileCopyrightText: 2026 Brian Hanson
 * SPDX-License-Identifier: MIT
 */
package xyz.hanson.fosslink.sms

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.telephony.SmsManager
import android.util.Base64
import android.util.Log
import androidx.core.content.FileProvider
import org.json.JSONArray
import org.json.JSONObject
import xyz.hanson.fosslink.network.ProtocolMessage
import xyz.hanson.fosslink.service.ConnectionService
import java.io.File

/**
 * Handles SMS/MMS send requests from the desktop.
 * Receives fosslink.sms.send, sends via Android SmsManager,
 * and reports status back via fosslink.sms.send_status.
 *
 * When attachments are present, builds an MMS PDU and uses
 * SmsManager.sendMultimediaMessage(). Otherwise uses sendTextMessage().
 */
class SmsSendHandler(private val context: Context) {
    private val TAG = "SmsSendHandler"
    private val handler = Handler(Looper.getMainLooper())

    fun handleMessage(msg: ProtocolMessage, send: (ProtocolMessage) -> Unit) {
        val messageBody = msg.body.optString("messageBody", "")
        val queueId = msg.body.optString("queueId", "")
        val attachmentsJson = msg.body.optJSONArray("attachments")

        // Accept phoneNumbers (array) or fall back to phoneNumber (single string)
        val recipients: List<String> = msg.body.optJSONArray("phoneNumbers")
            ?.let { arr -> (0 until arr.length()).map { arr.getString(it) }.filter { it.isNotEmpty() } }
            ?: msg.body.optString("phoneNumber", "").takeIf { it.isNotEmpty() }?.let { listOf(it) }
            ?: emptyList()

        if (recipients.isEmpty()) {
            Log.w(TAG, "Send missing phoneNumber/phoneNumbers")
            sendStatus(send, queueId, "failed", "Missing phone number")
            return
        }

        val hasAttachments = attachmentsJson != null && attachmentsJson.length() > 0

        if (hasAttachments || recipients.size > 1) {
            // MMS: required for attachments and for group sends
            sendMms(recipients, messageBody, attachmentsJson ?: JSONArray(), queueId, send)
        } else {
            if (messageBody.isEmpty()) {
                Log.w(TAG, "Send SMS missing messageBody")
                sendStatus(send, queueId, "failed", "Missing message body")
                return
            }
            sendSms(recipients[0], messageBody, queueId, send)
        }
    }

    private fun sendSms(
        phoneNumber: String,
        messageBody: String,
        queueId: String,
        send: (ProtocolMessage) -> Unit
    ) {
        Log.i(TAG, "Sending SMS to $phoneNumber (${messageBody.length} chars, queueId=$queueId)")

        try {
            val smsManager = context.getSystemService(SmsManager::class.java)

            val parts = smsManager.divideMessage(messageBody)
            if (parts.size == 1) {
                smsManager.sendTextMessage(phoneNumber, null, messageBody, null, null)
            } else {
                smsManager.sendMultipartTextMessage(phoneNumber, null, parts, null, null)
            }

            Log.i(TAG, "SMS sent to $phoneNumber (${parts.size} part(s))")
            sendStatus(send, queueId, "sent")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send SMS to $phoneNumber", e)
            sendStatus(send, queueId, "failed", e.message)
        }
    }

    private fun sendMms(
        recipients: List<String>,
        messageBody: String,
        attachmentsJson: org.json.JSONArray,
        queueId: String,
        send: (ProtocolMessage) -> Unit
    ) {
        val phoneNumber = recipients.joinToString(", ")
        Log.i(TAG, "Sending MMS to $phoneNumber (${attachmentsJson.length()} attachment(s), " +
                "text=${messageBody.length} chars, queueId=$queueId)")

        try {
            // 1. Parse and decode attachments
            val attachments = mutableListOf<MmsPduBuilder.MmsAttachment>()
            for (i in 0 until attachmentsJson.length()) {
                val att = attachmentsJson.getJSONObject(i)
                val fileName = att.optString("fileName", "attachment_$i")
                val mimeType = att.optString("mimeType", "application/octet-stream")
                val base64Data = att.optString("base64EncodedFile", "")
                if (base64Data.isEmpty()) {
                    Log.w(TAG, "Skipping attachment $i: empty data")
                    continue
                }
                val data = Base64.decode(base64Data, Base64.DEFAULT)
                Log.i(TAG, "Attachment $i: $fileName ($mimeType, ${data.size} bytes)")
                attachments.add(MmsPduBuilder.MmsAttachment(fileName, mimeType, data))
            }

            if (attachments.isEmpty() && messageBody.isEmpty()) {
                Log.w(TAG, "MMS has no content (no attachments, no text)")
                sendStatus(send, queueId, "failed", "No content to send")
                return
            }

            // 2. Build MMS PDU
            val pduBytes = MmsPduBuilder().buildSendReq(
                recipientNumbers = recipients,
                textBody = messageBody.ifEmpty { null },
                attachments = attachments
            )
            Log.i(TAG, "Built MMS PDU: ${pduBytes.size} bytes, ${attachments.size} attachment(s)")

            // 3. Write PDU to temp file
            val mmsDir = File(context.cacheDir, "mms")
            mmsDir.mkdirs()
            val pduFile = File(mmsDir, "send_$queueId.pdu")
            pduFile.writeBytes(pduBytes)

            // 4. Get content:// URI via FileProvider
            val pduUri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                pduFile
            )

            // 5. Create PendingIntent for send result
            val action = "xyz.hanson.fosslink.MMS_SENT_$queueId"
            val sentIntent = Intent(action)
            val sentPI = PendingIntent.getBroadcast(
                context,
                queueId.hashCode(),
                sentIntent,
                PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
            )

            // 6. Register result receiver with timeout
            var resultHandled = false
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(ctx: Context, intent: Intent) {
                    if (resultHandled) return
                    resultHandled = true

                    val rc = resultCode
                    if (rc == Activity.RESULT_OK) {
                        Log.i(TAG, "MMS sent successfully to $phoneNumber (queueId=$queueId)")
                        sendStatus(send, queueId, "sent")
                        // Trigger event detection so all connected desktops receive the sent
                        // message event immediately (don't rely on ContentObserver timing).
                        ConnectionService.instance?.smsEventHandler?.triggerDetection()
                    } else {
                        Log.w(TAG, "MMS send failed to $phoneNumber (resultCode=$rc, queueId=$queueId)")
                        sendStatus(send, queueId, "failed", "MMS send failed (code $rc)")
                    }
                    cleanup()
                }

                fun cleanup() {
                    try { context.unregisterReceiver(this) } catch (_: Exception) {}
                    pduFile.delete()
                }
            }

            // Timeout: 60 seconds — unregister receiver and report failure if no result
            val timeoutRunnable = Runnable {
                if (!resultHandled) {
                    resultHandled = true
                    Log.w(TAG, "MMS send timed out for $phoneNumber (queueId=$queueId)")
                    sendStatus(send, queueId, "failed", "MMS send timed out")
                    receiver.cleanup()
                }
            }
            handler.postDelayed(timeoutRunnable, MMS_SEND_TIMEOUT_MS)

            // RECEIVER_EXPORTED is required: the system MMS service broadcasts the
            // PendingIntent result from a different process. The action includes queueId
            // so it's unique and not spoofable in practice.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(receiver, IntentFilter(action), Context.RECEIVER_EXPORTED)
            } else {
                context.registerReceiver(receiver, IntentFilter(action))
            }

            // 7. Send MMS
            val smsManager = context.getSystemService(SmsManager::class.java)
            smsManager.sendMultimediaMessage(
                context,
                pduUri,
                null, // locationUrl
                null, // configOverrides
                sentPI
            )

            Log.i(TAG, "MMS send initiated to $phoneNumber (queueId=$queueId)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send MMS to $phoneNumber", e)
            sendStatus(send, queueId, "failed", e.message)
        }
    }

    private fun sendStatus(send: (ProtocolMessage) -> Unit, queueId: String, status: String, error: String? = null) {
        val body = JSONObject().apply {
            if (queueId.isNotEmpty()) put("queueId", queueId)
            put("status", status)
            if (error != null) put("error", error)
        }
        send(ProtocolMessage(ProtocolMessage.TYPE_SMS_SEND_STATUS, body))
    }

    companion object {
        private const val MMS_SEND_TIMEOUT_MS = 60_000L
    }
}

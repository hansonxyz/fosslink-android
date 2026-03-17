# FossLink Protocol Specification

## Overview

XYZConnect v2 uses WebSocket for all communication between the desktop client (server) and the Android companion app (client). The protocol is JSON-based with binary frame support for file transfers.

- **Transport**: WebSocket (RFC 6455) over TLS
- **Discovery**: UDP broadcast on port 1716
- **Server port**: 8716 (WebSocket)
- **Message format**: JSON text frames
- **Attachment format**: Binary frames with structured header
- **Keepalive**: WebSocket ping/pong (built-in)

## 1. Discovery

The desktop broadcasts a UDP packet on port 1716 (same port as KDE Connect) to `255.255.255.255` from each non-virtual network interface.

### Discovery Packet

```json
{
  "type": "xyzconnect.discovery",
  "deviceId": "74d1ee3c08a918c977a208305b7f18ac",
  "deviceName": "My Desktop",
  "wsPort": 8716,
  "clientVersion": "0.2.0"
}
```

Newline-terminated JSON, sent as a single UDP datagram.

### Discovery Flow

1. Desktop broadcasts discovery packet every 5 seconds while in DISCOVERING state
2. Phone receives broadcast, extracts `wsPort` and sender IP
3. Phone constructs WebSocket URL: `wss://<sender_ip>:<wsPort>`
4. Phone initiates WebSocket connection

### Per-Interface Broadcasting

The desktop broadcasts from each non-virtual interface separately. Container interfaces (`docker`, `veth`, `virbr`, `br-`) and loopback are excluded. VPN interfaces (`tun`, `wg`) are included but typically ineffective (point-to-point, no broadcast peers).

### Direct Connect

For VPN, Tailscale, or cross-subnet setups, the phone can be configured with a direct `wss://` URL, bypassing UDP discovery entirely.

## 2. WebSocket Connection

### Connection Establishment

1. Phone connects to `wss://<desktop_ip>:8716`
2. Desktop accepts the connection
3. Both sides immediately exchange identity messages (JSON text frames)
4. Connection is established — ready for pairing or authenticated communication

### TLS

The WebSocket server uses a self-signed TLS certificate. On first connection (pairing), the phone accepts the certificate and stores its fingerprint (TOFU — Trust On First Use). On subsequent connections, the phone verifies the certificate fingerprint matches the stored value.

The phone does NOT present a client certificate. Authentication is handled at the application layer via the pairing mechanism.

### Connection Lifecycle

```
Phone ──WSS──> Desktop
  │                │
  ├─ identity ────>│
  │<──── identity ─┤
  │                │
  │  (if not paired)
  ├─ pair_request ─>│
  │<── pair_code ──┤
  ├─ pair_confirm ─>│
  │<── pair_accept ─┤
  │                │
  │  (authenticated)
  │<── sync_start ─┤
  ├─ sync_batch ──>│
  │    ...         │
```

## 3. Message Format

All protocol messages are JSON objects sent as WebSocket text frames:

```json
{
  "type": "xyzconnect.<message_type>",
  "body": { ... }
}
```

No `id` field (unlike KDE Connect packets). No newline delimiter (WebSocket frames are self-delimiting). No capability negotiation (both sides are always XYZConnect).

### Message Types

| Type | Direction | Purpose |
|------|-----------|---------|
| `xyzconnect.identity` | Both | Device identification |
| `xyzconnect.pair_request` | Phone → Desktop | Initiate pairing |
| `xyzconnect.pair_code` | Desktop → Phone | Send 6-digit verification code |
| `xyzconnect.pair_confirm` | Phone → Desktop | Confirm code matches |
| `xyzconnect.pair_accept` | Desktop → Phone | Pairing accepted |
| `xyzconnect.pair_reject` | Both | Pairing rejected |
| `xyzconnect.unpair` | Both | Remove pairing |
| `xyzconnect.sms.sync_start` | Desktop → Phone | Start SMS sync |
| `xyzconnect.sms.sync_batch` | Phone → Desktop | Batch of messages |
| `xyzconnect.sms.sync_complete` | Phone → Desktop | Sync finished |
| `xyzconnect.sms.sync_ack` | Desktop → Phone | Acknowledge batch |
| `xyzconnect.sms.event` | Phone → Desktop | Real-time SMS event |
| `xyzconnect.sms.event_ack` | Desktop → Phone | Acknowledge events |
| `xyzconnect.sms.send` | Desktop → Phone | Send SMS/MMS |
| `xyzconnect.sms.send_status` | Phone → Desktop | Send result |
| `xyzconnect.sms.mark_read` | Desktop → Phone | Mark thread read |
| `xyzconnect.sms.delete` | Desktop → Phone | Delete message |
| `xyzconnect.sms.delete_thread` | Desktop → Phone | Delete thread |
| `xyzconnect.contacts.sync` | Desktop → Phone | Request contacts |
| `xyzconnect.contacts.batch` | Phone → Desktop | Batch of contacts |
| `xyzconnect.contacts.complete` | Phone → Desktop | Contacts sync done |
| `xyzconnect.contacts.photo` | Phone → Desktop | Contact photo (binary payload follows) |
| `xyzconnect.battery` | Phone → Desktop | Battery status |
| `xyzconnect.findmyphone` | Desktop → Phone | Ring the phone |
| `xyzconnect.clipboard` | Both | Clipboard content |
| `xyzconnect.notification` | Phone → Desktop | Phone notification |
| `xyzconnect.notification.dismiss` | Desktop → Phone | Dismiss notification |
| `xyzconnect.url.share` | Both | Share URL |
| `xyzconnect.settings` | Phone → Desktop | Phone settings/state |

## 4. Identity Exchange

Sent by both sides immediately after WebSocket connection is established.

```json
{
  "type": "xyzconnect.identity",
  "body": {
    "deviceId": "74d1ee3c08a918c977a208305b7f18ac",
    "deviceName": "My Desktop",
    "deviceType": "desktop",
    "clientVersion": "0.2.0"
  }
}
```

Fields:
- `deviceId`: 32-character hex string (generated once, stored persistently)
- `deviceName`: Human-readable device name
- `deviceType`: One of `desktop`, `laptop`, `phone`, `tablet`
- `clientVersion`: Semver version string

No capability lists. No protocol version negotiation. Both sides always speak the same protocol.

## 5. Pairing

### Pairing Flow

Pairing uses a shared-secret verification. The desktop generates a 6-digit code, displays it on screen, and sends it to the phone. The user visually compares the codes on both devices and confirms.

```
Phone                              Desktop
  │                                   │
  ├── pair_request ──────────────────>│
  │   { deviceId, deviceName }        │
  │                                   │  Desktop generates 6-digit code
  │                                   │  Desktop shows code on screen
  │<──────────────── pair_code ───────┤
  │   { code: "847291" }              │
  │                                   │
  │  Phone shows code on screen       │
  │  User confirms codes match        │
  │                                   │
  ├── pair_confirm ──────────────────>│
  │   { code: "847291" }              │
  │                                   │  Desktop verifies code matches
  │<──────────────── pair_accept ─────┤
  │   { deviceId, deviceName }        │
  │                                   │
```

### Pairing Policy (Android)

The phone only accepts incoming pairing flows when ALL of these are true:
1. App is in the foreground (MainActivity is resumed)
2. One of:
   - Setup wizard is active
   - No paired devices exist
   - User explicitly navigated to "Pair New Device" from Settings

All other pairing attempts are ignored (WebSocket connection closed without responding).

### Persistent Pairing

After successful pairing:
- Desktop stores: `{ deviceId, deviceName, certFingerprint }` in `known-devices.json`
- Phone stores: `{ deviceId, deviceName, certFingerprint }` in SharedPreferences

On reconnection, the phone verifies the desktop's TLS certificate fingerprint matches the stored value (TOFU). If it doesn't match, the connection is rejected and the user is prompted to re-pair.

### Unpairing

Either side can send `xyzconnect.unpair`. The recipient removes the device from its trusted store and closes the WebSocket connection.

```json
{
  "type": "xyzconnect.unpair",
  "body": { "deviceId": "74d1ee3c08a918c977a208305b7f18ac" }
}
```

### Pairing Timeout

If no `pair_confirm` is received within 30 seconds of sending `pair_code`, the desktop cancels the pairing and closes the connection.

## 6. SMS Sync Protocol

### Cursor-Based Incremental Sync

The desktop maintains a sync cursor: `lastSyncTimestamp` (millisecond epoch). On first sync, the cursor is 0 (full sync). After each sync, the cursor advances to the latest message timestamp.

### Sync Flow

```
Desktop                             Phone
  │                                   │
  ├── sync_start ────────────────────>│
  │   { lastSyncTimestamp: 1708300000 }│
  │                                   │  Phone queries SMS/MMS content
  │                                   │  providers for date > cursor
  │<──────────────── sync_batch ──────┤
  │   { messages[], batchIndex: 0,    │
  │     totalBatches: 12 }            │
  ├── sync_ack ──────────────────────>│
  │   { batchIndex: 0 }              │
  │<──────────────── sync_batch ──────┤
  │   { messages[], batchIndex: 1,    │
  │     totalBatches: 12 }            │
  │   ...                             │
  │<──────────────── sync_complete ───┤
  │   { messageCount: 583,            │
  │     latestTimestamp: 1708400000 } │
  │                                   │
```

### Message Format (within sync_batch)

Each message in the `messages[]` array:

```json
{
  "_id": 12345,
  "thread_id": 42,
  "address": "+15551234567",
  "body": "Hello!",
  "date": 1708300500000,
  "type": 1,
  "read": 1,
  "sub_id": -1,
  "event": 1,
  "attachments": []
}
```

Fields mirror Android's SMS content provider columns:
- `_id`: Message ID (unique per phone)
- `thread_id`: Conversation thread ID
- `address`: Phone number (E.164 preferred, but carrier-dependent)
- `body`: Message text (empty for MMS-only)
- `date`: Millisecond epoch timestamp
- `type`: 1=received, 2=sent, 3=draft, 4=outbox, 5=failed, 6=queued
- `read`: 0=unread, 1=read
- `sub_id`: SIM subscription ID (-1 for default)
- `event`: 1=SMS, 2=MMS
- `attachments`: Array of attachment metadata (see Attachments section)

### Conversation Metadata

Each batch also includes conversation summaries:

```json
{
  "thread_id": 42,
  "addresses": ["+15551234567"],
  "snippet": "Hello!",
  "date": 1708300500000,
  "read": 1,
  "unread_count": 0
}
```

### Batch Size

Default: 50 messages per batch. Configurable on the phone side.

### Interrupted Sync

If the connection drops during sync:
- The desktop's `lastSyncTimestamp` has NOT been updated (only updated on `sync_complete`)
- On reconnect, desktop sends the same cursor — sync restarts from the same point
- Messages already received are handled via idempotent upserts (no duplicates)

### Safety Timeout

If no batch arrives within 300 seconds of `sync_start`, the desktop considers the sync stalled, transitions to READY, and stores the current timestamp as the sync cursor.

### Auto-Recovery

If sync completes but the database is empty (indicates a wiped DB with a stale cursor), the desktop clears the cursor to 0 and triggers a full re-sync. This recovery is attempted only once per session.

## 7. Real-Time Events

After initial sync, the phone pushes real-time events for changes to the SMS database.

### Event Types

| Event | Trigger | Body |
|-------|---------|------|
| `received` | New incoming SMS/MMS | `{ messages[] }` |
| `sent` | Message sent from phone | `{ messages[] }` |
| `read` | Thread marked as read | `{ threadId }` |
| `deleted` | Messages deleted | `{ messageIds[] }` |
| `thread_deleted` | Thread deleted | `{ threadId }` |

### Event Format

```json
{
  "type": "xyzconnect.sms.event",
  "body": {
    "event": "received",
    "eventId": "550e8400-e29b-41d4-a716-446655440000",
    "timestamp": 1708300500000,
    "messages": [{ ... }]
  }
}
```

### Reliable Delivery

- Each event has a unique UUID `eventId`
- Phone maintains a persistent event queue (SharedPreferences-backed)
- Desktop acknowledges events in batches: `{ eventIds: ["uuid1", "uuid2", ...] }`
- On reconnect, phone replays all unacknowledged events
- Queue bounded: max 1000 events, 7-day TTL

### Ack Batching

Desktop batches acks with 100ms debounce:

```json
{
  "type": "xyzconnect.sms.event_ack",
  "body": { "eventIds": ["uuid1", "uuid2", "uuid3"] }
}
```

### Idempotency

Desktop processes all message events via idempotent upserts. Receiving the same event twice (e.g., replay after reconnect) is harmless.

## 8. Send SMS/MMS

### Send SMS

```json
{
  "type": "xyzconnect.sms.send",
  "body": {
    "requestId": "req-uuid-123",
    "addresses": ["+15551234567"],
    "body": "Hello from desktop!",
    "subId": -1
  }
}
```

### Send Status

```json
{
  "type": "xyzconnect.sms.send_status",
  "body": {
    "requestId": "req-uuid-123",
    "status": "sent",
    "messageId": 12346
  }
}
```

Status values: `queued`, `sending`, `sent`, `failed`

### Send MMS (with attachments)

For MMS with attachments, the desktop sends the message metadata as a text frame, immediately followed by binary frames for each attachment:

```json
{
  "type": "xyzconnect.sms.send",
  "body": {
    "requestId": "req-uuid-456",
    "addresses": ["+15551234567", "+15559876543"],
    "body": "Check out this photo",
    "attachments": [
      {
        "transferId": "xfer-uuid-001",
        "mimeType": "image/jpeg",
        "fileName": "photo.jpg",
        "fileSize": 245760
      }
    ]
  }
}
```

Binary frames for the attachment follow immediately (see section 9).

## 9. Binary Transfers (Attachments)

Attachments and contact photos are transferred as WebSocket binary frames. Each binary frame has a 40-byte header:

### Binary Frame Header

```
Bytes 0-15:   transferId (UUID, 16 bytes, big-endian)
Bytes 16-19:  chunkIndex (uint32, big-endian)
Bytes 20-23:  totalChunks (uint32, big-endian)
Bytes 24-27:  chunkSize (uint32, big-endian)
Bytes 28-31:  totalSize (uint32, big-endian)
Bytes 32-39:  reserved (zero-filled)
Bytes 40+:    chunk data
```

### Chunk Size

Default chunk size: 64 KB. Transfers larger than 64 KB are split into multiple binary frames.

### Attachment Download

Desktop requests an attachment:

```json
{
  "type": "xyzconnect.sms.request_attachment",
  "body": {
    "transferId": "xfer-uuid-002",
    "messageId": 12345,
    "partId": "0",
    "uniqueIdentifier": "content://mms/part/67890"
  }
}
```

Phone responds with binary frames containing the attachment data, tagged with the same `transferId`.

### Contact Photo Transfer

Same binary frame mechanism, triggered by `xyzconnect.contacts.photo` message:

```json
{
  "type": "xyzconnect.contacts.photo",
  "body": {
    "transferId": "xfer-uuid-003",
    "uid": "contact-uid-123",
    "mimeType": "image/jpeg"
  }
}
```

Followed by binary frames with the photo data.

## 10. Desktop → Phone Commands

### Mark Thread Read

```json
{
  "type": "xyzconnect.sms.mark_read",
  "body": { "threadId": 42 }
}
```

### Delete Message

```json
{
  "type": "xyzconnect.sms.delete",
  "body": { "messageId": 12345 }
}
```

### Delete Thread

```json
{
  "type": "xyzconnect.sms.delete_thread",
  "body": { "threadId": 42 }
}
```

These commands require root on the Android side (only the default SMS app can modify the SMS content provider). The phone's `rootEnabled` setting controls whether these are executed.

## 11. Contacts Sync

### Request Contacts

```json
{
  "type": "xyzconnect.contacts.sync",
  "body": { "lastSyncTimestamp": 0 }
}
```

### Contact Batch

```json
{
  "type": "xyzconnect.contacts.batch",
  "body": {
    "contacts": [
      {
        "uid": "contact-uid-123",
        "name": "John Doe",
        "phoneNumbers": ["+15551234567", "+15559876543"],
        "emails": ["john@example.com"],
        "photoHash": "sha256:abc123..."
      }
    ],
    "batchIndex": 0,
    "totalBatches": 3
  }
}
```

### Contact Photos

After contact sync, desktop compares photo hashes against cached photos. For new/changed photos, it requests them individually. Photos are transferred as binary frames (see section 9).

## 12. Battery Status

Phone sends battery status on connect and on change:

```json
{
  "type": "xyzconnect.battery",
  "body": {
    "currentCharge": 85,
    "isCharging": true
  }
}
```

## 13. Notifications

Phone forwards notifications:

```json
{
  "type": "xyzconnect.notification",
  "body": {
    "id": "notification-id-123",
    "appName": "Gmail",
    "title": "New email",
    "text": "You have a new message from...",
    "time": 1708300500000,
    "dismissable": true,
    "silent": false
  }
}
```

Desktop can dismiss:

```json
{
  "type": "xyzconnect.notification.dismiss",
  "body": { "id": "notification-id-123" }
}
```

## 14. Other Features

### Find My Phone

```json
{
  "type": "xyzconnect.findmyphone",
  "body": {}
}
```

Phone plays alarm at max volume, flashes screen.

### Clipboard Sync

```json
{
  "type": "xyzconnect.clipboard",
  "body": { "content": "copied text here" }
}
```

### URL Share

```json
{
  "type": "xyzconnect.url.share",
  "body": { "url": "https://example.com" }
}
```

### Settings

Phone sends settings/state on connect and on change:

```json
{
  "type": "xyzconnect.settings",
  "body": {
    "rootEnabled": true,
    "storageTotalBytes": 128000000000,
    "storageFreeBytes": 45000000000
  }
}
```

## 15. Reconnection

### Phone-Side Reconnection

When the WebSocket connection drops:
1. Wait 1 second, then attempt reconnect
2. On failure, exponential backoff: 1s, 2s, 4s, 8s, 16s, 30s (max)
3. Add random jitter (0-25% of interval) to prevent thundering herd
4. On reconnect success, exchange identity, verify cert fingerprint
5. If paired, resume normal operation (event replay, re-sync if needed)

### Desktop-Side Recovery

When the desktop detects a closed WebSocket:
1. Transition to DISCONNECTED, then DISCOVERING
2. Resume UDP broadcasting
3. Also attempt direct WebSocket connect to known phone IP (dual approach for VPN)
4. Reconnect timer: every 15 seconds

### Resume After Reconnect

- Events: Phone replays all unacknowledged events from its persistent queue
- Sync: Desktop sends `sync_start` with its current cursor (picks up where it left off)
- No special resume handshake needed — the cursor-based sync and event queue handle it

## 16. Error Handling

### WebSocket Close Codes

| Code | Meaning | Action |
|------|---------|--------|
| 1000 | Normal close | Reconnect |
| 1001 | Going away | Reconnect |
| 1006 | Abnormal close | Reconnect with backoff |
| 4000 | Unpaired | Remove device, stop reconnecting |
| 4001 | Version incompatible | Show upgrade prompt |
| 4002 | Auth failed | Re-pair required |

### Invalid Messages

Messages that fail JSON parse or have an unknown `type` are logged and ignored. The connection is NOT closed for invalid messages (robustness principle).

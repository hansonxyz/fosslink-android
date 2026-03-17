# FossLink Implementation Plan

Each phase is a self-contained unit of work. Every phase ends with a live test on real hardware (Pixel 7 + Windows desktop).

---

## Phase 1: Foundation — WebSocket Peering

Get the two sides talking over WebSocket with identity exchange and pairing. No SMS features yet — just the transport layer.

### Desktop: Replace Network Layer

**Delete:**
- `src/network/tls.ts` — TLS cert management (WebSocket handles this)
- `src/protocol/standard/` — entire directory (no dual-mode adapters)
- `src/protocol/adapter-factory.ts` — adapter selection logic
- `src/protocol/protocol-adapter.ts` — adapter interface

**Rewrite:**
- `src/network/connection-manager.ts` → `src/network/ws-server.ts`
  - WebSocket server on port 8716 using `ws` library
  - Accept connections, track connected clients
  - Send/receive JSON text frames
  - Handle binary frames (for future attachment transfers)
  - TLS via self-signed cert (generated on first run, stored in data dir)

- `src/network/discovery.ts` → `src/network/ws-discovery.ts`
  - UDP broadcast on port 1716 (same port)
  - Discovery packet: `{ type, deviceId, deviceName, wsPort, clientVersion }`
  - Per-interface broadcasting (same logic as v1, filter container interfaces)

- `src/network/packet.ts` — simplify message format
  - Remove KDE Connect packet format (`id`, newline-delimited)
  - Messages are `{ type, body }` JSON objects
  - Remove capability lists, protocol version
  - Keep message type constants (rename from `kdeconnect.*` to `xyzconnect.*`)

- `src/protocol/pairing-handler.ts` — shared-secret pairing
  - Generate 6-digit code on desktop
  - Display code in GUI (via IPC notification)
  - Send `pair_code` to phone
  - Receive `pair_confirm` with matching code
  - Store paired device in `known-devices.json` with cert fingerprint

- `src/core/daemon.ts` — rewire for WebSocket
  - Replace TCP/TLS connection flow with WebSocket
  - Remove protocol v7/v8 identity exchange (both sides send identity as first WS message)
  - Remove dual-mode adapter creation
  - Simplify reconnection (WebSocket close → reconnect timer)

**Keep unchanged:**
- `src/core/state-machine.ts` — same states, same transitions
- `src/core/errors.ts` — same error codes
- `src/database/` — schema and queries unchanged
- `src/ipc/` — IPC contract unchanged
- `src/config/` — config and known-devices unchanged
- `src/utils/logger.ts`, `paths.ts`, `semver.ts`
- `gui/` — entire GUI unchanged

**GUI changes (minimal):**
- Hide features not yet implemented behind a check (send button, find-my-phone, etc.)
- These get re-enabled phase by phase
- Connection/pairing UI works as-is (state machine drives it)

**Tests:**
- Update `tests/unit/network/` for WebSocket server
- Update `tests/unit/protocol/` for new pairing
- Keep all `tests/unit/database/`, `tests/unit/ipc/`, `tests/unit/core/` tests

### Android: New Project Shell

**Create `reference/xyzconnect-android-2/` with:**
- Gradle project: Kotlin, Jetpack Compose, Material 3
- `applicationId`: `xyz.hanson.xyzconnect`
- `compileSdk`: 36, `targetSdk`: 35, `minSdk`: 26
- Dependencies: OkHttp (WebSocket client), Kotlin Coroutines, AndroidX

**Port from v1:**
- `MainActivity.kt` — single activity with Compose `setContent`
- `KdeTheme.kt` → `XyzTheme.kt` — Material 3 / Material You theme
- `XyzNavHost.kt` — navigation with wizard + main flows
- `NavRoutes.kt`, `BottomNavBar.kt` — navigation components
- `HomeScreen.kt`, `SettingsScreen.kt`, `AboutScreen.kt`
- `StepIndicator.kt`, `WelcomeStep.kt`, `PermissionsStep.kt`, `DiscoveryStep.kt`, `PairStep.kt`, `SuccessStep.kt`
- `XyzConnectViewModel.kt` — ViewModel (adapt for new WebSocket connection)
- `ConnectionState.kt`, `PermissionItem.kt`
- `PairingPolicy.kt` — pairing security

**New code:**
- `WebSocketClient.kt` — OkHttp WebSocket, connect/disconnect/send/receive
- `DiscoveryListener.kt` — UDP broadcast listener on port 1716
- `ConnectionService.kt` — foreground service maintaining WebSocket connection
- `PairingManager.kt` — shared-secret pairing flow
- `DeviceStore.kt` — paired device persistence (SharedPreferences)

### Live Test Criteria

- [ ] Desktop starts, broadcasts UDP discovery on port 1716
- [ ] Phone receives broadcast, shows desktop in discovery screen
- [ ] Phone connects to desktop via WebSocket
- [ ] Both sides exchange identity messages
- [ ] Pairing flow: 6-digit code displayed on both devices
- [ ] User confirms codes match, pairing succeeds
- [ ] Pairing persisted — on reconnect, auto-authenticates
- [ ] Connection maintained (WebSocket ping/pong)
- [ ] Disconnect detection — desktop shows disconnected, phone shows disconnected
- [ ] Reconnect — phone reconnects automatically after drop
- [ ] Unpair — either side can unpair, removes trusted device
- [ ] Desktop `npm run test` passes
- [ ] Desktop `npm run build` passes
- [ ] `cd gui && npm run build:win` passes
- [ ] Android APK builds and deploys

---

## Phase 2: SMS Sync

Port the batch sync protocol. Desktop requests messages since last cursor, phone queries content providers and sends batches.

### Desktop

**Move `src/protocol/enhanced/` → `src/protocol/`:**
- `enhanced-sync-handler.ts` → `sync-handler.ts` (remove "enhanced" naming)
- Adapt to send/receive via WebSocket instead of `socket.write(serializePacket(...))`
- Keep cursor logic, batch processing, safety timeout, auto-recovery

**Port from `src/protocol/standard/sms-handler.ts`:**
- `handleMessages()` — idempotent upsert logic
- Attachment metadata handling (two-phase MMS delivery)
- Conversation/message database writes

### Android

**Port from v1 `XyzSyncPlugin.kt`:**
- `SyncHandler.kt` — receive `sync_start`, query SMS/MMS content providers, batch serialize, send batches
- Use `SMSHelper.kt` query logic (port from v1)
- Conversation metadata generation
- Batch size: 50 messages

### Live Test Criteria

- [ ] Desktop sends `sync_start { lastSyncTimestamp: 0 }` after pairing
- [ ] Phone queries all SMS/MMS, sends batches
- [ ] Desktop persists conversations and messages to SQLite
- [ ] Conversation list appears in GUI
- [ ] Message thread displays correctly
- [ ] Incremental sync: disconnect/reconnect, only new messages sync
- [ ] Full re-sync: `sms.resync_all` IPC command works

---

## Phase 3: Real-Time Events ✅

Push SMS/MMS changes in real time. No persistent queue — WebSocket handles delivery while connected, desktop resyncs on reconnect.

### Desktop (already implemented)

- `event-handler.ts` receives `xyzconnect.sms.event` via WebSocket
- Routes by event type: received, sent, read, deleted, thread_deleted
- Sends `xyzconnect.sms.event_ack` (batched, 100ms debounce)
- Fires IPC `sms.event` notifications to GUI
- Handles `xyzconnect.settings` for phone capability status

### Android ✅

**New:**
- `SmsEventHandler.kt` — ContentObserver + state diffing + command handling
  - Observes `content://mms-sms/conversations?simple=true`
  - Detects new messages (timestamp comparison)
  - Detects read/deleted/thread_deleted (set diffing against baseline state)
  - Sends events immediately via WebSocket send callback
  - Handles desktop commands: `mark_read`, `delete`, `delete_thread`
  - Pushes `xyzconnect.settings` on connect (storage info)

**Modified:**
- `SmsReader.kt` — added query methods for state diffing (getNewestMessageTimestamp, getAllThreadIds, getUnreadThreadIds, getMessageIdsByThread)
- `ProtocolMessage.kt` — added event/command/settings message type constants
- `ConnectionService.kt` — creates SmsEventHandler
- `MainViewModel.kt` — routes incoming commands, starts/stops event handler on connect/disconnect

**Design decisions:**
- No EventQueue, no acks, no replay. WebSocket guarantees delivery while connected. Desktop resyncs on reconnect.
- Event handler takes a `send` callback — caller provides it. Today that's single WebSocket. Multi-desktop = broadcast to all connections.
- ContentObserver registered only while connected+paired. Unregistered on disconnect.
- Desktop commands (mark_read, delete) are best-effort — may fail without default SMS app permission.

### Live Test Criteria

- [x] Send SMS to phone → appears on desktop instantly
- [x] Send SMS from phone → appears on desktop instantly
- [x] Mark thread as read on phone → desktop updates
- [x] Delete message on phone → desktop removes it
- [x] Disconnect → reconnect → desktop does full resync
- [x] Settings push on connect (storage info)

---

## Phase 4: Send SMS + Battery Status ✅

### Send SMS ✅

**Desktop (already implemented):**
- `sms.send` IPC → `xyzconnect.sms.send` WebSocket message
- Send queue with optimistic display

**Android ✅:**
- `SmsSendHandler.kt` — receives `xyzconnect.sms.send`, uses `SmsManager`
- Handles multipart messages via `divideMessage()` + `sendMultipartTextMessage()`
- Sends `xyzconnect.sms.send_status` back with `sent`/`failed`

### Battery Status ✅

**Desktop (already implemented):**
- Receives `xyzconnect.battery` with `currentCharge`/`isCharging`
- GUI battery indicator driven by IPC notification
- Sends `xyzconnect.battery.request` on connect

**Android ✅:**
- `BatteryHandler.kt` — BroadcastReceiver for `ACTION_BATTERY_CHANGED`
- Sends `xyzconnect.battery` on connect and on charge/charging state change
- Responds to `xyzconnect.battery.request` with current state
- Deduplicates (only sends on actual change)

### Attachments (already implemented in Phase 2)

- `AttachmentHandler.kt` handles `xyzconnect.sms.request_attachment`
- Reads from MMS content provider, sends base64-encoded data
- Desktop downloads, stores, and displays in GUI

### Live Test Criteria

- [x] Type message on desktop, send → arrives on recipient's phone
- [x] Battery level shows in desktop GUI
- [x] Plug/unplug charger → charging state updates
- [x] MMS attachments download and display

---

## Phase 5: Call/Dial, URL Sharing, Find My Phone, Root Mode ✅

### Contacts ✅ (implemented in Phase 2)

Contact sync already works — batch sync, photo download, photo caching by hash.

### Call/Dial ✅

**Desktop:**
- Call icon button in message thread header
- Phone numbers in messages are clickable
- `phone.dial` IPC → `xyzconnect.telephony.dial` WebSocket message
- Confirmation dialog: "Call {number} on your phone?" OK/Cancel
- `tel:` protocol handler registered — desktop is OS handler for tel: links
- tel: links trigger the same confirmation dialog

**Android:**
- `TYPE_DIAL` handler opens `ACTION_DIAL` intent with number pre-filled
- Wakes screen via `PowerManager.FULL_WAKE_LOCK`

### URL Sharing ✅

**Desktop → Phone:**
- `url.share` IPC sends `xyzconnect.url.share` (already implemented)
- `UrlHandler.kt` receives URL, opens via root (`am start`) or notification with fullScreenIntent

**Phone → Desktop:**
- `ShareActivity.kt` — Android share sheet integration for `text/plain`
- Extracts URL from shared text, sends `xyzconnect.url.share` to desktop
- Desktop opens URL in browser via `shell.openExternal()` (already implemented)

### Find My Phone ✅

**Android:**
- `FindMyPhoneHandler.kt` — MediaPlayer with USAGE_ALARM at max volume + vibration
- 5-minute safety timeout, saves/restores previous volume
- Modern Compose dismiss activity (Tile-inspired):
  - Dark background with pulsing radar ring animation (3 concentric animated circles)
  - Phone icon in center, "Tap anywhere to stop"
  - Shows on lock screen, wakes screen
- Notification with "Found It" action via `FindMyPhoneReceiver`

**Desktop:**
- `phone.ring` IPC → `xyzconnect.findmyphone` (already implemented)

### Root Mode ✅

**Android:**
- Root Integration toggle in Settings screen (SharedPreferences `root_integration`)
- `rootEnabled` sent in `xyzconnect.settings` on connect
- Used by UrlHandler for direct URL opening

**Desktop:**
- Receives `rootEnabled` in settings, stores in state machine context (already implemented)

### Live Test Criteria

- [x] Contact names and photos display in conversation list
- [x] Battery level shows in status bar with charging indicator
- [x] Click call icon → phone dialer opens with number
- [x] Click phone number in message → confirmation → dialer opens
- [x] tel: link → confirmation → dialer opens on phone
- [x] "Ring Phone" → phone plays alarm, modern dismiss screen
- [x] Share URL from desktop → opens on phone
- [x] Share URL from phone → opens in desktop browser
- [x] Root toggle in settings → rootEnabled sent to desktop

---

## Phase 6: Docs, Deploy, Release

### Update Project Documentation

- Rewrite `CLAUDE.md` — remove all KDE Connect protocol references, document WebSocket architecture
- Update `PROJECT.md` — new architecture overview
- Update `docs/PROTOCOL_ARCHITECTURE.md` — remove dual-mode adapter docs
- Remove or archive `reference/kdeconnect-android/IMPLEMENTATION_PLAN.md` (v1 plan, historical)
- Write `reference/xyzconnect-android-2/CLAUDE.md` — Android v2 development guide

### Deployment

- Version bump (likely `0.2.0` — new protocol, breaking change)
- GitHub deployment: `./deploy-github.sh`
- Release build: `./publish.sh`
- Android APK: upload to GitHub Releases

### Cleanup

- Archive v1 Android code (stays in `reference/kdeconnect-android/`, add README noting it's archived)
- Remove dead code from desktop (any remaining KDE Connect references)
- Update CI/CD if applicable

---

## Architecture Notes

### What Changes

| Area | v1 | v2 |
|------|-----|-----|
| Transport | TCP + TLS (role-inverted) | WebSocket over TLS |
| Discovery | UDP identity packet (KDE Connect format) | UDP JSON packet (XYZConnect format) |
| Pairing | RSA certificate exchange | Shared-secret (6-digit code) + TOFU |
| Message format | `{ id, type, body }\n` | `{ type, body }` (WebSocket frame) |
| Attachments | Out-of-band TLS payload channel | In-band WebSocket binary frames |
| Protocol modes | Standard (KDE) + Enhanced (XYZ) | XYZConnect only |
| Adapters | StandardAdapter + EnhancedAdapter | No adapter layer |
| Android app | Fork of KDE Connect (~19K lines) | Clean-room (~3-5K lines) |
| License | GPL v2/v3 | MIT |

### What Stays the Same

- State machine (INIT → DISCONNECTED → DISCOVERING → ... → READY)
- Database schema (conversations, messages, attachments, contacts, notifications, sync_state)
- IPC contract (all JSON-RPC methods + push notifications)
- GUI (Svelte 5 stores, components, routing)
- Config format and data directory structure
- Sync protocol semantics (cursor-based, batch, ack, auto-recovery)
- Event protocol semantics (UUID eventIds, real-time push)
- Reconnection strategy (dual approach: broadcast + direct connect)

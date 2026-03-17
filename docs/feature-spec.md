# FossLink Feature Specification

## Overview

XYZConnect is a cross-platform desktop SMS/MMS client. The Android companion app runs on the user's phone and bridges SMS/MMS to the desktop client over a WebSocket connection on the local network.

**Desktop client**: Node.js daemon + Electron GUI (Windows, Linux, macOS)
**Android companion**: Kotlin + Jetpack Compose

## 1. SMS/MMS Messaging

### Conversation List

- Display all SMS/MMS conversations from the phone
- Show contact name (or phone number if no contact), last message snippet, timestamp
- Unread indicator for conversations with unread messages
- Sort by most recent message
- Search conversations by contact name or message content
- Spam filter: hide threads from unknown numbers with no outgoing messages (unless they contain verification codes)

### Message Thread

- Display full message history for a conversation
- Incoming messages (type 1) and outgoing messages (type 2) visually distinct
- Timestamp labels for message groups (Today, Yesterday, date)
- Group MMS: show sender name/number for each message
- Unread messages highlighted until thread is opened

### Send SMS

- Compose and send SMS from the desktop
- Phone number input with contact autocomplete
- New conversation creation
- Send queue with optimistic display (show message immediately, update status when confirmed)
- Send status: queued → sending → sent / failed
- Retry failed sends

### Send MMS

- Compose and send MMS with attachments from the desktop
- File picker for images, videos, files
- Show attached files as thumbnails before send
- Multiple recipients (group MMS)
- Subject line (optional)
- Carrier-dependent size limits (typically 1-3 MB)

### Attachments

- Display inline image/video attachments in message thread
- Download attachments on demand (not pre-fetched)
- Thumbnail generation for image attachments
- Lightbox viewer for full-size images
- Download to disk option

### Real-Time Updates

- New incoming messages appear instantly (no polling)
- Sent messages from phone appear instantly
- Read status syncs from phone (thread marked as read on phone → desktop updates)
- Deleted messages/threads sync from phone
- Taskbar flash notification for new messages

### Desktop Commands

- Mark thread as read on desktop → syncs to phone (requires root)
- Delete message from desktop → syncs to phone (requires root)
- Delete thread from desktop → syncs to phone (requires root)

## 2. Contact Sync

- Sync all contacts from phone to desktop
- Contact name, phone numbers, emails
- Contact photos (thumbnail for list, full-res on demand)
- Incremental sync (only changed contacts re-synced)
- Contact search by name or phone number

## 3. Battery Status

- Display phone battery level in desktop status bar
- Charging indicator (lightning bolt icon)
- Updates on connect and on change

## 4. Find My Phone

- Button on desktop to ring the phone
- Phone plays alarm at max volume (overrides silent mode)
- Phone flashes screen white + toggles flashlight
- Plays until dismissed via button on phone screen

## 5. Clipboard Sync

- Copy on phone → paste on desktop (and vice versa)
- Toggle on/off in settings
- Automatic (no manual trigger needed)

## 6. Notification Mirroring

- Phone notifications forwarded to desktop as native desktop notifications
- Show app name, title, text
- Dismiss notification on desktop → dismisses on phone
- Per-app filter (enable/disable specific apps)

## 7. URL Sharing

- Share URL from desktop → opens on phone browser
- Share URL from phone → opens on desktop browser

## 8. Setup & Pairing

### First-Time Setup Wizard (Android)

Step-by-step wizard:
1. **Welcome**: App branding and purpose
2. **Permissions**: Request SMS, contacts, notifications, battery optimization exemption
3. **Discovery**: Show discovered desktops on the network
4. **Pairing**: Display verification code, user confirms on both devices
5. **Success**: Setup complete, navigate to main screen

### Pairing Security

- 6-digit verification code displayed on both devices
- User visually confirms codes match
- TOFU (Trust On First Use): certificate fingerprint stored on first pair
- Subsequent connections verify certificate fingerprint
- Pairing only accepted when app is in foreground and in pairing mode

### Multi-Device

- Phone can pair with multiple desktops
- Each desktop maintains independent sync state and event queue
- Phone-side plugins are instantiated per-device

### Unpair

- Available from Settings on both sides
- Removes trusted device, closes connection, wipes local data on desktop

## 9. Settings

### Desktop Settings

- Device name (editable)
- Theme (system, light, dark)
- Notification sound (on/off)
- Taskbar flash for new messages (on/off)
- Spam filter (on/off)
- Auto-check for updates (on/off)
- Sidebar width (draggable)

### Android Settings

- Device name (editable)
- Root integration toggle (for mark-read and delete commands)
- Battery optimization exemption
- Network settings (direct IP connect)

## 10. Connection States

The desktop daemon has an explicit state machine:

```
INIT → DISCONNECTED → DISCOVERING → PAIRING → CONNECTED → SYNCING → READY
                                                                       ↓
                                                                  DISCONNECTED
                                                                  (reconnect)
```

The GUI displays the current state:
- **Disconnected**: "Looking for your phone..."
- **Discovering**: "Searching for devices..."
- **Pairing**: Pairing UI with verification code
- **Connected/Syncing**: "Syncing messages..." with progress
- **Ready**: Conversation list and messaging UI
- **Error**: Error message with retry option

### Reconnection

- Automatic reconnection on connection drop
- Exponential backoff (1s → 30s max)
- Cached data displayed immediately from database (no waiting for phone connection)
- Quick access to recent messages even when phone is offline

## 11. Data Persistence

### Desktop

- SQLite database for messages, conversations, contacts, notifications
- Database is a cache — can be rebuilt from phone sync
- Schema auto-hash: database wiped and rebuilt if schema changes
- Sync cursor persisted for incremental sync

### Android

- SharedPreferences for paired devices, settings, event queue
- No message database — reads directly from Android content providers
- Event queue persisted for reliable delivery across reconnects

## 12. Platform Support

### Desktop

- **Windows**: NSIS installer, auto-updater via GitHub Releases
- **Linux**: AppImage + .deb packages
- **macOS**: DMG (requires building on macOS)

### Android

- **Minimum SDK**: 26 (Android 8.0)
- **Target SDK**: 35 (Android 15)
- **Distribution**: GitHub Releases (APK), potentially F-Droid

## 13. Non-Features (Explicitly Out of Scope)

- No phone call handling (receive/make calls from desktop)
- No file transfer (beyond MMS attachments)
- No remote control (mousepad, keyboard, presenter)
- No media control (MPRIS)
- No KDE Connect backwards compatibility
- No multi-phone support on desktop (single phone connection)

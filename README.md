# LightBackup

Simple server-side Fabric mod for Minecraft 26.2 with automatic scheduled backups and optional Google Drive upload.

## Features

- **Scheduled backups** — configurable interval (default: every 30 minutes)
- **Manual backups** — `/backup` command with subcommands
- **Google Drive upload** — OAuth2 authentication, auto-upload after each backup
- **Rate limiting** — configurable upload bandwidth and compression throttling
- **Backup retention** — automatic pruning of old backups
- **Configurable** — all settings via JSON config files

## Requirements

- Minecraft 26.2
- Fabric Loader 0.19.3+
- Fabric API

## Installation

1. Download `lightbackup-1.0.0.jar` from [Releases](https://github.com/tobni-jpg/lightbackup/releases)
2. Place the jar in your server's `mods/` folder
3. Start the server — config files are created automatically in `config/`

## Commands

All commands require permission level 4 (operator).

| Command | Description |
|---------|-------------|
| `/backup` | Create a backup now |
| `/backup now` | Same as above |
| `/backup list` | List all existing backups |
| `/backup reload` | Reload config from disk |
| `/backup gdrive-setup CLIENT_ID CLIENT_SECRET` | Set up Google Drive credentials |
| `/backup gdrive-auth CODE` | Authenticate with Google Drive |
| `/backup gdrive-upload` | Upload latest backup to Google Drive |

## Configuration

### `config/lightbackup.json`

```json
{
  "enabled": true,
  "intervalMinutes": 30,
  "backupDirectory": "backups",
  "maxBackups": 10,
  "announceToPlayers": true,
  "autoUpload": false,
  "compressionSleepMs": 5,
  "rateLimitUploadMBPerSec": 10.0
}
```

| Field | Type | Default | Description |
|-------|------|---------|-------------|
| `enabled` | boolean | `true` | Enable/disable scheduled backups |
| `intervalMinutes` | int | `30` | Minutes between scheduled backups |
| `backupDirectory` | string | `"backups"` | Directory for backup files (relative to server root) |
| `maxBackups` | int | `10` | Maximum number of backups to keep (0 = unlimited) |
| `announceToPlayers` | boolean | `true` | Broadcast backup status to all players |
| `autoUpload` | boolean | `false` | Auto-upload backups to Google Drive after creation |
| `compressionSleepMs` | int | `5` | Milliseconds to sleep per 8KB buffer during compression (0 = no throttling) |
| `rateLimitUploadMBPerSec` | double | `10.0` | Maximum upload speed in MB/s (0 = unlimited) |

### `config/lightbackup-gdrive.json`

```json
{
  "clientId": "",
  "clientSecret": "",
  "refreshToken": "",
  "folderId": "",
  "enabled": false
}
```

## Google Drive Setup

1. Go to [Google Cloud Console](https://console.cloud.google.com/)
2. Create a new project (or select an existing one)
3. Enable the **Google Drive API** in the API Library
4. Go to **Credentials** → **Create Credentials** → **OAuth 2.0 Client ID**
5. Application type: **Desktop app**
6. Copy the **Client ID** and **Client Secret**
7. On your Minecraft server, run:
   ```
   /backup gdrive-setup YOUR_CLIENT_ID YOUR_CLIENT_SECRET
   ```
8. Open the displayed URL in your browser
9. Authorize the application and copy the `code` parameter from the redirect URL
10. Run:
    ```
    /backup gdrive-auth THE_CODE_YOU_COPIED
    ```
11. Set `"autoUpload": true` in `config/lightbackup.json` to auto-upload future backups

To upload to a specific Drive folder, set the `folderId` field in `config/lightbackup-gdrive.json`.

## Building from Source

```bash
./gradlew build
```

The built jar will be in `build/libs/lightbackup-1.0.0.jar`.

## License

MIT

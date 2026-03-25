# Samaritan Client

This is the public, client-only version of Samaritan as a Fabric mod.

The private server, admin panel, backend auth code, database files, and deployment scripts are intentionally not included in this repository.

## Client Features

- In-game login screen
- Connect and disconnect from a Samaritan server
- Save the server host, port, and HTTP/HTTPS setting
- Persist your login token locally for reconnects
- Track teammates connected through Samaritan
- Show teammate position info in-game
- Show out-of-range directional indicators
- Send client chat messages through Samaritan
- Run ping checks from the client
- Change your own password from the client
- Configure arrow distance mode and highway filtering

## How To Use

### Fabric mod

1. Build the mod:

```bash
./gradlew build
```

2. Put the built jar from `build/libs/` into your Fabric mods folder.

3. Start Minecraft with Fabric.

4. Open the Samaritan login screen with the default keybind `K`.

5. Enter:

- server host
- server port
- your username
- your password

6. Press `Login`.

### Commands

The client also provides commands:

- `/samaritan login [username] [password]`
- `/samaritan logout`
- `/samaritan server <host> <port> [http|https]`
- `/samaritan status`
- `/samaritan token`
- `/samaritan players`
- `/samaritan pos <user>`
- `/samaritan chat <message>`
- `/samaritan ping`
- `/samaritan passwd <currentPassword> <newPassword>`

You can also use `/s` as the short alias.

## Build

Build the mod:

```bash
./gradlew build
```

## Repository Scope

Included here:

- Fabric mod source
- root Gradle wrapper and build files

Not included here:

- `server/`
- backend credentials or secrets
- backend database files
- server deployment scripts

# AdvancedReports

A GUI report system for Paper 1.21.11. Reports and cooldowns are stored in MySQL. The project targets Java 21 and works with an unmodified Minecraft client.

## Build

Install JDK 21 and Maven, then run:

```sh
mvn clean package
```

The ready-to-install plugin will be `target/advanced-reports-1.0.0.jar`. HikariCP and MySQL Connector/J are bundled in the jar.

## Install

1. Create a MySQL database and a user with permission to create and update tables in it.
2. Put the plugin jar in the server's `plugins` directory and start Paper once.
3. Set the MySQL host, database, username and password in `plugins/AdvancedReports/config.yml`.
4. Restart the server. The plugin creates its tables automatically.

## Commands

- `/report <player> [reason]` submits a report. Reasons are optional, allow spaces, and have a 20-character default limit. A missing reason is stored as empty and displayed as `ɴᴏ ʀᴇᴀꜱᴏɴ ᴘʀᴏᴠɪᴅᴇᴅ`.
- `/reports` opens the staff GUI.

Unknown players are rejected. Offline players can be reported when Paper has their profile cached or the plugin has recorded them joining the server. Players cannot report themselves. The same reporter cannot submit the same reason against the same player while that report exists; deleting it permits a later repeat. Successful reports are subject to the persistent 60-second reporter cooldown.

## Permissions

| Permission | Default | Access |
|---|---|---|
| `advancedreports.report` | Everyone | Submit reports |
| `advancedreports.view` | Operators | Open report lists and receive staff notifications |
| `advancedreports.actions` | Operators | Open report actions, copy names and teleport |
| `advancedreports.delete` | Operators | Delete reports |

## Configuration

Edit `plugins/AdvancedReports/config.yml` to configure MySQL, cooldown, free-text or preset reasons, date format and timezone, refresh interval, colors, and messages. Preset mode uses the listed reasons and still allows a report with no reason. Do not set the reason limit above 255 characters.

Default presets are Cheating, Griefing, Chat abuse, Spamming, Exploiting and Other.

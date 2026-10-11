# OpenArcade

Small, original minigame plugin for Paper 1.21.10 (Java 21). Games: Spleef, TNT Run, Block Party.

## Build
Requires JDK 21 and Maven.

    mvn package

The jar appears at `target/OpenArcade.jar`. Drop it in your server's `plugins` folder.

## Set up an arena
1. Stand on one corner of the floor and run `/arcade pos1`
2. Stand on the opposite corner and run `/arcade pos2`
   (floor height = the lower of the two Y values; players who fall 3 blocks below it are out)
3. Go to the waiting lobby and run `/arcade create <name> <spleef|tnt_run|block_party>`
   (the spot where you stand becomes the waiting spawn; change later with `/arcade setspawn <name>`)
4. Players join with `/arcade join <name>` and the game starts when `min-players` is reached.

## Commands
Players: `/arcade join <arena>`, `/arcade leave`, `/arcade list`, `/arcade menu`, `/arcade info`
Admins (`arcade.admin`): `pos1`, `pos2`, `create`, `setspawn`, `start`, `stop`, `delete`

Edit `plugins/OpenArcade/config.yml` for countdown, player limits and winner reward commands.
The plugin only places/removes its own floor blocks (snow, sandstone, wool) on the floor layer.

## Hotbar items and menus
- **Compass (Game Menu)**: given on join/respawn. Right-click to open a GUI listing every arena with its game, status and player count. Click an arena to join it.
- While waiting in an arena you also get a **Clock (Match Info)**, which shows the players and status of your match, and a **Red Dye (Leave Game)**.
- Set `lobby-item.give-on-join: false` in config.yml to stop the compass being handed out on join (the `/arcade menu` command still works).

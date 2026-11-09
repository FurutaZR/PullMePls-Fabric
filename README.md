# PullMePls: client-side pearl stasis bot (Fabric, Minecraft 26.3)

Author: **Ijau**

Run it on the bot account that stands next to your stasis chambers.

- A **registered** player types `$pull` in chat (public or whisper).
- If the bot is too far from their trapdoor it **walks there** first.
- It looks at the trapdoor and right-clicks it (**closes** it by default) so the pearl drops and the player teleports.
- After a short delay it clicks the trapdoor again to re-arm the chamber (optional).
- Optionally it **drops a fresh ender pearl** for the player once they arrive.
- Then it **walks back** to where it was standing.
- It knows **who owns each pearl** (shown above the pearl in-world and in the GUI) and only touches the trapdoor
  linked to the player who asked.
- Every reply the bot sends is **customizable**.
- **Right Shift** opens the liquid-glass config GUI. The key is rebindable in the GUI (and in Options > Controls).

## Build (Windows)

Needs JDK 25 (`java -version` must say 25). In the project folder:

```
gradlew.bat build
```

The mod is `build\libs\pullmepls-1.0.0.jar` (not the `-sources` jar). Put it in `.minecraft\mods` together with
Fabric API for 26.3 and run Minecraft with Fabric Loader 0.20.1 or newer.

All code lives in `src/client` because this template uses split client/common source sets.
Do not move it into `src/main`: the Minecraft client classes do not exist there.

## The GUI (Right Shift)

| Tab | What it does |
|---|---|
| General | owner labels, auto-register, **GUI key rebind** |
| Pull | trapdoor action, auto-reset + delay, look-at, reach, cooldown, require pearl, link radius |
| Walk | walk to the trapdoor, max distance, sprint, walk back afterwards |
| Give | drop pearls for the player: count, how long to wait, how close they must be |
| Chat | reply mode, what to listen to, whisper command, reply prefix, admins |
| Commands | the **prefix** and the **name(s) of every chat command** (see below) |
| Messages | edit every reply text (16 of them). Empty = the bot says nothing |
| Players | registered players: **Pull** (test), **Link** (trapdoor under crosshair), **Del**, Register |
| Pearls | every loaded pearl with its owner; **Assign** an owner by hand |

The switch at the bottom of the sidebar turns the whole bot on and off.

## Setting up a chamber

1. Put the bot near the chamber's trapdoor. Main hand empty, not sneaking.
2. Register the player: GUI > **Players** > type the name > **Register**
   (or an admin whispers/types `$register Name`, or enable *Auto-register pearl owners*).
3. Link their pearl to a trapdoor, any one of:
   - **Automatic:** the player throws the pearl into the chamber while they are in the bot's render distance.
     Once the pearl has sat still for 1 second, the bot links it to the nearest trapdoor (within *Link radius*).
   - **Look + Link:** look at the trapdoor, open the GUI, **Players** > **Link** next to the name.
   - **Pearls tab:** type the owner's name in the box, press **Assign** on that pearl (also links the nearest trapdoor).
4. The player logs off. Later they type `$pull`.

Links are saved, so after a relog the bot restores pearl owners from the registry.

## Chat commands (prefix and names are configurable)

These are the defaults. In GUI > **Commands** you can change the prefix and rename every command, and give a command
several names separated by commas (for example `pull, pullme, tp`). The old name stops working once you rename it.

| Command | Who | What |
|---|---|---|
| `$pull [n]` | registered players | release your pearl (`n` = which chamber if you have several) |
| `$help` | anyone | one-line help (rate limited) |
| `$register <name>` / `$unregister <name>` | admins + the bot account itself | manage the list |
| `$status` | admins + the bot account itself | counts |

Replies go out by whisper by default (max 1 message per second). Change it in GUI > Chat.

Command settings are saved in `config\pullmepls.json` under `prefix` and `commands`. If two commands end up with the
same name, the first one in the table above wins.

## Walking

The bot walks like a player (it presses forward, jump and sprint). Routes are found with A* over the blocks the client
has loaded. It can cross flat ground, jump up one block, drop down up to three blocks, and it avoids water, lava,
fire, cactus, magma, berry bushes, cobwebs, powder snow and portals. It does **not** open doors, swim, climb ladders
or bridge gaps. If no route exists (or the trapdoor is farther than *Max walk distance*) the pull is refused and the
player gets the "no path found" / "too far" message.

*Walk back afterwards* returns the bot to the block it stood on before it first walked away, once the queue is empty.

## Pearl refill

With *Drop a pearl for the player* on, the bot waits (up to *Wait for the player*) for the requester to appear within
*Player distance*, looks at them and drops ender pearls from its own inventory (main inventory, hotbar or offhand).
If it has none it sends the "out of pearls" message. Keep a stack in the bot's inventory.

## Reply texts

GUI > **Messages** (or `messages` in `config\pullmepls.json`). Placeholders: `{player}`, `{seconds}`, `{n}`,
`{reason}`, `{prefix}`, `{pull}` (the current name of the pull command). Leave a text empty to make the bot stay silent for that case. *Reply prefix* (Chat tab) is put in
front of every reply, e.g. `[Bot] `.

## Settings file

`.minecraft\config\pullmepls.json`. Everything in the GUI is saved there. Extra, JSON-only: **chat patterns**
(anchored regexes with `(?<name>...)` and `(?<msg>...)`, add one if your server uses a custom chat format; keep them
anchored to the start so nobody can fake a sender).

## Things to know

- Pearl owners are read from the pearl entity when it spawns, so the thrower has to be loaded on the bot's client at
  that moment. Pearls that were already in a chamber when the bot logged in get their owner from the saved registry,
  or from **Assign** in the GUI.
- Automatic linking never steals a trapdoor that already belongs to someone else.
- Pearl detection needs the pearl entity to be loaded: a chamber far beyond the server's entity range will not count
  as "pearl present" while *Require pearl in chamber* is on.
- Some servers forbid client-side automation (walking, dropping items, clicking blocks). Check the rules of yours.

## If the build reports errors

Paste the compiler output. The calls most likely to need a tweak for 26.3:

| File | Call |
|---|---|
| `Walker` | `Options#keyUp/keyJump/keySprint` + `KeyMapping#setDown`, `BlockState#getCollisionShape`, `Block#getDescriptionId` |
| `PullManager` | `ContainerInput.THROW` + `MultiPlayerGameMode#handleContainerInput`, `Player#inventoryMenu`, `ClientLevel#players` |
| `PullMePlsScreen` | `EditBox#setBordered`, `Screen#setFocused` |
| `PullMePlsClient` | lambda parameter lists of `ClientReceiveMessageEvents.CHAT/GAME` and `ClientEntityEvents.ENTITY_LOAD/UNLOAD` |

# ChatPilot v1.2.1

GrammaCrackers stream mod. YouTube live chat votes drive Grandma's character through Baritone while she AFKs. Built for the 24/7 subathon.

## What changed in 1.2.1 (hotfix)

Compile fix only. v1.2.0 used `PlayerInventory.getSelectedSlot()` which doesn't exist in yarn 1.21.4 mappings. Switched all four call sites to direct field access on `PlayerInventory.selectedSlot`. No behavior change. If you already pulled v1.2.0 just unzip this on top.

## What changed in 1.2.0

### Vote menu: Fishing replaces Wood
Slot 2 is now **Fish** instead of Chop wood. Reasoning: wood was overkilling resource accumulation (chests filling up with logs and saplings) without giving chat anything to look at. Fishing solves both at once. The bot finds water, casts the rod, and watches the bobber. When a fish bites the bobber dips, the rod yanks up the catch, and the counter ticks. Catches are mostly raw fish with the occasional treasure (saddle, name tag, enchanted book, lily pad), so chat gets surprise hype moments and the inventory stays light.

Setup: keep a fishing rod in Grandma's hotbar or main inventory. The task scans hotbar first, and if the rod is buried it auto-swaps the rod into hotbar slot 8 before casting. If no rod exists at all the task ends quickly so the next vote can pick something else.

The old wood aliases (`wood`, `chop`, `tree`, `log`, `axe`, etc) all map to fish now, so any chatter still typing the old keywords still casts a valid vote during the transition.

### Mining refocus to emerald, gold, coal
Priority order is now emerald, gold, coal. Iron, copper, lapis, and diamond are no longer actively pursued. Baritone still picks them up incidentally if they sit on the path. Quotas are deliberately low so the bot doesn't grind a single vein for an hour:
- 4 emeralds (rare and exciting on stream when one finally pops)
- 8 gold
- 16 coal

The explore-then-retry guard kicks the bot to fresh chunks if no emerald shows up quickly, then advances to gold, then coal. Worst case (no emeralds at all in this biome) the bot lands on coal in a few minutes and still accomplishes something.

### Bee safety in tree chopping
The wood task is preserved in the codebase as a backup option (not in the default vote menu, but easy to swap back into slot 2). It now scans for **beehive** and **bee_nest** blocks within 6 blocks every two seconds while logging. If any are found, the bot bails out of the current tree, hardResets Baritone, runs explore to walk away, then resumes elsewhere. Bee swarms wreck a 24/7 stream and the cost of skipping a tree is far smaller than the cost of getting Grandma stung to death on camera.

### Trash cactus
The bot now tosses junk items at a configurable cactus on the way home. Default trash list:

`cobblestone`, `cobbled_deepslate`, `dirt`, `coarse_dirt`, `rooted_dirt`, `grass_block`, `tuff`, `sand`, `red_sand`, `gravel`, `raw_copper`, `copper_ingot`, `lapis_lazuli`, `andesite`, `diorite`, `granite`, `wheat_seeds`, `melon_seeds`, `pumpkin_seeds`, `beetroot_seeds`, `torchflower_seeds`, `pitcher_pod`

The bot walks to the cactus, faces it, and throws each matching stack at it with a `THROW` slot click on the player's own screen handler (no GUI open needed). Cactus blocks destroy items they touch, so the trash never reaches the chest.

The cactus dump runs **before** the hopper drop. By the time the bot reaches the hopper, the only deposit-eligible items left are real keepers (raw iron, raw gold, emeralds, fish, treasures, etc).

Setup tip: place the cactus with a 2 to 3 block clear approach square so the bot stands a safe distance away when it aims and throws. The `gotoNear(cactus, 2)` lands the bot far enough that cactus damage isn't a concern, but the throw arc precision depends on the bot having a clean line of sight at the cactus block.

#### New commands
- `/trash` - look at a cactus block, run this, the cactus is now the trash drop point.
- `/trash here` - set the trash drop point to your current feet position.
- `/trash clear` - remove the trash drop point. The bot skips the cactus step until you set one again.
- `/trash where` - print the current trash drop point coordinates.

### New config keys
```
miningOreQuotaEmerald      = 4
miningOreQuotaGold         = 8
miningOreQuotaCoal         = 16

beehiveAvoidanceRadius     = 6

fishingCatchTarget         = 8
fishingWaterScanRadius     = 16
fishingMaxWaitTicks        = 600        # 30 seconds at 20 tps
fishingSettleTicks         = 14
fishingBiteVelocityY       = -0.04

trashItemIds               = [list above]
```

The legacy `miningOreQuotaCopper`, `miningOreQuotaLapis`, `miningOreQuotaIron` keys still parse for backward compatibility but are now unused. The legacy `woodLogQuota` is also still parsed for the same reason.

---

## What changed in 1.1.0

### Vote menu rebrand and additions
- "Forage" is gone. Slot 3 is now **Explore**: the bot finds an unvisited surface structure (village, pyramid, ruined portal, stronghold, mineshaft, witch hut, woodland mansion, pillager outpost), walks to it, loots every chest in range, then heads home. Same kind never repeats in the same area twice.
- New **Mystery** option, offered every other vote round. Slots into position 4 normally, position 5 when Sleep is also available at night. Targets dangerous structures (trial chambers, ancient cities / deep dark). The HUD never reveals which one was picked. Combat is expected.
- Both Explore and Mystery are "indefinite" tasks. The HUD shows the activity name with no countdown so chat doesn't see a timer that doesn't actually mean anything.

### Drowning and environmental safety
- Player air is pinned at maximum every tick while the pilot is on. Underwater paths no longer kill the bot.
- Drowning, suffocation, in-wall, cramming, and fall damage are fully cancelled by default.
- Lava damage is scaled to 10% of normal so a brief lip-touch isn't a stream-killer.
- All of the above are individually toggleable in the config.

### Mining refocus
- Priority order is now coal, copper, lapis, gold (in that order). Iron and diamond are no longer actively targeted; Baritone will still pick them up incidentally if they sit on a path.
- Quotas: 24 coal, 16 copper, 12 lapis, 8 gold.
- Cycle counter bug from 1.0.x is fixed: stalled stages now actually advance to the next ore type after a couple of explore-and-retry cycles instead of looping forever.

### Dance feature
- When YouTube super chat (or super sticker) accumulates to $5 USD, the bot pauses, switches to F5 third-person, runs `/emote dance` (Essential Mod), plays music_disc.cat for 15 seconds, then resumes the running task.
- Default keybind to manually trigger: **right bracket key** ( `]` ). F11 was avoided because that's Minecraft's fullscreen toggle.
- New commands:
  - `/dance` - trigger immediately
  - `/jewels add <amount>` - hook from external alert software when a YouTube Jewel gift fires (the YouTube Live Chat API does not surface Jewel gifts as of v1.1.0; only Super Chat / Super Sticker do)
  - `/jewels status` - show accumulator and threshold
  - `/jewels reset` - zero the accumulator

### TaskManager fix
- Combat (and now dance) interruptions during the return-home phase no longer corrupt the phase. The bot resumes whatever phase was paused instead of dropping into a bare RUNNING state.

### Misc
- `/visited count` and `/visited clear` commands for managing the explored-structures list.
- HUD supports 3, 4, or 5 vote rows depending on round composition. Help line dynamically lists which numbers are valid this round.

## Configuration

Find your config at `config/chatpilot/config.json`. New keys for v1.1.0:

| Key | Default | Purpose |
|---|---|---|
| `mysteryEveryNVotes` | 2 | Mystery appears every Nth vote round (1 = always, higher = rarer). |
| `indefiniteTaskMaxSeconds` | 480 | Hard ceiling on Explore / Mystery tasks before forced return-home. |
| `cancelDrowningDamage` | true | Pin air at max + cancel drown damage. |
| `cancelFallDamage` | true | Cancel fall damage entirely. |
| `cancelSuffocationDamage` | true | Cancel in-wall and cramming damage. |
| `lavaDamageMultiplier` | 0.10 | Scale incoming lava damage. |
| `miningOreQuotaCoal` | 24 | Coal target before advancing to copper. |
| `miningOreQuotaCopper` | 16 | Copper target before advancing to lapis. |
| `miningOreQuotaLapis` | 12 | Lapis target before advancing to gold. |
| `miningOreQuotaGold` | 8 | Gold target before falling back to stone. |
| `miningOreQuotaIron` | 0 | Legacy field, unused in v1.1.0. |
| `danceJewelThresholdUsd` | 5.0 | USD across super chat / `/jewels add` to trigger a dance. |
| `danceDurationSeconds` | 15 | How long the bot dances. |
| `danceCommand` | `/emote dance` | Chat command sent when the dance triggers (Essential Mod default). |
| `danceMusicSound` | `minecraft:music_disc.cat` | Sound event played during the dance. |
| `danceUseThirdPerson` | true | Switch to F5 view during the dance. |

## Persistent state files

- `config/chatpilot/config.json` - main config.
- `config/chatpilot/visited.json` - structures the bot has already explored. The Explore and Mystery tasks consult this so chat never gets the same village or trial chamber back-to-back. Capped at 256 entries with FIFO eviction.
- `config/chatpilot/restream_credentials.json` - Restream client credentials (unchanged from 1.0.x).
- `config/chatpilot/restream_tokens.json` - Restream OAuth tokens (unchanged from 1.0.x).

## Keybinds

| Key | Action |
|---|---|
| F6 | Toggle HUD on/off |
| F7 | Emergency stop (cancel current task, stop Baritone) |
| F8 | Set home from nearest bed |
| F9 | Force open a fresh vote |
| F10 | Toggle the pilot on/off |
| `]` | Trigger a dance immediately |

All keybinds are rebindable in Minecraft Controls under the **ChatPilot** category.

## Building

Same as 1.0.x:

1. Install JDK 21 (any vendor).
2. From a terminal in this folder: `./gradlew build` (Linux/macOS) or `gradlew.bat build` (Windows). Or just run `build_and_install.bat` on Windows.
3. The compiled jar lands in `build/libs/chatpilot-1.1.0.jar`.
4. Drop it into your Minecraft 1.21.4 Fabric mods folder alongside Fabric API.

## Required mods

- Minecraft 1.21.4
- Fabric Loader (matching version, see `gradle.properties`)
- Fabric API
- Baritone for Fabric (api jar bundled in `libs/`, runtime jar required separately)
- Essential Mod (only if you want the dance to play an actual emote; without it the `/emote dance` command will fail silently and the music will still play, the bot will still freeze and pose)

## Known limitations

- YouTube Live Chat API does not surface Jewel gifts at all as of May 2026. Only Super Chat and Super Sticker dollar amounts are extracted. Use `/jewels add` from external alert software (StreamElements, Streamlabs, Stream Avatars, etc.) to feed Jewel events into the dance accumulator.
- The Restream chat path also accepts the same vote / dance message routing but doesn't surface tip amounts on its current API.
- Mystery and Explore approach pathing through unloaded chunks depends on Baritone's explore command. If chat picks Mystery in a part of the world with no trial chambers or deep dark within ~1000 blocks, the bot may run the full 8-minute ceiling and return home empty-handed. Default behavior is fine for most worlds but if a stream session lands somewhere very flat the user can extend the ceiling via `indefiniteTaskMaxSeconds`.

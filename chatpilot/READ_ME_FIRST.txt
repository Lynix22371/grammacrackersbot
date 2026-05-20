CHATPILOT v1.2.1  -  QUICKSTART
===============================

NEW IN THIS BUILD
-----------------

1. SLOT 2 IS FISHING NOW
   The wood-chopping vote is gone. Slot 2 is "Fish". Bot finds water,
   casts the rod, watches the bobber, reels on the bite, repeats. Catches
   are mostly raw fish with the occasional treasure (saddle, name tag,
   enchanted book) which is great for chat hype moments without flooding
   the chest.

   REQUIREMENT: Grandma needs a fishing rod somewhere in her inventory.
   Hotbar is fastest; the task auto-swaps a rod from main inventory into
   hotbar slot 8 if it has to. If no rod exists at all the task ends in
   a few seconds so the next vote can run.

   The wood task is still in the code (with bee safety baked in) and can
   be re-enabled by editing VoteOption.java if you ever want it back.

2. MINING NOW HUNTS EMERALD, GOLD, COAL
   Old order was coal, copper, lapis, gold. New order is:
     - 4 emeralds  (rare and exciting on stream)
     - 8 gold
     - 16 coal
   Iron, copper, lapis, diamond are no longer actively targeted; Baritone
   still picks them up incidentally as it passes. Copper and lapis are on
   the trash list now anyway, so any incidental drops get tossed at the
   cactus on the way home.

3. TRASH CACTUS
   Bot now tosses junk items at a cactus before depositing in the hopper.
   Default trash list is everything that piles up uselessly: cobblestone,
   dirt variants, tuff, sand, gravel, raw_copper, lapis_lazuli, andesite,
   diorite, granite, all seed types, copper_ingot.

   SETUP:
     1. Place a cactus with a 2-3 block clear approach square in front of
        it so the bot can stand far enough back that cactus damage isn't a
        concern.
     2. Look at the cactus block in-game.
     3. Run /trash
   Done. The bot will toss trash at it during every return-home flow.

   Other commands:
     /trash here   - set drop point at your feet
     /trash clear  - disable the cactus step
     /trash where  - print current cactus position

4. BEE SAFETY (FOR THE BACKUP WOOD TASK)
   If wood is ever re-enabled, the chopper now scans every 2 seconds for
   beehive or bee_nest blocks within 6 blocks, and bails out of the tree
   if any are found. Stops the swarm-wrecks-the-stream failure mode.

INSTALL
-------
1. Copy chatpilot-1.2.1.jar to your .minecraft/mods folder.
2. Make sure these mods are also in mods/:
     - Fabric API (matching 1.21.4 build)
     - Baritone API Fabric 1.13.1 (the libs/ jar in this zip is built and
       linked at compile time; if you want runtime Baritone you also need
       baritone-fabric on the client. The Baritone jar that the client
       runs is whatever you already had working in v1.1.0; nothing changed
       there.)
3. Launch Minecraft 1.21.4 with Fabric.
4. Look for "chatpilot 1.2.1" in the in-game mods list.

CONFIG NEW KEYS
---------------
config/chatpilot/config.json gets these on first run after the upgrade:

  miningOreQuotaEmerald: 4
  miningOreQuotaGold:    8
  miningOreQuotaCoal:    16

  beehiveAvoidanceRadius: 6

  fishingCatchTarget:        8
  fishingWaterScanRadius:    16
  fishingMaxWaitTicks:       600       (30 seconds, recast on timeout)
  fishingSettleTicks:        14
  fishingBiteVelocityY:      -0.04     (more negative = stricter bite)

  trashItemIds:  [the default trash list, editable]

Old keys miningOreQuotaCopper / miningOreQuotaLapis / miningOreQuotaIron
and woodLogQuota are still parsed for backward compat but unused.

GOTCHAS
-------
* The fishing bobber uses the FishingBobberEntity exposed by the player.
  Catch detection is "bobber Y velocity dipped below threshold". Vanilla
  bobbers occasionally bob naturally; the threshold is set conservatively
  (-0.04) plus a 30-tick guard window after each cast to avoid false
  positives from the cast settling. Real catches blow past -0.1 easily.

* The cactus drop step uses SlotActionType.THROW with button=1 (whole
  stack) on the player's own screen handler. No GUI is opened. Item
  entities spawn forward of the player in the look direction with a small
  random spread, so aiming AT the cactus while standing 2-3 blocks back
  reliably lands them on the cactus side or top. Items dropped onto the
  cactus get destroyed within a few seconds.

* If you re-enable wood gathering, beehive avoidance is on by default.
  No extra config needed.

WHAT'S IN THIS ZIP
------------------
  src/                       - Java source for the mod
  libs/                      - Baritone-api-fabric-1_13_1.jar (compile)
  build_and_install.bat      - Windows build + copy to mods/ helper
  build.gradle, settings.gradle, gradle.properties
  README.md                  - Full changelog including v1.1.0 history
  READ_ME_FIRST.txt          - This file

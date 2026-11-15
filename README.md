# TravellersTales

## Introduction

**TravellersTales** is a custom boss and vanilla-boss overhaul plugin built exclusively for **Project: Kompass**. It transforms Minecraft's core boss encounters—the Wither, Ender Dragon, and Warden—into multi-phase raid battles while introducing five brand-new bosses and mini-bosses culminating in an endgame showdown against **Herobrine**.

Every encounter is designed around readable attack telegraphs, dynamic phase transitions, custom BossBars, anti-cheese protections, and unique boss rewards. Optional integrations with **PacketEvents** and **DiscordSRV** further bring Herobrine into your server's player list and Discord channel.

---

## Permissions

| Permission | Description | Default |
| :--- | :--- | :--- |
| `travellerstales.admin` | Allows summoning bosses, granting special boss items, unlocking the Herobrine Shrine, reloading configuration, and running maintenance commands. | `op` |

---

## Commands

All plugin management uses the central `/travellerstales` command (alias: `/tt`).

| Command | Description |
| :--- | :--- |
| `/tt summon wither` | Summons **The Wither** with its full intro animation at your location. |
| `/tt summon warden` | Summons **The Warden** emerging from the deep dark at your location. |
| `/tt summon traveller` | Summons **The Traveller** with its rift entrance at your location. |
| `/tt summon giant` | Summons **The Giant** crawling up from underground at your location. |
| `/tt summon dreadnought` | Summons a **Dreadnought Golem** with its beacon startup sequence. |
| `/tt summon world_slime` | Awakens **The World Slime** via its summoning ritual. |
| `/tt summon magma_core` | Awakens **The Magma Core** via its summoning ritual. |
| `/tt summon herobrine` | Summons **Herobrine** at your location (only one Herobrine may be active at a time). |
| `/tt boots bouncy` | Grants a pair of **Bouncy Boots** (World Slime boss reward). |
| `/tt boots magma` | Grants a pair of **Magma Core Boots** (Magma Core boss reward). |
| `/tt tunneler` | Grants **The Hero's Tunneler** (Herobrine boss reward). |
| `/tt unlock_shrine` | Unlocks all 7 prerequisite boss kills for your character to activate the Herobrine Shrine. |
| `/tt reload` | Reloads all boss settings, BossBars, and weapon resistance rules from `config.yml`. |
| `/tt fix` | Safely clears all active boss fights, minions, temporary effects, and BossBars. |
| `/tt status` | Displays the live status of all active bosses and current weapon resistance settings. |

---

## Features

### Global Systems & Progression
* **Mace & Spear Balance:** Configurable damage reduction (default **75% reduction**) on Mace smashes/hits and Spear attacks so bosses cannot be instantly one-shot. Can be configured for all mobs or restricted to bosses.
* **Custom Multi-Phase BossBars:** Every boss features custom-styled BossBars with dynamic phase titles, colors, segments, view radii, and sky-darkening effects.
* **Natural Night-Boss Spawning Rules (The Traveller & The Giant):**
  * **500×500 Spawn Safe Zone:** Neither mini-boss spawns naturally within `±250` blocks of `(0, 0)` in the Overworld.
  * **3-Day Boss Deferral:** Defeating any boss—or dying to any boss or its minions—grants a 3-day cooldown preventing natural Traveller or Giant spawns.
  * **One Night Boss at a Time:** The Traveller and The Giant will never naturally spawn on the same night.
* **7-Boss Progression Lock:** Players must defeat **The Wither**, **The Ender Dragon**, **The Warden**, **The Traveller**, **The Giant**, **The World Slime**, and **The Magma Core** before they are worthy of igniting the Herobrine Shrine.

---

### Boss Roster

#### 1. The Wither *(1,000 HP)*
* **Intro & Atmosphere:** Optional Bad Omen summoning cost, singleton enforcement (refunds skulls and soul sand if a Wither is already alive), soul-pulling intro from nearby mobs, gravity pull, darkness, and weather storms.
* **Phase 1 & Combat:** 50 HP single-hit damage cap, custom blast-resistant armor, homing skulls, 5-skull burst volleys, direct-hit explosive knockback counters, periodic Wither Skeleton guards, and conversion of nearby Zombies and Skeletons into Wither allies.
* **Midpoint & Enraged Phase:** At 50% HP, spins skyward to launch up to 30 Blaze-mounted Wither Skeleton archers before crashing down in a shockwave slam. At 25% HP, unleashes a 15-skeleton mini-swarm; once Enraged, drops to grounded gravity, fires 360° 24-skull homing rings, chains triple destructive charges, and performs wind dashes.
* **Rewards:** 200 levels of XP fountain, a glowing rotating Nether Star display, and the **Withered Rose** trophy.

#### 2. The Ender Dragon *(1,500 HP — 6 Phases)*
* **Overhauled Arena & Scale:** **1.8× larger** than a normal Ender Dragon, featuring dynamic phase-shifting particle colors, beacon-marked End Crystals, remaining crystal actionbar alerts, anti-pearl-farming shatter, and caged-tower guard Phantoms.
* **Signature Attacks:**
  * **Direct & Non-Direct Slams:** Targeted dives onto players and sweeping curved swoops across the island, triggering 64-block ground shockwaves with white sparkles that players must jump over (and instantly slaying Endermen).
  * **Multi-Dragon Slams:** Summons mirror decoy dragons in both symmetric formations and randomized island-wide barrages, each unleashing a shockwave.
  * **Fireballs & Ground-Snapped Breath:** Slower, readable homing fireballs whose lingering Dragon's Breath clouds always land cleanly on solid ground below.
  * **Dragon Beam:** Sweeps an outward-spiraling beam across the island using a slow $\rightarrow$ average $\rightarrow$ slow cadence with firework-like explosive hits.
  * **Smart Perching:** Tracks nearby players on the Exit Portal with Dragon's Breath, stopping its rotation and breath if a player moves directly underneath it.
* **6-Phase Progression:**
  1. **End Crystals Alive (Void):** Non-Direct Slam $\rightarrow$ Fireball $\rightarrow$ Direct Slam.
  2. **Void Dragon (Crystals Destroyed):** Non-Direct Slam $\rightarrow$ Fireball $\rightarrow$ Direct Slam $\rightarrow$ Fireball $\rightarrow$ 1-Slam Perch *(inflicts Levitation)*.
  3. **Crimson Dragon:** Direct Slam $\rightarrow$ Non-Direct Slam $\rightarrow$ Fireball $\times 2$ $\rightarrow$ Direct Slam $\rightarrow$ Non-Direct Slam $\rightarrow$ Dragon Beam $\rightarrow$ 2-Slam Perch *(inflicts Wither)*.
  4. **Ichor Dragon:** Direct Slam $\rightarrow$ Fireball $\rightarrow$ Non-Direct Slam $\rightarrow$ Fireball $\rightarrow$ Dragon Beam $\rightarrow$ Random Multi-Dragon Slam $\rightarrow$ Fireball $\rightarrow$ 3-Slam Perch (Slam $\rightarrow$ Normal Multi-Slam $\rightarrow$ Slam) $\rightarrow$ Dragon Beam *(lifesteals on hit)*.
  5. **Ender Dragon:** Direct Slam $\rightarrow$ Fireball $\rightarrow$ Direct Slam $\rightarrow$ Fireball $\rightarrow$ Dragon Beam $\rightarrow$ Random Multi-Dragon Slam $\rightarrow$ Fireball $\rightarrow$ 3-Slam Perch (2$\times$ Normal Multi-Slams $\rightarrow$ Slam) $\rightarrow$ Dragon Beam $\rightarrow$ Normal Multi-Dragon Slam.
  6. **Totem Climax:** Pops a Totem of Undying, rises 32 blocks above the portal, becomes 100% immune to projectiles, pulses with Warden heartbeats and big firework flashes that repulse players, opens a portal Levitation stream for the final melee strike, and alternates **Shockwave $\rightarrow$ Dragon Beam**.
* **Rewards:** 300 levels of XP at the Exit Portal.

#### 3. The Warden *(1,000 HP — 3 Phases)*
* **Stealth-First Shrieker Spawning:** Wardens spawned from Sculk Shriekers start with vanilla detection—sniffing and investigating noises before turning hostile, giving players time to sneak away and avoid the boss fight. Only one Warden can spawn within a **128-block radius** (`256×256` area).
* **Anti-Cheese & Escape Protection:** Immune to arrows outside of Phase 3 parry windows, fires a 25-block mob-piercing Sonic Boom, digs underground to teleport beside unreachable targets, and remembers mid-combat chunk unloads so fleeing triggers a 1-shrieker respawn next time the player sets off a shrieker.
* **3-Phase Mechanics:**
  * **Phase 1:** Fires creeping **Tendril Snares** that slow and lock jumping.
  * **Phase 2 (66% HP):** Inflicts Darkness, spawns **Sculk Parasites** when players sprint or jump on any Sculk block (sending vibration beacons to anger the Warden), and unleashes 3-wave **Seismic Echo** jump-rope shockwaves every 20 seconds.
  * **Phase 3 (33% HP):** Gains speed and enters **Heartbeat Parries**—three accelerating heartbeats where landing a critical melee or arrow hit parries for 15% Max HP damage and a 4-second stun; failing to parry triggers a 25-damage Sonic Boom blast.
* **Rewards & Grace Period:** Plays a Soul Explosion death sequence; drops 100 levels of XP, 1× Sculk Catalyst, 1× Recovery Compass, and 2× Echo Shards, and grants a **10-minute Sculk grace period** covering a 128-block radius.

#### 4. The Traveller *(250 HP — Neutral Night Stalker)*
* **Spawning:** Spawns globally once per night across valid Overworld, Nether, or End biomes for active (non-AFK) players who have defeated the Ender Dragon and gone 5 in-game days without sleeping.
* **Combat & Shield Mastery:** Outfitted in Gold-trimmed Wayfinder Netherite armor, a Mace, and an 8-layer custom banner Shield. Remains neutral and stalks until provoked. Reduces arrow damage by 75%, blocks 35% of frontal arrows, performs Shield Bashes if guard-broken by an axe, teleports via Gilded Rifts, executes swirling Mace strikes, and launches 3-shot homing Wither Skull volleys.
* **Escape & Rewards:** Vanishes into a rift if the player escapes, logs out, or survives until day, queueing a rematch for the very next night. Drops 50 levels of XP, 32× Gold Ingots, and a bonus roll of Netherite Scrap, Ancient Debris, or a Netherite Upgrade Smithing Template.

#### 5. The Giant *(500 HP — Surface Colossus)*
* **Spawning:** Crawls out from 11.5 blocks underground on flat/lowland Overworld terrain ($Y \le 110$, avoiding mountains, oceans, rivers, Mushroom Fields, Deep Dark, and the spawn safe zone) every 5 days pre-Dragon or on a 3-day post-Dragon cooldown.
* **Mechanics & Rewards:** Crushes foliage and tramples grass into dirt, is immune to fall damage, and ruptures in a gore explosion on death that launches 6–12 zombies outward. Drops 50 levels of XP, 128× Rotten Flesh, and 64× Bones.

#### 6. Dreadnought Golem *(800 HP — Friendly Construct)*
* **Construction:** Built like an Iron Golem using a **Beacon** in the center flanked by Iron Blocks and topped with a Pumpkin, Carved Pumpkin, or Jack o'Lantern.
* **Allied Guardian:** Never attacks players, villagers, passive mobs, pets, or golems—actively hunts hostile monsters, **Herobrine**, and **Herobrine's Steve minions**.
* **Abilities & Drops:** Reflects all arrows/tridents back at attackers, heals nearby Iron Golems in a 16×16 aura, deals massive **Dreadnought Bash** knockback, and grabs enemies overhead for a devastating ground slam. Shatters on death to drop 1× Beacon.

#### 7. The World Slime & The Magma Core *(300 HP $\rightarrow$ 2× 100 HP)*
* **Craftable Spawn Eggs:** Craftable in 3×3 recipes using an Eye of Ender surrounded by 8× Slime Blocks (**World Slime Spawn Egg**) or 8× Magma Blocks (**Magma Core Spawn Egg**).
* **2-Phase Split & Abilities:** Fall-immune Size-9 bosses that leap sky-high and plunge into 8-block ground slams, inflicting Oozing + mini-slimes (World Slime) or 30 seconds of Fire + mini-magma cubes + ground flames (Magma Core). At 0 HP, each splits into **two Size-5 Twins (100 HP each)**.
* **Rewards:** 50 levels of XP plus unique boss boots:
  * **Bouncy Boots (World Slime):** Negates fall damage and bounces you upward upon landing (hold Sneak to land without bouncing).
  * **Magma Core Boots (Magma Core):** Grants fire/magma immunity and solidifies lava into Magma Blocks and water into Stone beneath your feet.

#### 8. Herobrine *(1,500 HP — 3-Phase Endgame Boss)*
* **The Shrine:** Unlocked after slaying all 7 prior bosses. Constructed from a 3×3 Gold Block base (Gold or Mossy Cobblestone center) with Netherrack above the center surrounded by 4 Redstone Torches, then ignited.
* **World & Server Presence:** Joins the server player list, triggers thunderstorms and rapid Day/Night sky flickering, and posts styled join, leave, player-slain, and boss-slain embeds to Discord (when DiscordSRV is installed).
* **Mob Fear & Golem Rivalry:**
  * All normal mobs are terrified of Herobrine and his Steve minions, unable to target them and fleeing on sight.
  * Iron Golems and Dreadnought Golems are immune to fear and actively charge Herobrine and his Steves—though Herobrine **instantly kills** vanilla Iron Golems and deals **100 armor-ignoring damage** per strike to Dreadnought Golems.
* **3-Phase Combat:**
  * Summons waves of nametagless sword and ranged **Steve minions** every 100 HP lost (scaling from Iron in Phase 1, to Diamond in Phase 2, to Netherite + Piercing IV Crossbows in Phase 3) while floating invulnerably.
  * Reflects Tridents, Fireballs, and Wind Charges back at attackers, deflects arrows while floating, swaps to axes to break player shields, and blinks behind players when struck in melee.
  * **Phase 2 & 3 Escalation:** Unleashes 5-wave lightning rings and 5-pointed star strikes, Guardian laser lock-ons, Channeling Trident stun volleys, **3D Trident Dome** homing barrages (shield-blockable or lightning-charged), and an outward-spiraling **Dragon Laser** sweep in Phase 3 before 3×3 lightning slams.
* **Death & Rewards:** Ascends in an Ender-Dragon-style lightning climax, dropping **500 levels of XP** (plus 10 levels per Steve), **32× Enchanted Golden Apples**, and **The Hero's Tunneler** (an unbreakable 3×3 mining Netherite Pickaxe).

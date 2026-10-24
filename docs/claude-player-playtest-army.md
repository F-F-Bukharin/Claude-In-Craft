# Playtest: the 24 Oct army round

Every new skill and fix, as chat orders. Each line: **order**, then *what should happen*. Run them top to bottom; later sections reuse what earlier ones built. After the session, keep `logs/latest.log` (look for `reflex`, `claude code reply`, `failed:`, `denied:`).

## Setup
- Jar from `review/army-round` (59d0a82) or later; config `tool_phase = 4`, `trigger = "C"`. Creative-ish kit: `/give @p` what each section lists, or `/give Claude ...` (the body is a player).
- Right-click Claude now and keep the Brain panel in mind: every order below should show its plan under **Thinking**, its calls under **Queue**, and their codes under **Recent**.
- `/cp status`: says `attacks on`, and tokens "this hour".

## 1. Movement (the gate: if this feels worse than before, stop and report)
- **C come here** across a field. *Straight line, no zig-zag; one jump per 1-block step; no jump on slabs or stairs.*
- **C come here** from 30+ blocks on flat ground. *It sprints; not next to lava or a 3+ drop.*
- Stand behind a closed wooden door. **C come here**. *Opens it, walks through, closes it about 3 blocks behind. An iron door: it goes around or says no_path.*
- Stand across a pond. **C come here**. *Swims across along the path, climbs out; no sinking.*
- Long underwater tunnel. **C come here**. *Rises for air when low.*
- Box it in with 3 walls and place a block in the 4th gap while it walks. *Jump at ~1 s, re-plan at ~2 s, then `failed:stuck` or a new route.*
- **C follow me** and walk, turn sharply, stop. *Stays 2 to 3.5 blocks back without stop-start jitter; stops at lava or a deep drop.*
- Lava pool on top of a 1-block step on its route. *It refuses the step (`hazard_lava`).*

## 2. Senses
- Tree on a hill 20 blocks away. **C what do you see around you?** *Mentions the tree uphill (`t` on the map).*
- **C look as far as you can** (radius 64). *No lag spike; answer mentions scale (1 cell = 3x3).*
- Near a village. **C what's around? use see** *Landmarks line: bell or villagers, water, highest point, each with a compass direction.*
- Cows and a cat nearby. **C what animals are near you?** *Names them from `creatures`, with distances; knows which are babies.*

## 3. Liquids and crops
Kit: hoe, water bucket, empty bucket, wheat seeds, carrots, potatoes, sugar cane, melon seeds, sweet berries, bone meal.
- **C till the dirt in front of you and plant wheat seeds** *Farmland, then wheat. On coarse dirt: plain dirt first (till again).*
- **C pour water on the ground next to the farmland** *Water lands beside the face it looks at (this always failed before).*
- **C fill your empty bucket from the pond** *Fills from a still source; from flowing water it says no_source.*
- **C pour water into that slab** *Waterlogs the slab.*
- Put a torch on the ground. **C pour water on the torch** *`denied:protected_block` (it isn't Claude's).*
- **C plant sugar cane by the water**, then far from water. *First works; second `no_water`.*
- **C plant melon seeds and sweet berries** *Melon on farmland, berries on grass.*
- **C use bone meal on the wheat** *No bone meal tool exists: it should say so plainly, not pretend.*
- `/time add 24000` a few times. **C harvest the farm** *Ripe wheat, carrots, potatoes broken and replanted; berries picked (bush stays); melon broken, stem kept; cane cut from the second block up; drops in water collected.*
- Cactus with drops beside it. **C harvest here** *Picks up drops from a spot clear of the cactus; no damage.*
- **C expand the farm: till 5 more blocks in a row east and plant carrots** *Batches till and plant; skips blocks that aren't dirt.*
- **C dig a 1-wide canal 6 blocks long beside the new farm and fill it with water** *mine_block or dig along the row, then use_bucket at one end and refills; crops next to the water stay.*

## 4. Lava rules (safety)
Kit: lava bucket. Stand 5 blocks from Claude, near home.
- **C pour lava there** (a block 2 from Claude's feet). *`denied:too_close`.*
- **C pour lava next to me** *`denied:lava_near_player` (needs more than 7 blocks from you in the overworld).*
- Walk 10+ away. **C pour lava 4 blocks in front of you** *Works; it doesn't touch you or Claude.*
- Far from home (over 32 blocks). *`denied:outside_work_area`.*

## 5. Building and home
Kit: 71 oak planks, 1 oak door, 1 red bed, 64 cobblestone.
- **C build a pillar of 5 cobblestone here** *build places bottom up, reports placed and skipped.*
- **C build a wall 4 long and 2 high in front of you** *A block list; places each from within reach.*
- **C build at 10000 64 10000** *`no_path` at once, no lag (far or unloaded).*
- **C build a house here facing south** *5x5, walls 3 high, flat roof, door in the south wall facing out, bed inside against the back wall, "home" set to the bed.*
- With only 40 planks. **C build another house** *`missing_blocks` (needs 71, carries 40).*
- Walk 40 blocks away. **C go home and store your loot** *Walks back, through its own door, stores in the chest by the house, keeps tools, food, torches, the bed, one stack of blocks.*
- Night. **C sleep** *Walks to its bed, lies down; you sleeping too skips the night.*
- While it sleeps: **C what's in your inventory?** *Answers without getting up.* Then **C come here** *Gets up and comes.*
- Daytime. **C sleep** *`not_night`.* A zombie nearby at night: *`monsters_nearby`.*

## 6. Animals (attacks on)
Kit: iron sword, wheat, bones, raw cod, seeds; a pen with cows, calves, a named cow, a saddled pig.
- **C hunt 2 cows** *Kills two adult cows, picks up beef and leather. Never the calves or the named cow, even with the sword swinging next to them.*
- You stand next to the cow it's hunting. *You're never hit (no sweep).*
- **C hunt the pig** with you riding it. *Refused (ridden).*
- **C breed the cows** *Feeds two adults wheat; hearts, then a calf.* Again at once: *`not_ready`.*
- Only calves left. **C breed the cows** *`not_ready`.*
- **C tame that wolf** (with bones) *Feeds until tamed; it sits.* **C make the wolf follow you** *pet follow.*
- **C tame a parrot** with torchflower seeds only. *Works (new seed).*
- **C make my dog sit** (your own wolf) *`not_mine`.*

## 7. Villagers and storage
Kit: 40 emeralds, paper; a village with a librarian; three messy chests side by side.
- **C what does that librarian sell?** *trade_offers: index, price, result, uses left.*
- **C buy the first offer 3 times** *Pays and receives; price matches the menu you see.*
- **C buy 20 of it** *Stops at the emerald cap (32 by default), ok with the reason.*
- Open the villager's trade screen yourself, then ask Claude to trade. *`busy`.*
- **C sort these chests** *Blocks, ores, food, tools each end up together; nothing lost; its own sword and tipped arrows stay with it.*
- **C what's in each chest now?** *Answers from its notes.*

## 8. Explore and fish
Kit: fishing rod.
- **C explore around here for a minute, radius 32** *Walks out in new directions, no chat while out, comes back, one report: trees, water, ores, animals, villages with positions.*
- **C explore for 30 seconds, radius 64** *Turns back at 15 s and reports what it found (not no_path).*
- **C fish 2 times** by a pond with cod. *Casts, reels in right after a bite. If every cast waits about 35 s and catches nothing, bite detection failed: report it.*
- No rod: **C fish** *`missing_rod`.* Far from water: *`no_water`.*

## 9. Combat and reflexes
- `/cp level 1`, spawn a zombie 10 blocks away. *`reflex FIGHT`, gears up, fights, wins.*
- A player beside the zombie it fights. *The player is never hit.*
- Level 3, open field, zombie. *Flees (by design).* Boxed in: *fights when it's adjacent.*
- Zombies on both sides of a corridor. *Flees along the open axis, never past a zombie.*
- `tool_phase = 3` (restart): **C level 1** *Notice says attacks are off and how to turn them on.*

## 10. Brain panel
- During a long order (the house): *Order, Thinking (one-line plan), Queue (running, waiting, prio), Recent (codes), Status (caution, attacks, step n/max, tokens this hour).*
- Shrink the window, raise the GUI scale. *Text wraps, nothing clips; mouse wheel and Page Up/Down scroll.*
- *Recent shows what failed; "said in chat" shows only lines that really went to chat.*

## 11. Combinations (one order each)
- **C get 10 logs, craft planks and a door, and build yourself a house here** *gather, craft, build_house; asks for a bed if it has none.*
- **C make a wheat farm next to your house with a water canal, then harvest it tomorrow** *till, plant, water canal; a day later, harvest and replant.*
- **C breed the cows, then hunt one and cook the beef** *breed, hunt, smelt.*
- **C explore, then go home, store what you found and sleep** *roam, go_home store, sleep at night.*
- **C trade for something useful, then sort your chests** *trade_offers, trade, sort_chests.*
- **C go do your thing, I'll call you** (autonomy) for 5 minutes. *Uses the new skills sensibly; no lava near you; no attacks on pets or villagers.*

# Writing AFK Bot mods

Mods are small JavaScript files that run inside the app (Rhino engine, ES6 syntax like `let`, `const`
and arrow functions works). Install them from the **Modules** tab: *Examples*, *From file*, *Paste code*
or *From link* (a raw GitHub link works). Every mod has an on/off switch, an editor and its own log.

## Header

```js
// @name My Mod
// @description What it does
// @version 1.0
// @author You
// @enabled false      (optional: install switched off)
```

## Events — `mod.on(name, function (e) { ... })`

| Event | Data |
|---|---|
| `join` | `e.server` |
| `leave` | `e.reason` (disconnect / safety logout) |
| `kicked` | `e.reason` |
| `chat` | `e.message`, `e.sender` (if known), `e.raw`, `e.system` |
| `playerEnter` | `e.name`, `e.distance`, `e.friend` |
| `playerLeave` | `e.name` |
| `health` | `e.health` (0-20) |
| `death` | `e.pos` |
| `second` | — (every second while online) |

## Timers and saved data

```js
var id = mod.every(60000, fn);   // repeat
mod.after(5000, fn);             // once
mod.cancel(id);
mod.storage.get('key', defaultValue);   // kept between restarts (max 200 KB per mod)
mod.storage.set('key', value);
```

## Bot

```js
bot.name; bot.server; bot.online();
bot.pos();       // {x, y, z, yaw}
bot.health();    // 0-20
bot.players();   // [{name, distance, dx, dy, dz, friend}]
bot.entities();  // same, plus kind: player / hostile / animal / item / other
bot.chat('hello');      // rate-limited: 1 per 1.5 s, 15 per minute
bot.command('home');    // same as bot.chat('/home')
bot.jump();             // do the anti-AFK action now
bot.log('text');        // mod log (and the app log)
bot.alert('Title', 'text');   // phone notification
bot.disconnect('reason');     // safety logout
console.log('also works');
```

## Controls in the app: settings, buttons, status

A mod can give itself a settings form, buttons and status lines. They show on the mod's card (Modules), on the
Home tab while it runs, and the status lines also at the top left of the Play screen.

```js
var cf = mod.settings([            // returns the current values (saved between restarts); cf.radius etc.
  {key: 'radius', tab: 'Farming', type: 'slider', min: 8, max: 128, default: 64, label: 'Farm size: {v} blocks out',
   help: 'Shown under it.'},       // {v} is the value, {half} half of it; labels: {0: 'Never'} names a value
  {key: 'sprint', tab: 'Farming', type: 'toggle', default: true, label: 'Sprint'},
  {key: 'boxes', tab: 'Storage', type: 'choice', options: ['auto', 'off'], default: 'auto', label: 'Boxes: {v}',
   helps: {auto: 'Help for auto', off: 'Help for off'}}
]);
mod.on('settings', function (e) { /* e.key, e.value: you changed one */ });
mod.saveSettings();                // after changing cf.something yourself

mod.buttons([{id: 'start', label: '▶ Start', help: 'Shown on a long press'}]);  // the first stands out
mod.on('button', function (e) { if (e.id === 'start') ... });
mod.status([{text: 'Working', color: 'gold'}, {text: '12 done', color: 'gray'}], {screen: true});
  // colours: gold, gray, red, green, yellow, aqua, white. screen: false keeps them off the Play screen
mod.say('to you only, in the chat');
mod.command(['hh', 'harvesthand'], function (args) { ... });  // "/hh start 32" typed in the app comes here, not to the server
```

## Every tick, walking, clicking, inventory and the world

```js
mod.on('tick', fn);          // 20 times a second while in a world (skipped if your last one is still running)
mod.on('useBlock', fn);      // you right-clicked a block: e.x, e.y, e.z, e.face, e.item (what you held)

bot.control(true);           // the mod walks the bot: anti-AFK waits; bot.control(false) gives it back
bot.body();                  // {x, y, z, yaw, pitch, onGround, inWater, health, food, dimension, server, mode}
bot.look(yaw, pitch); bot.lookAt(x, y, z);
bot.hit(x, y, z, face);      // left click; a block with no hardness (crops) breaks at once: true when it's gone
bot.mine(x, y, z, face); bot.mining(); bot.stopMining();     // break a harder block over the right time
bot.useOn(x, y, z, face, cx, cy, cz);    // right click a block at a point on it (0..1)
bot.useItem(); bot.releaseUse(); bot.swing();
// faces: 0 down, 1 up, 2 north, 3 south, 4 west, 5 east. Clicks go out in the bot's next tick, after its move.

inv.items();      // your inventory: 0-8 hotbar, 9-35 the rest, 36-39 armour, 40 off hand; each null or
                  // {id, name, count, max, damage, maxDamage, ench: {fortune: 3}, contents: [...] (shulker boxes)}
inv.held(); inv.select(0..8); inv.swap(slot, hotbar);      // swap: as a number key over a slot (40: off hand)
inv.container();  // the open chest/box: {id, type, size, byMod, loaded, slots: [...]} or null
inv.quickMove(windowSlot); inv.close();                    // shift-click; your slots come after the container's
// Clicks are predicted at once, like the real client, and the server's answer corrects them.

world.block(x, y, z);        // "carrots", "air", or null if that chunk isn't loaded
world.prop(x, y, z, 'age');  // a block-state property, as text
world.passable / standable / wet / fluid / replaceable / hardness / blockEntity (x, y, z)
world.solidSide(x, y, z, face); world.outlineTop(x, y, z); world.loaded(x, z); world.waterNear(x, y, z, r, dy)
world.predict(x, y, z, 'carrots');   // show a change before the server confirms it
world.items();               // dropped items: {entity, id, count, x, y, z, age (ticks)}
world.mobs(radius);          // {type, kind: player/hostile/animal, x, y, z, name}
world.flood(x, y, z, r);     // every cell you can walk to: x, y, z, flags (1 stand, 2 fluid, 4 watered), block under
world.lookedAt();            // the block under the Play screen's crosshair: [x, y, z, face] or null

nav.plan(goal, [minX, minY, minZ, maxX, maxY, maxZ]);  // goal: {near: [x,y,z], reach: 3.3, avoid: [[x,y,z]]},
                                                       //       {column: [x,y,z]} or {around: [x,y,z], r: 1}
nav.tick();       // keep walking; false if it got stuck (and gave up)
nav.arrived(); nav.moving(); nav.stop(); nav.clear(); nav.sprint(on); nav.careful(on);
```

The walker steps up one block, drops up to five (more than three only if there's no other way), never walks into
lava or fire, doesn't cut corners, and goes round water when there's a dry way.

## Add-ons

Bigger mods live in their own repos and are installed with *From link*. For example
[Harvest Hand](https://github.com/PlayWitIt/harvest-hand-afk): harvests and replants your whole farm, with chests,
shulker boxes, Fortune tools, eating and reconnecting, all set up from its card.

## Sandbox

Mods can't access Java classes, files or the network. Each event/timer call has an instruction budget
(20 million), so an endless loop is stopped; a mod that fails 5 times is switched off and you get an alert.

## Examples

See [`app/src/main/assets/mods`](app/src/main/assets/mods): Auto Reply, Player Logger, Announcer,
Keyword Alerts, Damage Alarm, AFK Status.

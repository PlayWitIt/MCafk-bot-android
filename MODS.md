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

## Sandbox

Mods can't access Java classes, files or the network. Each event/timer call has an instruction budget,
so an endless loop is stopped; a mod that fails 5 times is switched off and you get an alert.

## Examples

See [`app/src/main/assets/mods`](app/src/main/assets/mods): Auto Reply, Player Logger, Announcer,
Keyword Alerts, Damage Alarm, AFK Status.

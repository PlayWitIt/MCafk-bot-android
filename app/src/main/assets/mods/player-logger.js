// @name Player Logger
// @description Remembers every player you've seen (when and how close) and alerts you about players on a watch list.
// @version 1.0
// @author AFK Bot

// ---- settings ----
var WATCH_LIST = ['SomeGriefer', 'AnotherName'];   // you get a phone alert when these appear
var LOG_EVERY_PLAYER = true;

var seen = mod.storage.get('seen', {});   // name -> { first, last, closest, count }

mod.on('playerEnter', function (e) {
  var p = bot.pos();
  var s = seen[e.name] || { first: Date.now(), count: 0, closest: 9999 };
  s.last = Date.now();
  s.count++;
  s.closest = Math.min(s.closest, e.distance);
  s.where = Math.round(p.x) + ', ' + Math.round(p.z);
  seen[e.name] = s;
  mod.storage.set('seen', seen);

  if (LOG_EVERY_PLAYER) bot.log(e.name + ' seen ' + e.distance + ' blocks away (seen ' + s.count + ' times)');
  for (var i = 0; i < WATCH_LIST.length; i++) {
    if (WATCH_LIST[i].toLowerCase() === e.name.toLowerCase()) {
      bot.alert('Watched player nearby', e.name + ' is ' + e.distance + ' blocks away');
    }
  }
});

// Say "!seen" in chat (from another account) or check the mod log for a summary every 30 minutes.
mod.every(30 * 60 * 1000, function () {
  var names = Object.keys(seen);
  bot.log('Players seen so far: ' + names.length + (names.length ? ' - ' + names.slice(-10).join(', ') : ''));
});

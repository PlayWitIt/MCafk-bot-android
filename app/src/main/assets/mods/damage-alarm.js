// @name Damage Alarm
// @description Alerts you the moment you take damage, with how much health is left. Optionally logs out after repeated hits.
// @version 1.0
// @author AFK Bot

// ---- settings ----
var LOG_OUT_AFTER_HITS = 3;      // 0 = never log out, just alert
var WINDOW_SECONDS = 20;

var last = 20, hits = [];

mod.on('health', function (e) {
  if (e.health < last) {
    var lost = (last - e.health).toFixed(1);
    bot.alert('Taking damage!', 'Lost ' + lost + ' HP, ' + (e.health / 2).toFixed(1) + ' hearts left');
    var now = Date.now();
    hits.push(now);
    hits = hits.filter(function (t) { return now - t < WINDOW_SECONDS * 1000; });
    if (LOG_OUT_AFTER_HITS > 0 && hits.length >= LOG_OUT_AFTER_HITS) {
      bot.disconnect('hit ' + hits.length + ' times in ' + WINDOW_SECONDS + ' seconds');
    }
  }
  last = e.health;
});

mod.on('join', function () { last = 20; hits = []; });

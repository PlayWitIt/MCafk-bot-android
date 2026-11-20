// @name AFK Status
// @description Answers "!afk" in chat with how long you've been online, and keeps simple session stats.
// @version 1.0
// @author AFK Bot

var joinedAt = 0;
var sessions = mod.storage.get('sessions', 0);

mod.on('join', function () {
  joinedAt = Date.now();
  sessions++;
  mod.storage.set('sessions', sessions);
  bot.log('Session #' + sessions + ' started');
});

function duration(ms) {
  var m = Math.floor(ms / 60000), h = Math.floor(m / 60);
  return h > 0 ? h + 'h ' + (m % 60) + 'm' : m + 'm';
}

mod.on('chat', function (e) {
  if (!e.sender || e.sender === bot.name) return;
  if (e.message.trim().toLowerCase() === '!afk' && joinedAt) {
    bot.chat(bot.name + ' has been AFK for ' + duration(Date.now() - joinedAt) + '.');
  }
});

// @name Announcer
// @description Sends a message (or command) every few minutes, like a spam/announcer module. Installed switched off - edit the message first.
// @enabled false
// @version 1.0
// @author AFK Bot

// ---- settings ----
var MESSAGES = [
  'Hello from my AFK spot!',
];
var EVERY_MINUTES = 10;
var RANDOM_ORDER = false;

var i = 0;
mod.every(EVERY_MINUTES * 60 * 1000, function () {
  if (!bot.online() || MESSAGES.length === 0) return;
  var msg = RANDOM_ORDER ? MESSAGES[Math.floor(Math.random() * MESSAGES.length)] : MESSAGES[i++ % MESSAGES.length];
  bot.chat(msg);
  bot.log('Sent: ' + msg);
});

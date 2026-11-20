// @name Auto Reply
// @description Answers when someone says your name or whispers you, so people know you're AFK.
// @version 1.0
// @author AFK Bot

// ---- settings (edit these) ----
var REPLY = "I'm AFK right now, I'll get back to you later!";
var COOLDOWN_SECONDS = 60;   // don't reply to the same person more often than this

var lastReply = {};

mod.on('chat', function (e) {
  var me = bot.name.toLowerCase();
  if (!me || !e.message) return;
  var sender = e.sender;
  var text = e.raw.toLowerCase();

  // whispers usually look like "Steve whispers to you: hi" or "From Steve: hi"
  var whisper = e.raw.match(/^(?:From |\[)?([A-Za-z0-9_]{3,16})(?: whispers(?: to you)?:| -> me\]|:)\s/i);
  if (!sender && whisper) sender = whisper[1];
  if (!sender || sender.toLowerCase() === me) return;

  var mentioned = text.indexOf(me) >= 0;
  if (!mentioned && !whisper) return;

  var now = Date.now();
  if (lastReply[sender] && now - lastReply[sender] < COOLDOWN_SECONDS * 1000) return;
  lastReply[sender] = now;

  if (whisper) bot.command('msg ' + sender + ' ' + REPLY);
  else bot.chat(REPLY);
  bot.log('Replied to ' + sender);
});

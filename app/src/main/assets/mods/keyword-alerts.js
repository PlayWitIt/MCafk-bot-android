// @name Keyword Alerts
// @description Sends a phone alert when chat contains your name or any word you choose.
// @version 1.0
// @author AFK Bot

// ---- settings ----
var KEYWORDS = ['base', 'stash', 'coords'];   // your own name is always included
var INCLUDE_SERVER_MESSAGES = true;

mod.on('chat', function (e) {
  if (!INCLUDE_SERVER_MESSAGES && e.system && !e.sender) return;
  var text = e.raw.toLowerCase();
  var words = KEYWORDS.concat([bot.name]);
  for (var i = 0; i < words.length; i++) {
    var w = String(words[i]).toLowerCase();
    if (w && text.indexOf(w) >= 0 && e.sender !== bot.name) {
      bot.alert('Chat mentioned "' + words[i] + '"', e.raw);
      return;
    }
  }
});

package app.afkbot;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The Naughty or Nice tab: People (your lists, split by group), DMs (whisper conversations you can answer), Intel
 * (coordinates posted, your name mentioned), Log (joins, leaves, sightings) and Settings (alerts, import from PC).
 * Its content is rebuilt from {@link NonIntel} about once a second while it's on screen and something changed.
 */
final class NonPage {
    private static final int BG = MainActivity.BG, SURFACE = MainActivity.SURFACE, SURFACE2 = MainActivity.SURFACE2, STROKE = MainActivity.STROKE,
            TEXT = MainActivity.TEXT, MUTED = MainActivity.MUTED, ACCENT = MainActivity.ACCENT, ACCENT2 = MainActivity.ACCENT2,
            GOOD = MainActivity.GOOD, BAD = MainActivity.BAD, WARN = MainActivity.WARN;
    static final int FRIEND = 0xFF8AB4FF, NICE = 0xFF4CD39B, LOOK = 0xFFFFC14D, NAUGHTY = 0xFFFF7A66, WHISPER = 0xFFCF8BEF;

    enum Section {PEOPLE, DMS, INTEL, LOG, SETTINGS}

    enum Filter {
        ALL("All"), ONLINE("Online"), NEAR("Seen today"), FRIEND("Friends"), NICE("Nice"), LOOK("Look Into"), NAUGHTY("Naughty"), NONE("Not listed");
        final String label;

        Filter(String label) {
            this.label = label;
        }
    }

    private final Activity act;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final LinearLayout root;
    private final LinearLayout sections;
    private final TextView headerSub;
    private final FrameLayout body;
    private Section section = Section.PEOPLE;
    private Filter filter = Filter.ALL;
    private String query = "";
    private boolean byGroup = true;
    private final java.util.Set<String> folded = new java.util.HashSet<String>();
    /** The player page open in People (null: the list). */
    private String person;
    /** The DM conversation open (null: the list). */
    private String dmWith;
    private String logFilter = "all";
    private int builtVersion = -1;
    private boolean shown;
    private String importStatus = "";
    private boolean importing;

    NonPage(Activity act) {
        this.act = act;
        root = new LinearLayout(act);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(14), dp(4), dp(14), dp(6));

        LinearLayout head = new LinearLayout(act);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = text("Naughty or Nice", 20, TEXT, true);
        head.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        headerSub = text("", 12, MUTED, false);
        head.addView(headerSub);
        root.addView(head);

        HorizontalScrollView hs = new HorizontalScrollView(act);
        hs.setHorizontalScrollBarEnabled(false);
        sections = new LinearLayout(act);
        sections.setOrientation(LinearLayout.HORIZONTAL);
        hs.addView(sections);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.topMargin = dp(8);
        root.addView(hs, slp);

        body = new FrameLayout(act);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1);
        blp.topMargin = dp(8);
        root.addView(body, blp);
    }

    View view() {
        return root;
    }

    // ================================================================ life

    private final Runnable tick = new Runnable() {
        public void run() {
            if (!shown) return;
            if (NonIntel.version() != builtVersion) refresh(false);
            ui.postDelayed(this, 1000);
        }
    };

    void onShown() {
        if (shown) return;
        shown = true;
        refresh(true);
        ui.post(tick);
    }

    void onHidden() {
        shown = false;
        ui.removeCallbacks(tick);
        NonIntel.viewing(null);
    }

    /** Rebuilds what's on screen. Not while you're typing in it (it would lose your place), unless forced. */
    void refresh(boolean force) {
        if (!force && typingInBody()) return;
        builtVersion = NonIntel.version();
        String server = NonIntel.server();
        int online = 0;
        for (NonIntel.Person p : NonIntel.people()) if (p.online) online++;
        headerSub.setText(server.isEmpty() ? "no server yet" : server + (World.inWorld ? "  •  " + online + " online" : "  •  offline"));
        buildSections();
        body.removeAllViews();
        NonIntel.viewing(section == Section.DMS ? dmWith : null);
        switch (section) {
            case PEOPLE:
                body.addView(person != null ? personView(person) : peopleView());
                break;
            case DMS:
                body.addView(dmWith != null ? threadView(dmWith) : dmListView());
                break;
            case INTEL:
                body.addView(intelView());
                break;
            case LOG:
                body.addView(logView());
                break;
            default:
                body.addView(settingsView());
        }
    }

    private boolean typingInBody() {
        View f = act.getCurrentFocus();
        if (!(f instanceof EditText)) return false;
        for (View v = f; v != null; v = v.getParent() instanceof View ? (View) v.getParent() : null) {
            if (v == body) return !Boolean.TRUE.equals(f.getTag());
        }
        return false;
    }

    private void go(Section s) {
        section = s;
        if (s != Section.PEOPLE) person = null;
        refresh(true);
    }

    private void buildSections() {
        sections.removeAllViews();
        int unread = NonIntel.unread();
        chip(sections, "People", section == Section.PEOPLE, ACCENT, new Runnable() {
            public void run() {
                person = null;
                go(Section.PEOPLE);
            }
        });
        chip(sections, unread > 0 ? "DMs  " + unread : "DMs", section == Section.DMS, unread > 0 ? WHISPER : ACCENT, new Runnable() {
            public void run() {
                dmWith = null;
                go(Section.DMS);
            }
        });
        chip(sections, "Intel", section == Section.INTEL, ACCENT, new Runnable() {
            public void run() {
                go(Section.INTEL);
            }
        });
        chip(sections, "Log", section == Section.LOG, ACCENT, new Runnable() {
            public void run() {
                go(Section.LOG);
            }
        });
        chip(sections, "Settings", section == Section.SETTINGS, ACCENT, new Runnable() {
            public void run() {
                go(Section.SETTINGS);
            }
        });
    }

    // ================================================================ People

    /** The search box lives outside the rebuilt list, so typing in it isn't interrupted. */
    private EditText search;

    private View peopleView() {
        LinearLayout col = new LinearLayout(act);
        col.setOrientation(LinearLayout.VERTICAL);
        if (search == null) {
            search = edit("Search names, groups, notes", InputType.TYPE_CLASS_TEXT);
            search.setTag(Boolean.TRUE);
            search.addTextChangedListener(new TextWatcher() {
                public void beforeTextChanged(CharSequence s, int a, int b, int c) {
                }

                public void onTextChanged(CharSequence s, int a, int b, int c) {
                }

                public void afterTextChanged(Editable e) {
                    query = e.toString().trim();
                    refresh(true);
                    search.requestFocus();
                }
            });
        }
        if (search.getParent() != null) ((ViewGroup) search.getParent()).removeView(search);
        col.addView(search);
        LinearLayout filters = chipRow(col);
        for (final Filter f : Filter.values()) {
            chip(filters, f.label, filter == f, colorOf(f), new Runnable() {
                public void run() {
                    filter = f;
                    refresh(true);
                }
            });
        }
        chip(filters, (byGroup ? "✔ " : "") + "By group", byGroup, ACCENT2, new Runnable() {
            public void run() {
                byGroup = !byGroup;
                refresh(true);
            }
        });

        ScrollView sv = new ScrollView(act);
        LinearLayout list = new LinearLayout(act);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(0, dp(6), 0, dp(20));
        sv.addView(list);
        col.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        List<NonIntel.Person> people = matching();
        if (query.matches("[A-Za-z0-9_]{3,16}") && NonIntel.get(query) == null) {
            final String name = query;
            TextView add = text("+ Add " + name + " (to put them on a list before they're seen)", 14, ACCENT2, true);
            add.setPadding(dp(12), dp(12), dp(12), dp(12));
            add.setBackground(MainActivity.round(SURFACE, dp(12), STROKE));
            add.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    NonIntel.add(name);
                    person = name;
                    refresh(true);
                }
            });
            list.addView(add);
        }
        if (people.isEmpty()) {
            list.addView(text(NonIntel.people().isEmpty()
                    ? "Nobody yet. While the bot is online, everyone who joins, comes near or talks shows up here. Import your lists from the PC mod in Settings."
                    : "Nobody matches.", 13, MUTED, false));
            return col;
        }
        boolean anyGroups = false;
        for (NonIntel.Person p : people) anyGroups |= !p.groups.isEmpty();
        int shown = 0, limit = 250;
        if (byGroup && anyGroups) {
            Map<String, List<NonIntel.Person>> groups = new LinkedHashMap<String, List<NonIntel.Person>>();
            final Map<String, Integer> onlineIn = new java.util.HashMap<String, Integer>();
            List<NonIntel.Person> none = new ArrayList<NonIntel.Person>();
            for (NonIntel.Person p : people) {
                if (p.groups.isEmpty()) none.add(p);
                else {
                    String g = p.groups.get(0);
                    if (!groups.containsKey(g)) groups.put(g, new ArrayList<NonIntel.Person>());
                    groups.get(g).add(p);
                }
            }
            List<String> order = new ArrayList<String>(groups.keySet());
            for (String g : order) {
                int n = 0;
                for (NonIntel.Person p : groups.get(g)) if (p.online) n++;
                onlineIn.put(g, n);
            }
            final Map<String, List<NonIntel.Person>> gs = groups;
            Collections.sort(order, new Comparator<String>() {
                public int compare(String a, String b) {
                    int c = onlineIn.get(b) - onlineIn.get(a);
                    if (c != 0) return c;
                    c = gs.get(b).size() - gs.get(a).size();
                    return c != 0 ? c : a.compareToIgnoreCase(b);
                }
            });
            if (!none.isEmpty()) {
                order.add("");
                groups.put("", none);
            }
            for (final String g : order) {
                List<NonIntel.Person> members = groups.get(g);
                int on = 0;
                for (NonIntel.Person p : members) if (p.online) on++;
                final boolean isFolded = folded.contains(g);
                TextView h = text((isFolded ? "▸  " : "▾  ") + (g.isEmpty() ? "No group" : g) + "   " + members.size() + (on > 0 ? "  •  " + on + " online" : ""),
                        14, g.isEmpty() ? MUTED : groupColor(g), true);
                h.setPadding(dp(10), dp(10), dp(10), dp(10));
                h.setBackground(MainActivity.round(SURFACE2, dp(10), STROKE));
                h.setOnClickListener(new View.OnClickListener() {
                    public void onClick(View v) {
                        if (isFolded) folded.remove(g);
                        else folded.add(g);
                        refresh(true);
                    }
                });
                LinearLayout.LayoutParams hl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                hl.topMargin = dp(8);
                list.addView(h, hl);
                if (isFolded) continue;
                for (NonIntel.Person p : members) {
                    if (shown++ >= limit) break;
                    list.addView(personRow(p));
                }
            }
        } else {
            for (NonIntel.Person p : people) {
                if (shown++ >= limit) break;
                list.addView(personRow(p));
            }
        }
        if (people.size() > limit) list.addView(text("Showing " + limit + " of " + people.size() + ". Search or filter to find the rest.", 12, MUTED, false));
        return col;
    }

    private List<NonIntel.Person> matching() {
        List<NonIntel.Person> out = new ArrayList<NonIntel.Person>();
        String q = query.toLowerCase(Locale.ROOT);
        long today = System.currentTimeMillis() - 86400000L;
        for (NonIntel.Person p : NonIntel.people()) {
            switch (filter) {
                case ONLINE:
                    if (!p.online) continue;
                    break;
                case NEAR:
                    if (p.lastSeen < today) continue;
                    break;
                case FRIEND:
                    if (p.verdict != NonIntel.Verdict.FRIEND) continue;
                    break;
                case NICE:
                    if (p.verdict != NonIntel.Verdict.NICE) continue;
                    break;
                case LOOK:
                    if (p.verdict != NonIntel.Verdict.LOOK) continue;
                    break;
                case NAUGHTY:
                    if (p.verdict != NonIntel.Verdict.NAUGHTY) continue;
                    break;
                case NONE:
                    if (p.verdict != NonIntel.Verdict.NONE) continue;
                    break;
                default:
            }
            if (!q.isEmpty() && !p.name.toLowerCase(Locale.ROOT).contains(q) && !p.note.toLowerCase(Locale.ROOT).contains(q)
                    && !NonIntel.join(p.groups, " ").toLowerCase(Locale.ROOT).contains(q)) continue;
            out.add(p);
        }
        Collections.sort(out, new Comparator<NonIntel.Person>() {
            public int compare(NonIntel.Person a, NonIntel.Person b) {
                if (a.online != b.online) return a.online ? -1 : 1;
                boolean an = a.nearSince > 0, bn = b.nearSince > 0;
                if (an != bn) return an ? -1 : 1;
                return Long.compare(Math.max(b.lastSeen, b.lastOnline), Math.max(a.lastSeen, a.lastOnline));
            }
        });
        return out;
    }

    private View personRow(final NonIntel.Person p) {
        LinearLayout row = new LinearLayout(act);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(10), dp(9), dp(10), dp(9));
        row.setBackground(MainActivity.round(SURFACE, dp(12), p.verdict == NonIntel.Verdict.NAUGHTY ? (NAUGHTY & 0x00FFFFFF) | 0x66000000 : STROKE));
        TextView badge = text(mark(p.verdict), 14, Color.WHITE, true);
        badge.setGravity(Gravity.CENTER);
        badge.setBackground(MainActivity.round(p.verdict == NonIntel.Verdict.NONE ? SURFACE2 : (verdictColor(p.verdict) & 0x00FFFFFF) | 0x55000000, dp(14), verdictColor(p.verdict)));
        row.addView(badge, new LinearLayout.LayoutParams(dp(28), dp(28)));
        LinearLayout mid = new LinearLayout(act);
        mid.setOrientation(LinearLayout.VERTICAL);
        mid.setPadding(dp(10), 0, 0, 0);
        TextView name = text(p.name + (p.groups.isEmpty() ? "" : "  [" + NonIntel.join(p.groups, ", ") + "]"), 15,
                p.verdict == NonIntel.Verdict.NONE ? TEXT : verdictColor(p.verdict), true);
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        mid.addView(name);
        TextView status = text(status(p), 12, p.online ? GOOD : MUTED, false);
        status.setSingleLine(true);
        status.setEllipsize(android.text.TextUtils.TruncateAt.END);
        mid.addView(status);
        row.addView(mid, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        row.setClickable(true);
        row.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                person = p.name;
                refresh(true);
            }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(6);
        row.setLayoutParams(lp);
        return row;
    }

    private String status(NonIntel.Person p) {
        StringBuilder sb = new StringBuilder();
        if (p.nearSince > 0) sb.append("● near you now");
        else if (p.online) sb.append("● online");
        else if (p.lastOnline > 0) sb.append("last online ").append(NonIntel.ago(p.lastOnline));
        else sb.append("not seen online");
        if (p.posAt > 0 && p.nearSince == 0) sb.append("  •  seen ").append(NonIntel.ago(p.posAt));
        if (p.sightings > 0) sb.append("  •  near ").append(p.sightings).append("×");
        if (!p.note.isEmpty()) sb.append("  •  ").append(p.note.replace('\n', ' '));
        return sb.toString();
    }

    // ---- One player ----

    private View personView(final String name) {
        final NonIntel.Person p = NonIntel.get(name);
        ScrollView sv = new ScrollView(act);
        LinearLayout col = new LinearLayout(act);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(0, 0, 0, dp(24));
        sv.addView(col);
        TextView back = text("‹ People", 14, ACCENT2, true);
        back.setPadding(0, dp(4), 0, dp(8));
        back.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                person = null;
                refresh(true);
            }
        });
        col.addView(back);
        if (p == null) {
            col.addView(text(name + " was forgotten.", 14, MUTED, false));
            return sv;
        }
        col.addView(text(p.name, 24, p.verdict == NonIntel.Verdict.NONE ? TEXT : verdictColor(p.verdict), true));
        col.addView(text(status(p), 13, p.online ? GOOD : MUTED, false));

        LinearLayout lists = chipRow(col);
        for (final NonIntel.Verdict v : NonIntel.Verdict.values()) {
            chip(lists, (v == NonIntel.Verdict.NONE ? "" : mark(v) + " ") + v.label, p.verdict == v, verdictColor(v), new Runnable() {
                public void run() {
                    NonIntel.setVerdict(p.name, v);
                    toast(v == NonIntel.Verdict.NONE ? p.name + " is on no list" : p.name + " is on your " + v.label + " list");
                    refresh(true);
                }
            });
        }

        LinearLayout actions = row(col);
        actions.addView(button("✉  Message", WHISPER, new Runnable() {
            public void run() {
                dmWith = p.name;
                section = Section.DMS;
                refresh(true);
            }
        }), weight());
        if (p.posAt > 0) {
            actions.addView(button("Copy where seen", SURFACE2, new Runnable() {
                public void run() {
                    copy(String.format(Locale.US, "%.0f %.0f %.0f", p.x, p.y, p.z), "Copied where " + p.name + " was seen");
                }
            }), weight());
        }

        LinearLayout info = card(col);
        info.addView(text("What's known", 15, TEXT, true));
        fact(info, "First seen", p.firstSeen > 0 ? date(p.firstSeen) + " (" + NonIntel.ago(p.firstSeen) + ")" : "—");
        fact(info, "Online", p.online ? "now" : p.lastOnline > 0 ? "last " + NonIntel.ago(p.lastOnline) : "not seen online");
        if (p.lastJoin > 0) fact(info, "Last joined", date(p.lastJoin));
        fact(info, "Near you", p.sightings == 0 ? "never" : p.sightings + " times, " + NonIntel.span(p.nearMs) + " in all");
        if (p.posAt > 0) fact(info, "Last seen at", String.format(Locale.US, "%.0f, %.0f, %.0f%s, %s", p.x, p.y, p.z,
                p.dim.isEmpty() ? "" : " (" + p.dim.replace("minecraft:", "").replace("the_", "") + ")", NonIntel.ago(p.posAt)));
        fact(info, "Messages", String.valueOf(p.messages));
        if (!p.uuid.isEmpty()) fact(info, "UUID", p.uuid);

        LinearLayout notes = card(col);
        notes.addView(text("Groups (comma between)", 13, MUTED, false));
        final EditText groups = edit("e.g. SCG, alts", InputType.TYPE_CLASS_TEXT);
        groups.setText(NonIntel.join(p.groups, ", "));
        notes.addView(groups);
        notes.addView(text("Note", 13, MUTED, false));
        final EditText note = edit("Where their base is, who they play with…", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        note.setSingleLine(false);
        note.setMinLines(2);
        note.setText(p.note);
        notes.addView(note);
        notes.addView(button("Save groups and note", ACCENT, new Runnable() {
            public void run() {
                List<String> gs = new ArrayList<String>();
                for (String g : groups.getText().toString().split(",")) if (!g.trim().isEmpty()) gs.add(g.trim());
                NonIntel.setGroups(p.name, gs);
                NonIntel.setNote(p.name, note.getText().toString().trim());
                hideKeyboard(note);
                toast("Saved");
                refresh(true);
            }
        }));

        if (!p.chat.isEmpty()) {
            LinearLayout said = card(col);
            said.addView(text("What they said lately", 15, TEXT, true));
            for (int i = p.chat.size() - 1; i >= 0 && i >= p.chat.size() - 25; i--) {
                NonIntel.Line l = p.chat.get(i);
                said.addView(text(clock(l.t) + "  " + l.text, 13, TEXT, false));
            }
        }

        TextView forget = text("Forget " + p.name, 13, BAD, false);
        forget.setPadding(0, dp(18), 0, 0);
        forget.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                new AlertDialog.Builder(act).setTitle("Forget " + p.name + "?").setMessage("Their list, groups, note and what's known about them go.")
                        .setPositiveButton("Forget", new android.content.DialogInterface.OnClickListener() {
                            public void onClick(android.content.DialogInterface d, int w) {
                                NonIntel.forget(p.name);
                                person = null;
                                refresh(true);
                            }
                        }).setNegativeButton("Cancel", null).show();
            }
        });
        col.addView(forget);
        return sv;
    }

    private void fact(LinearLayout parent, String label, String value) {
        LinearLayout r = new LinearLayout(act);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setPadding(0, dp(4), 0, 0);
        TextView l = text(label, 13, MUTED, false);
        r.addView(l, new LinearLayout.LayoutParams(dp(104), ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView v = text(value, 13, TEXT, false);
        v.setTextIsSelectable(true);
        r.addView(v, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        parent.addView(r);
    }

    // ================================================================ DMs

    private EditText newDm;

    private View dmListView() {
        LinearLayout col = new LinearLayout(act);
        col.setOrientation(LinearLayout.VERTICAL);
        LinearLayout start = new LinearLayout(act);
        start.setOrientation(LinearLayout.HORIZONTAL);
        start.setGravity(Gravity.CENTER_VERTICAL);
        if (newDm == null) {
            newDm = edit("New: type a name", InputType.TYPE_CLASS_TEXT);
            newDm.setTag(Boolean.TRUE);
            newDm.setImeOptions(EditorInfo.IME_ACTION_GO);
            newDm.setOnEditorActionListener(new TextView.OnEditorActionListener() {
                public boolean onEditorAction(TextView v, int a, KeyEvent e) {
                    openNewDm();
                    return true;
                }
            });
        }
        if (newDm.getParent() != null) ((ViewGroup) newDm.getParent()).removeView(newDm);
        start.addView(newDm, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        View open = button("Open", ACCENT, new Runnable() {
            public void run() {
                openNewDm();
            }
        });
        LinearLayout.LayoutParams olp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        olp.leftMargin = dp(8);
        start.addView(open, olp);
        col.addView(start);

        ScrollView sv = new ScrollView(act);
        LinearLayout list = new LinearLayout(act);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(0, dp(6), 0, dp(20));
        sv.addView(list);
        col.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        List<NonIntel.Thread> threads = NonIntel.threads();
        if (threads.isEmpty()) {
            list.addView(text("No private messages yet. Whispers you get or send show up here, one conversation per player. "
                    + "Type below and tap Send: it goes as /" + NonIntel.whisperCommand + " <name> for you.", 13, MUTED, false));
        }
        for (final NonIntel.Thread t : threads) {
            LinearLayout row = new LinearLayout(act);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(12), dp(10), dp(12), dp(10));
            row.setBackground(MainActivity.round(SURFACE, dp(12), STROKE));
            LinearLayout mid = new LinearLayout(act);
            mid.setOrientation(LinearLayout.VERTICAL);
            NonIntel.Person p = NonIntel.get(t.name);
            boolean on = NonIntel.online(t.name);
            int unread = t.unread();
            mid.addView(text((on ? "● " : "") + t.name, 15, p == null || p.verdict == NonIntel.Verdict.NONE ? TEXT : verdictColor(p.verdict), true));
            NonIntel.Line last = t.msgs.isEmpty() ? null : t.msgs.get(t.msgs.size() - 1);
            TextView prev = text(last == null ? "No messages yet" : last.state == NonIntel.FAILED ? "✖ Not sent: " + last.text : (last.me ? "You: " : "") + last.text,
                    13, last != null && last.state == NonIntel.FAILED ? BAD : unread > 0 ? TEXT : MUTED, false);
            prev.setSingleLine(true);
            prev.setEllipsize(android.text.TextUtils.TruncateAt.END);
            mid.addView(prev);
            row.addView(mid, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            LinearLayout right = new LinearLayout(act);
            right.setOrientation(LinearLayout.VERTICAL);
            right.setGravity(Gravity.END);
            right.addView(text(last == null ? "" : NonIntel.ago(last.t), 11, unread > 0 ? WHISPER : MUTED, false));
            if (unread > 0) {
                TextView b = text(String.valueOf(unread), 12, Color.WHITE, true);
                b.setPadding(dp(8), dp(1), dp(8), dp(1));
                b.setBackground(MainActivity.round(WHISPER, dp(10), WHISPER));
                right.addView(b);
            }
            row.addView(right);
            row.setClickable(true);
            row.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    dmWith = t.name;
                    refresh(true);
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = dp(6);
            list.addView(row, lp);
        }
        return col;
    }

    private void openNewDm() {
        String n = newDm.getText().toString().trim();
        if (!n.matches("[A-Za-z0-9_]{1,16}")) {
            toast("Type a player's name");
            return;
        }
        newDm.setText("");
        hideKeyboard(newDm);
        dmWith = n;
        refresh(true);
    }

    /** The message box stays put while the conversation above it is rebuilt. */
    private EditText dmInput;
    private ScrollView dmScroll;

    private View threadView(final String name) {
        LinearLayout col = new LinearLayout(act);
        col.setOrientation(LinearLayout.VERTICAL);
        NonIntel.Thread t = NonIntel.threadCopy(name);
        NonIntel.Person p = NonIntel.get(name);
        boolean on = NonIntel.online(name);

        LinearLayout head = new LinearLayout(act);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView back = text("‹ ", 22, ACCENT2, true);
        back.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                dmWith = null;
                hideKeyboard(back(v));
                refresh(true);
            }
        });
        head.addView(back);
        LinearLayout who = new LinearLayout(act);
        who.setOrientation(LinearLayout.VERTICAL);
        who.addView(text(t.name, 17, p == null || p.verdict == NonIntel.Verdict.NONE ? TEXT : verdictColor(p.verdict), true));
        who.addView(text(on ? "● online" : p != null && p.lastOnline > 0 ? "last online " + NonIntel.ago(p.lastOnline) : "not online", 12, on ? GOOD : MUTED, false));
        head.addView(who, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView more = text("⋮", 22, MUTED, true);
        more.setPadding(dp(12), 0, dp(4), 0);
        more.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                threadMenu(name);
            }
        });
        head.addView(more);
        col.addView(head);

        dmScroll = new ScrollView(act);
        LinearLayout msgs = new LinearLayout(act);
        msgs.setOrientation(LinearLayout.VERTICAL);
        msgs.setPadding(0, dp(6), 0, dp(6));
        dmScroll.addView(msgs);
        col.addView(dmScroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        if (t.msgs.isEmpty()) {
            msgs.addView(text("No messages with " + t.name + " yet. Type below and tap Send: it goes as /" + NonIntel.whisperCommand + " " + t.name + " for you.", 13, MUTED, false));
        }
        String lastDay = "";
        int from = Math.max(0, t.msgs.size() - 300);
        for (int i = from; i < t.msgs.size(); i++) {
            final NonIntel.Line l = t.msgs.get(i);
            String d = day(l.t);
            if (!d.equals(lastDay)) {
                TextView dv = text(d, 11, MUTED, false);
                dv.setGravity(Gravity.CENTER);
                dv.setPadding(0, dp(8), 0, dp(4));
                msgs.addView(dv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                lastDay = d;
            }
            LinearLayout line = new LinearLayout(act);
            line.setOrientation(LinearLayout.VERTICAL);
            line.setGravity(l.me ? Gravity.END : Gravity.START);
            TextView bubble = text(l.text, 15, l.state == NonIntel.SENDING ? MUTED : TEXT, false);
            bubble.setPadding(dp(12), dp(8), dp(12), dp(8));
            bubble.setMaxWidth((int) (act.getResources().getDisplayMetrics().widthPixels * 0.75f));
            bubble.setBackground(MainActivity.round(l.me ? 0xFF2A2350 : SURFACE2, dp(14), l.state == NonIntel.FAILED ? BAD : l.me ? 0xFF4C3F8F : STROKE));
            bubble.setTextIsSelectable(true);
            line.addView(bubble);
            String meta = clock(l.t) + (l.state == NonIntel.SENDING ? "  sending…" : "");
            TextView mt = text(meta, 10, MUTED, false);
            line.addView(mt);
            if (l.state == NonIntel.FAILED) {
                TextView err = text("✖ Not sent: " + (l.error == null ? "the server said no" : l.error) + "  ·  tap to retry", 12, BAD, false);
                err.setOnClickListener(new View.OnClickListener() {
                    public void onClick(View v) {
                        String why = NonIntel.send(name, l.text);
                        if (why != null) toast(why);
                        refresh(true);
                    }
                });
                line.addView(err);
            }
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = dp(4);
            msgs.addView(line, lp);
        }
        dmScroll.post(new Runnable() {
            public void run() {
                dmScroll.fullScroll(View.FOCUS_DOWN);
            }
        });

        LinearLayout input = new LinearLayout(act);
        input.setOrientation(LinearLayout.HORIZONTAL);
        input.setGravity(Gravity.CENTER_VERTICAL);
        input.setPadding(0, dp(6), 0, 0);
        if (dmInput == null) {
            dmInput = edit("Message", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
            dmInput.setTag(Boolean.TRUE);
            dmInput.setImeOptions(EditorInfo.IME_ACTION_SEND);
            dmInput.setOnEditorActionListener(new TextView.OnEditorActionListener() {
                public boolean onEditorAction(TextView v, int a, KeyEvent e) {
                    sendDm();
                    return true;
                }
            });
        }
        if (dmInput.getParent() != null) ((ViewGroup) dmInput.getParent()).removeView(dmInput);
        dmInput.setHint(World.inWorld ? "Message " + t.name + (on ? "" : " (not online: may not get there)") : "Start AFK to send");
        input.addView(dmInput, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        View send = button("Send", ACCENT, new Runnable() {
            public void run() {
                sendDm();
            }
        });
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.leftMargin = dp(8);
        input.addView(send, slp);
        col.addView(input);
        return col;
    }

    private EditText back(View v) {
        return dmInput != null ? dmInput : newDm;
    }

    private void sendDm() {
        String text = dmInput.getText().toString().trim();
        if (text.isEmpty() || dmWith == null) return;
        String why = NonIntel.send(dmWith, text);
        if (why != null) {
            toast(why);
            return;
        }
        dmInput.setText("");
        refresh(true);
        dmInput.requestFocus();
    }

    private void threadMenu(final String name) {
        final String[] items = {"Their page", "Copy conversation", "Delete conversation"};
        new AlertDialog.Builder(act).setTitle(name).setItems(items, new android.content.DialogInterface.OnClickListener() {
            public void onClick(android.content.DialogInterface d, int which) {
                if (which == 0) {
                    NonIntel.add(name);
                    person = name;
                    dmWith = null;
                    section = Section.PEOPLE;
                    refresh(true);
                } else if (which == 1) {
                    StringBuilder sb = new StringBuilder("Private messages with " + name + "\n");
                    for (NonIntel.Line l : NonIntel.threadCopy(name).msgs) {
                        sb.append('[').append(date(l.t)).append("] ").append(l.me ? "You: " : name + ": ").append(l.text)
                                .append(l.state == NonIntel.FAILED ? " (not sent)" : "").append('\n');
                    }
                    copy(sb.toString(), "Copied the conversation");
                } else {
                    new AlertDialog.Builder(act).setTitle("Delete your messages with " + name + "?")
                            .setPositiveButton("Delete", new android.content.DialogInterface.OnClickListener() {
                                public void onClick(android.content.DialogInterface d2, int w) {
                                    NonIntel.deleteThread(name);
                                    dmWith = null;
                                    refresh(true);
                                }
                            }).setNegativeButton("Cancel", null).show();
                }
            }
        }).show();
    }

    /** Opens a conversation (from a whisper notification). */
    void openDm(String name) {
        dmWith = name;
        section = Section.DMS;
        refresh(true);
    }

    // ================================================================ Intel and Log

    private View intelView() {
        ScrollView sv = new ScrollView(act);
        LinearLayout col = new LinearLayout(act);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(0, 0, 0, dp(24));
        sv.addView(col);
        List<NonIntel.Event> all = NonIntel.events();
        LinearLayout coords = card(col);
        coords.addView(text("Coordinates posted in chat", 15, TEXT, true));
        coords.addView(text("Tap one to copy it.", 12, MUTED, false));
        int n = 0;
        for (int i = all.size() - 1; i >= 0 && n < 100; i--) {
            final NonIntel.Event e = all.get(i);
            if (!e.kind.equals("coords")) continue;
            n++;
            eventLine(coords, e, LOOK);
        }
        if (n == 0) coords.addView(text("None yet.", 13, MUTED, false));
        LinearLayout mentions = card(col);
        mentions.addView(text("Your name mentioned", 15, TEXT, true));
        n = 0;
        for (int i = all.size() - 1; i >= 0 && n < 60; i--) {
            NonIntel.Event e = all.get(i);
            if (!e.kind.equals("mention")) continue;
            n++;
            eventLine(mentions, e, WARN);
        }
        if (n == 0) mentions.addView(text("Nobody yet.", 13, MUTED, false));
        LinearLayout naughty = card(col);
        naughty.addView(text("Naughty and Look Into players near you", 15, TEXT, true));
        n = 0;
        for (int i = all.size() - 1; i >= 0 && n < 60; i--) {
            NonIntel.Event e = all.get(i);
            if (!e.kind.equals("seen")) continue;
            NonIntel.Person p = NonIntel.get(e.name);
            if (p == null || (p.verdict != NonIntel.Verdict.NAUGHTY && p.verdict != NonIntel.Verdict.LOOK)) continue;
            n++;
            eventLine(naughty, e, verdictColor(p.verdict));
        }
        if (n == 0) naughty.addView(text("None.", 13, MUTED, false));
        return sv;
    }

    private void eventLine(LinearLayout parent, final NonIntel.Event e, int color) {
        String pos = e.hasPos ? String.format(Locale.US, "  (%.0f, %.0f, %.0f)", e.x, e.y, e.z) : "";
        TextView t = text(e.name + "  ·  " + NonIntel.ago(e.t) + "\n" + e.text + pos, 13, TEXT, false);
        t.setPadding(dp(8), dp(6), dp(8), dp(6));
        t.setBackground(MainActivity.round(SURFACE2, dp(10), (color & 0x00FFFFFF) | 0x55000000));
        t.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                copy(e.hasPos ? String.format(Locale.US, "%.0f %.0f %.0f", e.x, e.y, e.z) : e.text, "Copied");
            }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(6);
        parent.addView(t, lp);
    }

    private View logView() {
        LinearLayout col = new LinearLayout(act);
        col.setOrientation(LinearLayout.VERTICAL);
        LinearLayout chips = chipRow(col);
        String[][] kinds = {{"all", "All"}, {"join", "Joins"}, {"leave", "Leaves"}, {"seen", "Came near"}, {"listed", "Listed players"}};
        for (final String[] k : kinds) {
            chip(chips, k[1], logFilter.equals(k[0]), ACCENT, new Runnable() {
                public void run() {
                    logFilter = k[0];
                    refresh(true);
                }
            });
        }
        ScrollView sv = new ScrollView(act);
        LinearLayout list = new LinearLayout(act);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(0, dp(6), 0, dp(20));
        sv.addView(list);
        col.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        List<NonIntel.Event> all = NonIntel.events();
        Map<String, NonIntel.Verdict> verdicts = new java.util.HashMap<String, NonIntel.Verdict>();
        for (NonIntel.Person p : NonIntel.people()) verdicts.put(NonIntel.key(p.name), p.verdict);
        int n = 0;
        String lastDay = "";
        for (int i = all.size() - 1; i >= 0 && n < 400; i--) {
            final NonIntel.Event e = all.get(i);
            NonIntel.Verdict v = e.name.isEmpty() ? null : verdicts.get(NonIntel.key(e.name));
            if (v == null) v = NonIntel.Verdict.NONE;
            if (e.kind.equals("coords") || e.kind.equals("mention")) continue;
            if (logFilter.equals("listed") ? v == NonIntel.Verdict.NONE : !logFilter.equals("all") && !e.kind.equals(logFilter)) continue;
            n++;
            String d = day(e.t);
            if (!d.equals(lastDay)) {
                list.addView(text(d, 12, MUTED, true));
                lastDay = d;
            }
            String icon = e.kind.equals("join") ? "→" : e.kind.equals("leave") ? "←" : e.kind.equals("seen") ? "◎" : "•";
            String pos = e.hasPos ? String.format(Locale.US, "  (%.0f, %.0f, %.0f)", e.x, e.y, e.z) : "";
            int c = e.kind.equals("you") ? ACCENT2 : v == NonIntel.Verdict.NONE ? TEXT : verdictColor(v);
            final String who = e.name;
            TextView t = text(clock(e.t) + "  " + icon + "  " + (who.isEmpty() ? "" : who + " ") + e.text + pos, 13, c, false);
            t.setPadding(0, dp(3), 0, dp(3));
            if (!who.isEmpty()) {
                t.setOnClickListener(new View.OnClickListener() {
                    public void onClick(View v) {
                        person = who;
                        section = Section.PEOPLE;
                        refresh(true);
                    }
                });
            }
            list.addView(t);
        }
        if (n == 0) list.addView(text("Nothing yet. Joins, leaves and players coming near you are logged here while the bot is online.", 13, MUTED, false));
        return col;
    }

    // ================================================================ Settings

    private EditText importBox;

    private View settingsView() {
        ScrollView sv = new ScrollView(act);
        LinearLayout col = new LinearLayout(act);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(0, 0, 0, dp(24));
        sv.addView(col);

        LinearLayout alerts = card(col);
        alerts.addView(text("Phone alerts", 15, TEXT, true));
        toggle(alerts, "A Naughty player joins", NonIntel.alertNaughtyJoin, new Set() {
            public void set(boolean v) {
                NonIntel.alertNaughtyJoin = v;
            }
        });
        toggle(alerts, "A Naughty player comes near you", NonIntel.alertNaughtyNear, new Set() {
            public void set(boolean v) {
                NonIntel.alertNaughtyNear = v;
            }
        });
        toggle(alerts, "A Look Into player joins", NonIntel.alertLookJoin, new Set() {
            public void set(boolean v) {
                NonIntel.alertLookJoin = v;
            }
        });
        toggle(alerts, "Someone whispers you", NonIntel.alertWhisper, new Set() {
            public void set(boolean v) {
                NonIntel.alertWhisper = v;
            }
        });
        toggle(alerts, "Someone says your name", NonIntel.alertMention, new Set() {
            public void set(boolean v) {
                NonIntel.alertMention = v;
            }
        });

        LinearLayout w = card(col);
        w.addView(text("Whisper command", 15, TEXT, true));
        w.addView(text("DMs are sent as /<command> <name> <message>. Constantiam uses w.", 12, MUTED, false));
        final EditText cmd = edit("w", InputType.TYPE_CLASS_TEXT);
        cmd.setText(NonIntel.whisperCommand);
        cmd.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            public void afterTextChanged(Editable e) {
                String v = e.toString().trim().replaceFirst("^/", "");
                if (!v.isEmpty() && v.matches("[A-Za-z0-9_:.-]{1,24}")) {
                    NonIntel.whisperCommand = v;
                    NonIntel.saveSettings();
                }
            }
        });
        w.addView(cmd);

        LinearLayout imp = card(col);
        imp.addView(text("Import from Naughty or Nice on PC", 15, TEXT, true));
        imp.addView(text("In the mod: Share → Get share link, then paste it here (or a share code). Lists, groups and notes are added to "
                + (NonIntel.server().isEmpty() ? "the server the bot joins next" : NonIntel.server()) + ". Lists you set here aren't wiped.", 12, MUTED, false));
        if (importBox == null) {
            importBox = edit("NN2:… or NN1:…", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
            importBox.setTag(Boolean.TRUE);
        }
        if (importBox.getParent() != null) ((ViewGroup) importBox.getParent()).removeView(importBox);
        imp.addView(importBox);
        imp.addView(button(importing ? "Importing…" : "Import", importing ? SURFACE2 : ACCENT, new Runnable() {
            public void run() {
                startImport();
            }
        }));
        if (!importStatus.isEmpty()) imp.addView(text(importStatus, 13, importStatus.startsWith("Imported") ? GOOD : BAD, false));

        LinearLayout data = card(col);
        data.addView(text("This server's data", 15, TEXT, true));
        data.addView(text((NonIntel.server().isEmpty() ? "No server yet." : NonIntel.server() + ": " + NonIntel.people().size() + " players, "
                + NonIntel.threads().size() + " conversations, " + NonIntel.events().size() + " log lines.")
                + " Kept on this phone only.", 12, MUTED, false));
        if (!NonIntel.server().isEmpty()) {
            data.addView(button("Clear it all", 0xFF3A1D24, new Runnable() {
                public void run() {
                    new AlertDialog.Builder(act).setTitle("Clear everything for " + NonIntel.server() + "?")
                            .setMessage("Your lists, groups, notes, DMs and the log for this server go. This can't be undone.")
                            .setPositiveButton("Clear", new android.content.DialogInterface.OnClickListener() {
                                public void onClick(android.content.DialogInterface d, int which) {
                                    NonIntel.clearServer();
                                    refresh(true);
                                }
                            }).setNegativeButton("Cancel", null).show();
                }
            }));
        }
        col.addView(text("Naughty or Nice is an add-on: switch it off in Modules to hide this tab (your data is kept).", 12, MUTED, false));
        return sv;
    }

    private void startImport() {
        if (importing) return;
        final String text = importBox.getText().toString();
        if (NonIntel.server().isEmpty()) {
            importStatus = "Start AFK once first, so the add-on knows which server the lists are for.";
            refresh(true);
            return;
        }
        importing = true;
        importStatus = "";
        hideKeyboard(importBox);
        refresh(true);
        new java.lang.Thread(new Runnable() {
            public void run() {
                String result;
                try {
                    result = NonIntel.importShare(text, new NonIntel.Fetcher() {
                        public String get(String url) throws IOException {
                            return fetch(url);
                        }
                    }).toString();
                } catch (Exception e) {
                    result = e instanceof IllegalArgumentException ? e.getMessage() : "Couldn't import: " + e.getMessage();
                }
                final String r = result;
                ui.post(new Runnable() {
                    public void run() {
                        importing = false;
                        importStatus = r;
                        if (r.startsWith("Imported")) importBox.setText("");
                        refresh(true);
                    }
                });
            }
        }, "non-import").start();
    }

    private static String fetch(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(20000);
        c.setRequestProperty("User-Agent", "AFKBot-NaughtyOrNice");
        int code = c.getResponseCode();
        if (code == 404) throw new IOException("that share link has expired. Ask for a new one.");
        if (code / 100 != 2) throw new IOException("the share site said " + code);
        InputStream in = c.getInputStream();
        try {
            return new String(NonIntel.readAll(in), "UTF-8");
        } finally {
            in.close();
        }
    }

    interface Set {
        void set(boolean v);
    }

    private void toggle(LinearLayout parent, String label, boolean on, final Set set) {
        Switch s = new Switch(act);
        s.setText(label);
        s.setTextColor(TEXT);
        s.setTextSize(14);
        s.setChecked(on);
        s.setPadding(0, dp(6), 0, dp(6));
        s.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(android.widget.CompoundButton b, boolean v) {
                set.set(v);
                NonIntel.saveSettings();
            }
        });
        parent.addView(s);
    }

    // ================================================================ helpers

    static String mark(NonIntel.Verdict v) {
        switch (v) {
            case FRIEND:
                return "★";
            case NICE:
                return "✔";
            case LOOK:
                return "?";
            case NAUGHTY:
                return "✖";
            default:
                return "•";
        }
    }

    static int verdictColor(NonIntel.Verdict v) {
        switch (v) {
            case FRIEND:
                return FRIEND;
            case NICE:
                return NICE;
            case LOOK:
                return LOOK;
            case NAUGHTY:
                return NAUGHTY;
            default:
                return MUTED;
        }
    }

    private static int colorOf(Filter f) {
        switch (f) {
            case FRIEND:
                return FRIEND;
            case NICE:
                return NICE;
            case LOOK:
                return LOOK;
            case NAUGHTY:
                return NAUGHTY;
            default:
                return ACCENT;
        }
    }

    private static final int[] GROUP_COLORS = {0xFF5BC0FF, 0xFFFF9F43, 0xFFB388FF, 0xFF4DD0A1, 0xFFFF6FA8, 0xFFFFD54F, 0xFF80DEEA, 0xFFC5E1A5, 0xFFFF8A65, 0xFFB0BEC5};

    static int groupColor(String g) {
        return GROUP_COLORS[Math.abs(g.toLowerCase(Locale.ROOT).hashCode()) % GROUP_COLORS.length];
    }

    private int dp(float v) {
        return Math.round(v * act.getResources().getDisplayMetrics().density);
    }

    private TextView text(String s, float sp, int color, boolean bold) {
        TextView t = new TextView(act);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        t.setPadding(0, dp(1), 0, dp(1));
        return t;
    }

    private void chip(LinearLayout parent, String label, boolean on, int color, final Runnable run) {
        TextView c = text(label, 13, on ? Color.WHITE : TEXT, on);
        c.setPadding(dp(12), dp(7), dp(12), dp(7));
        c.setBackground(MainActivity.round(on ? (color & 0x00FFFFFF) | 0x66000000 : SURFACE2, dp(16), on ? color : STROKE));
        c.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                run.run();
            }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = dp(6);
        parent.addView(c, lp);
    }

    private LinearLayout chipRow(LinearLayout parent) {
        HorizontalScrollView h = new HorizontalScrollView(act);
        h.setHorizontalScrollBarEnabled(false);
        LinearLayout r = new LinearLayout(act);
        r.setOrientation(LinearLayout.HORIZONTAL);
        h.addView(r);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        parent.addView(h, lp);
        return r;
    }

    private LinearLayout row(LinearLayout parent) {
        LinearLayout r = new LinearLayout(act);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(10);
        parent.addView(r, lp);
        return r;
    }

    private LinearLayout.LayoutParams weight() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.setMargins(0, 0, dp(6), 0);
        return lp;
    }

    private LinearLayout card(LinearLayout parent) {
        LinearLayout c = new LinearLayout(act);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(14), dp(12), dp(14), dp(14));
        c.setBackground(MainActivity.round(SURFACE, dp(16), STROKE));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(10);
        parent.addView(c, lp);
        return c;
    }

    private TextView button(String label, int color, final Runnable run) {
        TextView b = text(label, 14, Color.WHITE, true);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(14), dp(10), dp(14), dp(10));
        b.setBackground(MainActivity.round(color, dp(12), color == SURFACE2 ? STROKE : color));
        b.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                run.run();
            }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        b.setLayoutParams(lp);
        return b;
    }

    private EditText edit(String hint, int type) {
        EditText e = new EditText(act);
        e.setHint(hint);
        e.setInputType(type);
        e.setSingleLine((type & InputType.TYPE_TEXT_FLAG_MULTI_LINE) == 0);
        e.setTextColor(TEXT);
        e.setHintTextColor(0xFF5B6478);
        e.setTextSize(15);
        e.setPadding(dp(12), dp(10), dp(12), dp(10));
        e.setBackground(MainActivity.round(SURFACE2, dp(12), STROKE));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(4);
        e.setLayoutParams(lp);
        return e;
    }

    private void toast(String s) {
        Toast.makeText(act, s, Toast.LENGTH_SHORT).show();
    }

    private void copy(String text, String said) {
        ClipboardManager cm = (ClipboardManager) act.getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("Naughty or Nice", text));
        toast(said);
    }

    private void hideKeyboard(View v) {
        if (v == null) return;
        android.view.inputmethod.InputMethodManager im = (android.view.inputmethod.InputMethodManager) act.getSystemService(Context.INPUT_METHOD_SERVICE);
        if (im != null) im.hideSoftInputFromWindow(v.getWindowToken(), 0);
    }

    private static final SimpleDateFormat CLOCK = new SimpleDateFormat("HH:mm", Locale.US);
    private static final SimpleDateFormat DATE = new SimpleDateFormat("EEE d MMM, HH:mm", Locale.US);
    private static final SimpleDateFormat DAY = new SimpleDateFormat("EEE d MMM", Locale.US);

    private static String clock(long t) {
        return CLOCK.format(new Date(t));
    }

    private static String date(long t) {
        return DATE.format(new Date(t));
    }

    private static String day(long t) {
        String d = DAY.format(new Date(t));
        if (d.equals(DAY.format(new Date()))) return "Today";
        if (d.equals(DAY.format(new Date(System.currentTimeMillis() - 86400000L)))) return "Yesterday";
        return d;
    }
}

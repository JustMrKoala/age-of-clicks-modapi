package aoc.diplomacy;

import aoc.api.AocMod;
import aoc.api.GameApi.CountryInfo;
import aoc.api.ModContext;
import aoc.api.ui.Button;
import aoc.api.ui.Label;
import aoc.api.ui.ListView;
import aoc.api.ui.MessageList;
import aoc.api.ui.Panel;
import aoc.api.ui.Separator;
import aoc.api.ui.TextField;
import aoc.api.ui.Theme;
import aoc.api.ui.Ui;
import aoc.api.ui.Window;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Pax Historia-style diplomacy: talk to any country's leader in plain language.
 * An AI plays the leader from live game data and can agree to anything in the mod API's
 * action catalog; deals happen when you accept them.
 */
public final class DiplomacyMod implements AocMod {
    /** How often the country list refreshes while the window is open. */
    private static final float REFRESH_SECONDS = 3f;

    private static final class Conversation {
        final MessageList log = new MessageList().flex(1);
        Deal.Proposal proposal;
        boolean waiting;
    }

    private ModContext ctx;
    private Config config;
    private Negotiator negotiator;
    private String loadProblem;
    private int hotkey = Input.Keys.F8;

    private final Map<String, Conversation> conversations = new HashMap<>();
    private List<CountryInfo> countries = List.of();
    private CountryInfo selected;
    private float sinceRefresh;

    private Window window;
    private ListView<CountryInfo> list;
    private TextField search;
    private TextField input;
    private Panel logSlot;
    private Panel proposalCard;
    private Label proposalTitle;
    private Label proposalTerms;

    @Override
    public String id() { return "diplomacy"; }

    @Override
    public String name() { return "AI Diplomacy"; }

    @Override
    public String version() { return "0.4.1"; }

    @Override
    public String description() {
        return "Talk to any country's leader in your own words. An AI plays the leader from live game data "
                + "and can agree to anything in the mod API's action catalog; deals happen when you accept them.";
    }

    @Override
    public void onLoad(ModContext ctx) {
        this.ctx = ctx;
        try {
            config = new Config(ctx.settings());
            hotkey = keyOf(config.hotkey());
            negotiator = new Negotiator(ctx, new LlmClient(config), config);
            ctx.settings().onChange(this::settingsChanged);
            ctx.log("Ready: " + config.baseUrl() + " / " + config.model() + ", hotkey " + Input.Keys.toString(hotkey));
        } catch (Exception e) {
            loadProblem = "The diplomacy mod could not start: " + e.getMessage();
            ctx.error("Config failed", e);
        }
    }

    @Override
    public void onGameReady(ModContext ctx) {
        Ui ui = ctx.ui();
        buildWindow();
        ui.addHud(new Button("Diplomacy  [" + Input.Keys.toString(hotkey) + "]").onClick(this::toggle),
                Ui.Anchor.TOP_LEFT, () -> ctx.game().inGame() && !window.isOpen());
        ui.bindKey(hotkey, this::hotkeyPressed);
    }

    private void hotkeyPressed() {
        if (ctx.game().inGame()) toggle();
    }

    /** Settings saved in Code Mods: everything but the hotkey is read live. */
    private void settingsChanged() {
        int key = keyOf(config.hotkey());
        if (key != hotkey) {
            ctx.ui().unbindKey(hotkey);
            hotkey = key;
            ctx.ui().bindKey(hotkey, this::hotkeyPressed);
        }
    }

    private static int keyOf(String name) {
        int key = Input.Keys.valueOf(name);
        return key > 0 ? key : Input.Keys.F8;
    }

    /** Why talks cannot start, or null. */
    private String setupProblem() {
        if (loadProblem != null) return loadProblem;
        if (config.apiKey().isEmpty()) {
            return "No API key yet. Set it in main menu > MODS > CODE MODS > AI Diplomacy (free Groq keys: console.groq.com/keys).";
        }
        return null;
    }

    @Override
    public void render(ModContext ctx, float delta) {
        if (!ctx.game().inGame()) {
            if (window.isOpen()) window.close();
            return;
        }
        if (window.isOpen()) {
            sinceRefresh += delta;
            if (sinceRefresh > REFRESH_SECONDS) refreshCountries();
        }
    }

    // ---- window ----

    private void buildWindow() {
        window = new Window("Diplomacy", 1040, 680);

        search = new TextField().placeholder("Search countries...").maxLength(40)
                .onChange(text -> list.filter(c -> c.displayName().toLowerCase().contains(text.toLowerCase())));
        list = new ListView<CountryInfo>()
                .items(() -> countries)
                .label(CountryInfo::displayName)
                .note(c -> c.ally() ? new ListView.Note("ally", Theme.good())
                        : c.puppet() ? new ListView.Note("puppet", Theme.good())
                        : new ListView.Note(String.valueOf(c.relationWithPlayer()),
                                c.relationWithPlayer() > 20 ? Theme.good() : c.relationWithPlayer() < -20 ? Theme.bad() : Theme.textMuted()))
                .onSelect(this::select)
                .flex(1);
        Panel left = Panel.column().gap(8).width(270).add(search, list);

        Label name = new Label(() -> selected == null ? "Choose a country" : selected.displayName()).font(Theme.Font.LARGE);
        Label status = new Label(this::statusLine).font(Theme.Font.SMALL).muted();
        Label hint = new Label(() -> setupProblem() != null ? setupProblem()
                : "Talk to any leader in your own words. Ask for peace, alliances, trade, land, unions, or make threats. "
                + "Their offers appear below the chat; nothing happens until you accept.")
                .font(Theme.Font.SMALL).color(() -> setupProblem() != null ? Theme.bad() : Theme.textMuted());

        logSlot = Panel.column().flex(1);
        logSlot.add(new MessageList().flex(1));

        proposalTitle = new Label("").font(Theme.Font.MAIN);
        proposalTerms = new Label("").font(Theme.Font.SMALL);
        proposalCard = Panel.column().padding(10).gap(6).background(Panel.Background.CARD).add(
                proposalTitle, proposalTerms,
                Panel.row().gap(8).height(36).add(
                        Separator.spacer().flex(1),
                        new Button("Decline").width(120).onClick(this::decline),
                        new Button("Accept").primary().width(120).onClick(this::accept)));
        proposalCard.visible(false);

        input = new TextField().placeholder("Write to the leader...").maxLength(600).onSubmit(t -> send()).flex(1);
        Button send = new Button("Send").primary().width(110).onClick(this::send)
                .enabled(() -> selected != null && setupProblem() == null && !conversation(selected).waiting);
        Panel inputRow = Panel.row().gap(8).height(38).add(input, send);

        Panel right = Panel.column().gap(8).flex(1).add(name, status, hint, logSlot, proposalCard, inputRow);
        window.content().add(Panel.row().gap(14).flex(1).add(left, new Separator().width(2), right));
    }

    private String statusLine() {
        if (selected == null) return ctx.game().inGame() ? ctx.game().date() : "";
        CountryInfo c = selected;
        return readable(c.government()) + "    relation " + c.relationWithPlayer()
                + (c.ally() ? "    ally" : "") + (c.puppet() ? "    your puppet" : "") + "    " + ctx.game().date();
    }

    private void toggle() {
        if (window.isOpen()) {
            window.close();
        } else {
            refreshCountries();
            ctx.ui().open(window);
            (selected == null ? search : input).focus();
        }
    }

    private void refreshCountries() {
        sinceRefresh = 0;
        try {
            List<CountryInfo> all = new ArrayList<>();
            for (CountryInfo c : ctx.game().countries()) if (!c.isPlayer()) all.add(c);
            countries = all;
            if (selected != null) {
                CountryInfo fresh = null;
                for (CountryInfo c : all) if (c.name().equals(selected.name())) fresh = c;
                if (fresh != null) {
                    selected = fresh;
                    list.select(fresh);
                }
            }
        } catch (Exception e) {
            ctx.error("Could not read countries", e);
        }
    }

    private Conversation conversation(CountryInfo c) {
        return conversations.computeIfAbsent(c.name(), k -> {
            Conversation conv = new Conversation();
            conv.log.add(MessageList.Side.NOTICE, null, "You open talks with the government of " + c.displayName() + ".");
            return conv;
        });
    }

    private void select(CountryInfo c) {
        selected = c;
        Conversation conv = conversation(c);
        logSlot.clear().add(conv.log);
        showProposal(conv);
        input.focus();
    }

    private void showProposal(Conversation conv) {
        Deal.Proposal p = conv.proposal;
        proposalCard.visible(p != null);
        if (p == null) return;
        String player = ctx.game().player().displayName();
        StringBuilder terms = new StringBuilder();
        for (Deal.Term t : p.terms) terms.append("- ").append(t.describe(selected.displayName(), player)).append('\n');
        proposalTitle.text("Proposal: " + p.summary);
        proposalTerms.text(terms.toString().trim());
    }

    // ---- talking ----

    private void send() {
        CountryInfo them = selected;
        String text = input.text().trim();
        if (them == null || text.isEmpty() || setupProblem() != null) return;
        Conversation conv = conversation(them);
        if (conv.waiting) return;
        input.text("");
        conv.log.add(MessageList.Side.RIGHT, "You", text);
        conv.waiting = true;
        conv.log.typing(them.displayName());
        negotiator.send(them, text).whenComplete((deal, err) -> Gdx.app.postRunnable(() -> {
            conv.waiting = false;
            conv.log.typing(null);
            if (err != null) {
                Throwable cause = err.getCause() != null ? err.getCause() : err;
                conv.log.add(MessageList.Side.NOTICE, null, "No answer: " + cause.getMessage(), Theme.bad());
                ctx.error("LLM request failed", cause);
                return;
            }
            conv.log.add(MessageList.Side.LEFT, them.displayName(), deal.reply.isEmpty() ? "..." : deal.reply);
            if (deal.proposal != null) {
                conv.proposal = deal.proposal;
                if (them == selected) showProposal(conv);
            }
            List<Deal.Term> unilateral = new ArrayList<>();
            for (Deal.Term t : deal.actions) if (Negotiator.allowedUnilateral(t)) unilateral.add(t);
            if (!unilateral.isEmpty()) carryOut(them, conv, unilateral, false);
        }));
    }

    private void accept() {
        CountryInfo them = selected;
        if (them == null) return;
        Conversation conv = conversation(them);
        Deal.Proposal p = conv.proposal;
        if (p == null) return;
        conv.proposal = null;
        showProposal(conv);
        conv.log.add(MessageList.Side.NOTICE, null, "You accept: " + p.summary, Theme.good());
        negotiator.note(them, "The player accepted your proposal: " + p.summary);
        carryOut(them, conv, p.terms, true);
    }

    private void decline() {
        CountryInfo them = selected;
        if (them == null) return;
        Conversation conv = conversation(them);
        if (conv.proposal == null) return;
        conv.log.add(MessageList.Side.NOTICE, null, "You decline the proposal.");
        negotiator.note(them, "The player declined your proposal: " + conv.proposal.summary);
        conv.proposal = null;
        showProposal(conv);
    }

    private void carryOut(CountryInfo them, Conversation conv, List<Deal.Term> terms, boolean agreed) {
        negotiator.apply(them, terms, agreed).whenComplete((results, err) -> Gdx.app.postRunnable(() -> {
            if (err != null) {
                conv.log.add(MessageList.Side.NOTICE, null, "The deal could not be carried out: " + err.getMessage(), Theme.bad());
                ctx.error("Applying terms failed", err);
            } else {
                for (String r : results) {
                    conv.log.add(MessageList.Side.NOTICE, null, r,
                            r.startsWith("Done") ? Theme.good() : r.startsWith("Sent") || r.startsWith("Skipped") ? Theme.textMuted() : Theme.bad());
                }
                negotiator.note(them, "Results: " + String.join("; ", results));
                if (!agreed) ctx.ui().toast(them.displayName() + ": " + String.join("; ", results), 6);
            }
            refreshCountries();
        }));
    }

    private static String readable(String gov) {
        if (gov == null || gov.isEmpty()) return "";
        String s = gov.toLowerCase().replace('_', ' ');
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}

package com.runeledger.sync;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.inject.Provides;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.Skill;
import net.runelite.api.WorldType;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@PluginDescriptor(
    name = "Rune Ledger Sync",
    description = "Sync skills and optionally your opened bank and Slayer task to your own Rune Ledger",
    tags = {"skills", "bank", "slayer", "sync"}
)
public class RuneLedgerPlugin extends Plugin
{
    private static final Logger LOG = LoggerFactory.getLogger(RuneLedgerPlugin.class);
    private static final long UPLOAD_INTERVAL = TimeUnit.SECONDS.toNanos(15);
    private static final long RETRY_INTERVAL = TimeUnit.SECONDS.toNanos(60);
    private static final EnumSet<WorldType> SEPARATE_PROFILES = EnumSet.of(
        WorldType.SEASONAL, WorldType.DEADMAN, WorldType.TOURNAMENT_WORLD, WorldType.BETA_WORLD,
        WorldType.NOSAVE_MODE, WorldType.QUEST_SPEEDRUNNING, WorldType.FRESH_START_WORLD, WorldType.PVP_ARENA
    );

    @Inject
    private Client client;

    @Inject
    private RuneLedgerConfig config;

    @Inject
    private OkHttpClient httpClient;

    @Inject
    private Gson gson;

    private volatile UploadWorker sender;
    private volatile Session session;

    @Provides
    RuneLedgerConfig provideConfig(ConfigManager manager)
    {
        return manager.getConfig(RuneLedgerConfig.class);
    }

    @Override
    protected void startUp()
    {
        sender = new UploadWorker(new SnapshotTransport(httpClient, gson));
    }

    @Override
    protected void shutDown()
    {
        UploadWorker worker = sender;
        sender = null;
        session = null;
        if (worker != null)
        {
            worker.cancelActiveCall();
            worker.executor.shutdownNow();
        }
    }

    private void resetSession()
    {
        session = null;
        UploadWorker worker = sender;
        if (worker != null)
        {
            worker.cancelActiveCall();
        }
    }

    @Subscribe
    public void onConfigChanged(ConfigChanged event)
    {
        if (RuneLedgerConfig.GROUP.equals(event.getGroup()))
        {
            resetSession();
        }
    }

    @Subscribe
    public void onGameStateChanged(GameStateChanged event)
    {
        switch (event.getGameState())
        {
            case LOGIN_SCREEN:
            case LOGGING_IN:
            case HOPPING:
            case CONNECTION_LOST:
                resetSession();
                break;
            default:
                break;
        }
    }

    @Subscribe
    public void onGameTick(GameTick ignored)
    {
        UploadWorker worker = sender;
        if (worker == null || !config.syncEnabled() || client.getGameState() != GameState.LOGGED_IN
            || client.getLocalPlayer() == null || !java.util.Collections.disjoint(client.getWorldType(), SEPARATE_PROFILES))
        {
            resetSession();
            return;
        }

        String name = SnapshotTransport.normalizeName(client.getLocalPlayer().getName());
        String pairedPlayer = SnapshotTransport.normalizeName(config.playerName());
        String configuredToken = config.token();
        String token = configuredToken == null ? "" : configuredToken.trim();
        URI endpoint = SnapshotTransport.endpoint(config.serverUrl());
        if (name.isEmpty() || !name.equalsIgnoreCase(pairedPlayer) || endpoint == null || !SnapshotTransport.validToken(token))
        {
            resetSession();
            return;
        }
        boolean bankEnabled = config.syncBank();
        boolean slayerEnabled = config.syncSlayer();
        Session active = session;
        if (active == null || !active.matches(name, endpoint, token, bankEnabled, slayerEnabled))
        {
            resetSession();
            active = new Session(name, endpoint, token, bankEnabled, slayerEnabled);
            session = active;
        }
        if (bankEnabled)
        {
            captureOpenBank(active);
        }
        if (slayerEnabled && !active.slayerReady)
        {
            // Let the profile's game variables settle before reporting a fresh session.
            active.slayerReady = true;
            return;
        }
        sampleAndSend(worker, active);
    }

    @Subscribe
    public void onItemContainerChanged(ItemContainerChanged event)
    {
        Session active = session;
        if (active != null && active.bankEnabled && event.getContainerId() == InventoryID.BANK)
        {
            active.bankNeedsCapture = true;
        }
    }

    private void captureOpenBank(Session active)
    {
        Widget bank = client.getWidget(InterfaceID.Bankmain.ITEMS);
        if (bank == null || bank.isHidden())
        {
            active.bankNeedsCapture = true;
            return;
        }
        if (!active.bankNeedsCapture)
        {
            return;
        }
        ItemContainer container = client.getItemContainer(InventoryID.BANK);
        if (container == null)
        {
            return;
        }

        Map<Integer, Integer> totals = new TreeMap<>();
        for (Item item : container.getItems())
        {
            if (item != null && item.getId() > 0 && item.getQuantity() > 0)
            {
                totals.merge(item.getId(), item.getQuantity(), (left, right) ->
                    (int) Math.min((long) left + right, Integer.MAX_VALUE));
            }
        }
        if (!totals.equals(active.observedBank))
        {
            active.observedBank = totals;
        }
        active.bankNeedsCapture = false;
    }

    private JsonElement sampleSlayer(Session active)
    {
        if (!active.slayerEnabled)
        {
            return null;
        }
        try
        {
            JsonElement task = SlayerTaskReader.read(client);
            return Objects.equals(task, active.uploadedSlayer) ? null : task;
        }
        catch (RuntimeException exception)
        {
            LOG.debug("Could not read the current Slayer assignment.", exception);
            return null;
        }
    }

    private void sampleAndSend(UploadWorker worker, Session active)
    {
        long now = System.nanoTime();
        if (worker.sending.get() || (active.nextUpload != 0 && now - active.nextUpload < 0))
        {
            return;
        }

        List<SnapshotTransport.SkillValue> skills = new ArrayList<>();
        for (Skill skill : Skill.values())
        {
            int level = client.getRealSkillLevel(skill);
            int xp = client.getSkillExperience(skill);
            if (level > 0 && xp >= 0)
            {
                skills.add(new SnapshotTransport.SkillValue(skill.getName(), level, xp));
            }
        }
        if (skills.isEmpty())
        {
            return;
        }
        String fingerprint = worker.transport.fingerprint(skills);
        Map<Integer, Integer> bank = Objects.equals(active.observedBank, active.uploadedBank) ? null : active.observedBank;
        JsonElement slayer = sampleSlayer(active);
        if (fingerprint.equals(active.uploadedSkills) && bank == null && slayer == null)
        {
            active.nextUpload = now + UPLOAD_INTERVAL;
            return;
        }
        List<SnapshotTransport.BankStack> bankStacks = null;
        if (bank != null)
        {
            bankStacks = new ArrayList<>();
            for (Map.Entry<Integer, Integer> entry : bank.entrySet())
            {
                bankStacks.add(new SnapshotTransport.BankStack(entry.getKey(), entry.getValue()));
            }
        }
        if (!worker.sending.compareAndSet(false, true))
        {
            return;
        }
        SnapshotTransport.Snapshot snapshot = new SnapshotTransport.Snapshot(active.player, skills, bankStacks, slayer);
        try
        {
            worker.executor.execute(() -> upload(worker, active, snapshot, fingerprint, bank));
        }
        catch (RejectedExecutionException ignored)
        {
            worker.sending.set(false);
        }
    }

    private void upload(UploadWorker worker, Session active, SnapshotTransport.Snapshot snapshot, String fingerprint, Map<Integer, Integer> bank)
    {
        try
        {
            if (sender != worker || session != active)
            {
                return;
            }
            Call call = worker.transport.newCall(active.endpoint, active.token, snapshot);
            worker.activeCall = call;
            if (sender != worker || session != active)
            {
                return;
            }
            int status;
            // This runs on the upload worker, off the RuneLite client thread.
            try (Response response = call.execute())
            {
                status = response.code();
            }
            if (sender != worker || session != active)
            {
                return;
            }
            if (status >= 200 && status < 300)
            {
                active.uploadedSkills = fingerprint;
                if (bank != null)
                {
                    active.uploadedBank = bank;
                }
                if (snapshot.slayer != null)
                {
                    active.uploadedSlayer = snapshot.slayer;
                }
                active.nextUpload = System.nanoTime() + UPLOAD_INTERVAL;
            }
            else
            {
                active.nextUpload = System.nanoTime() + RETRY_INTERVAL;
                LOG.warn("Rune Ledger declined the snapshot (HTTP {}). Check the pairing settings.", status);
            }
        }
        catch (IOException ex)
        {
            if (sender == worker && session == active)
            {
                active.nextUpload = System.nanoTime() + RETRY_INTERVAL;
                LOG.warn("Rune Ledger could not be reached. Check the configured server URL.");
            }
        }
        finally
        {
            worker.activeCall = null;
            worker.sending.set(false);
        }
    }

    private static final class UploadWorker
    {
        final SnapshotTransport transport;
        final AtomicBoolean sending = new AtomicBoolean();
        volatile Call activeCall;
        final ExecutorService executor = Executors.newSingleThreadExecutor(task ->
        {
            Thread thread = new Thread(task, "rune-ledger-sync");
            thread.setDaemon(true);
            return thread;
        });

        UploadWorker(SnapshotTransport transport)
        {
            this.transport = transport;
        }

        void cancelActiveCall()
        {
            Call call = activeCall;
            if (call != null)
            {
                call.cancel();
            }
        }
    }

    private static final class Session
    {
        final String player;
        final URI endpoint;
        final String token;
        final boolean bankEnabled;
        final boolean slayerEnabled;
        boolean slayerReady;
        boolean bankNeedsCapture = true;
        Map<Integer, Integer> observedBank;
        volatile Map<Integer, Integer> uploadedBank;
        volatile String uploadedSkills;
        volatile JsonElement uploadedSlayer;
        volatile long nextUpload;

        Session(String player, URI endpoint, String token, boolean bankEnabled, boolean slayerEnabled)
        {
            this.player = player;
            this.endpoint = endpoint;
            this.token = token;
            this.bankEnabled = bankEnabled;
            this.slayerEnabled = slayerEnabled;
        }

        boolean matches(String name, URI target, String credential, boolean includeBank, boolean includeSlayer)
        {
            return player.equalsIgnoreCase(name) && endpoint.equals(target) && token.equals(credential)
                && bankEnabled == includeBank && slayerEnabled == includeSlayer;
        }
    }
}

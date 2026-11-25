package com.runeledger.sync;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.inject.Provides;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
import javax.inject.Inject;
import javax.swing.SwingUtilities;
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
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.events.ProfileChanged;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import okhttp3.Call;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@PluginDescriptor(
    name = "Rune Ledger Sync",
    description = "Sync skills and optionally your opened bank, Slayer task and completed quests to your own Rune Ledger",
    tags = {"skills", "bank", "slayer", "quests", "sync"}
)
public class RuneLedgerPlugin extends Plugin
{
    private static final Logger LOG = LoggerFactory.getLogger(RuneLedgerPlugin.class);
    private static final long UPLOAD_INTERVAL = TimeUnit.SECONDS.toNanos(15);
    private static final long RETRY_INTERVAL = TimeUnit.SECONDS.toNanos(60);
    private static final long QUEST_SCAN_INTERVAL = TimeUnit.MINUTES.toNanos(1);
    private static final long LOCAL_DISCOVERY_INTERVAL = TimeUnit.SECONDS.toNanos(5);
    private static final Pattern PLAYER_NAME = Pattern.compile("[A-Za-z0-9 -]{1,12}");
    private static final EnumSet<WorldType> SEPARATE_PROFILES = EnumSet.of(
        WorldType.SEASONAL, WorldType.DEADMAN, WorldType.TOURNAMENT_WORLD, WorldType.BETA_WORLD,
        WorldType.NOSAVE_MODE, WorldType.QUEST_SPEEDRUNNING, WorldType.FRESH_START_WORLD, WorldType.PVP_ARENA
    );

    @Inject
    private Client client;

    @Inject
    private RuneLedgerConfig config;

    @Inject
    private ConfigManager configManager;

    @Inject
    private ClientThread clientThread;

    @Inject
    private ClientToolbar clientToolbar;

    @Inject
    private Gson gson;

    private volatile UploadWorker sender;
    private volatile Session session;
    private volatile CredentialScope shownScope;
    private CredentialPanel credentialPanel;
    private NavigationButton credentialNavigation;

    @Provides
    RuneLedgerConfig provideConfig(ConfigManager manager)
    {
        return manager.getConfig(RuneLedgerConfig.class);
    }

    @Override
    protected void startUp()
    {
        clearLegacySettings();
        credentialPanel = new CredentialPanel(this::useCode, () -> setCode(null));
        sender = new UploadWorker(SnapshotTransport.forLocalApp(gson), SnapshotTransport.hostedClient());
        credentialNavigation = NavigationButton.builder().tooltip("Rune Ledger Sync").priority(8)
            .icon(CredentialPanel.icon()).panel(credentialPanel).build();
        clientToolbar.addNavigation(credentialNavigation);
    }

    @Override
    protected void shutDown()
    {
        UploadWorker worker = sender;
        sender = null;
        session = null;
        shownScope = null;
        if (credentialNavigation != null)
        {
            clientToolbar.removeNavigation(credentialNavigation);
        }
        if (worker != null)
        {
            worker.cancelActiveCall();
            worker.executor.shutdown();
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

    private void clearLegacySettings()
    {
        configManager.unsetConfiguration(RuneLedgerConfig.GROUP, "token");
        configManager.unsetConfiguration(RuneLedgerConfig.GROUP, "localSyncCode");
    }

    @Subscribe
    public void onConfigChanged(ConfigChanged event)
    {
        if (RuneLedgerConfig.GROUP.equals(event.getGroup()))
        {
            if (event.getProfile() == null && event.getNewValue() != null
                && ("token".equals(event.getKey()) || "localSyncCode".equals(event.getKey())))
            {
                configManager.unsetConfiguration(RuneLedgerConfig.GROUP, event.getKey());
            }
            resetSession();
        }
    }

    @Subscribe
    public void onProfileChanged(ProfileChanged ignored)
    {
        clearLegacySettings();
        resetSession();
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
                showScope(null);
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
        if (worker == null)
        {
            return;
        }
        CredentialScope scope = activeScope();
        showScope(scope);
        if (scope == null || !config.syncEnabled())
        {
            resetSession();
            return;
        }

        String token = worker.credential(scope);
        if (token == null)
        {
            resetSession();
            return;
        }
        boolean localApp = scope.hostedEndpoint == null;
        LocalAppTarget localTarget = localApp ? worker.discoverLocalApp() : null;
        if (localApp && localTarget == null)
        {
            resetSession();
            return;
        }
        boolean bankEnabled = config.syncBank();
        boolean slayerEnabled = config.syncSlayer();
        boolean questsEnabled = config.syncQuests();
        Session active = session;
        if (active == null || !active.matches(scope, localTarget, token, bankEnabled, slayerEnabled, questsEnabled))
        {
            resetSession();
            active = new Session(scope, localTarget, token, bankEnabled, slayerEnabled, questsEnabled);
            session = active;
        }
        if (bankEnabled)
        {
            captureOpenBank(active);
        }
        if ((slayerEnabled || questsEnabled) && !active.gameVariablesReady)
        {
            // Let the profile's game variables settle before reporting a fresh session.
            active.gameVariablesReady = true;
            return;
        }
        if (questsEnabled)
        {
            sampleQuests(active);
        }
        sampleAndSend(worker, active);
    }

    private CredentialScope activeScope()
    {
        if (client.getGameState() != GameState.LOGGED_IN || client.getLocalPlayer() == null
            || !java.util.Collections.disjoint(client.getWorldType(), SEPARATE_PROFILES))
        {
            return null;
        }
        String name = SnapshotTransport.normalizeName(client.getLocalPlayer().getName());
        if (!PLAYER_NAME.matcher(name).matches())
        {
            return null;
        }
        if (config.localApp())
        {
            return new CredentialScope(name, null);
        }
        URI endpoint = SnapshotTransport.endpoint(config.serverUrl());
        if (endpoint != null && name.equalsIgnoreCase(SnapshotTransport.normalizeName(config.playerName())))
        {
            return new CredentialScope(name, endpoint);
        }
        return null;
    }

    private void showScope(CredentialScope scope)
    {
        if (Objects.equals(shownScope, scope))
        {
            return;
        }
        shownScope = scope;
        CredentialPanel panel = credentialPanel;
        SwingUtilities.invokeLater(() -> panel.showScope(scope));
    }

    private void useCode(char[] password)
    {
        String code = new String(password).trim();
        Arrays.fill(password, '\0');
        if (!SnapshotTransport.validToken(code))
        {
            credentialPanel.finish("Paste a valid Rune Ledger sync code.");
            return;
        }
        setCode(code);
    }

    private void setCode(String code)
    {
        UploadWorker worker = sender;
        CredentialPanel panel = credentialPanel;
        CredentialScope selected = panel.selectedScope();
        clientThread.invokeLater(() ->
        {
            if (worker == null || sender != worker)
            {
                return;
            }
            CredentialScope scope = activeScope();
            if (scope == null || !scope.equals(selected))
            {
                SwingUtilities.invokeLater(() -> panel.finish("Check the logged-in character and the selected Rune Ledger destination."));
                return;
            }
            worker.credentials.put(scope, new CredentialEntry(code));
            resetSession();
            try
            {
                worker.executor.execute(() ->
                {
                    String message = worker.persistCredential(scope, code);
                    SwingUtilities.invokeLater(() -> panel.finish(message));
                });
            }
            catch (RejectedExecutionException ignored)
            {
                SwingUtilities.invokeLater(() -> panel.finish("Rune Ledger Sync is closing."));
            }
        });
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
        JsonElement quests = Objects.equals(active.observedQuests, active.uploadedQuests) ? null : active.observedQuests;
        if (fingerprint.equals(active.uploadedSkills) && bank == null && slayer == null && quests == null)
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
        SnapshotTransport.Snapshot snapshot = new SnapshotTransport.Snapshot(active.scope.player, skills, bankStacks, slayer, quests);
        try
        {
            worker.executor.execute(() -> upload(worker, active, snapshot, fingerprint, bank));
        }
        catch (RejectedExecutionException ignored)
        {
            worker.sending.set(false);
        }
    }

    private void sampleQuests(Session active)
    {
        long now = System.nanoTime();
        if (active.questReader == null)
        {
            if (active.nextQuestScan != 0 && now - active.nextQuestScan < 0)
            {
                return;
            }
            active.questReader = new QuestProgressReader();
        }

        try
        {
            JsonElement quests = active.questReader.readBatch(quest -> quest.getState(client));
            if (quests == null)
            {
                return;
            }
            active.observedQuests = quests;
        }
        catch (RuntimeException exception)
        {
            LOG.debug("Could not read quest completion.", exception);
        }
        active.questReader = null;
        active.nextQuestScan = now + QUEST_SCAN_INTERVAL;
    }

    private void upload(UploadWorker worker, Session active, SnapshotTransport.Snapshot snapshot, String fingerprint, Map<Integer, Integer> bank)
    {
        try
        {
            if (sender != worker || session != active)
            {
                return;
            }
            int status;
            if (active.localTarget == null)
            {
                SnapshotTransport.HostedUpload call = worker.transport.newHostedCall(worker.hostedClient,
                    active.scope.hostedEndpoint, active.token, snapshot);
                worker.cancelActive = call::cancel;
                if (sender != worker || session != active)
                {
                    return;
                }
                status = call.execute();
            }
            else
            {
                SnapshotTransport.LocalProbe probe = new SnapshotTransport.LocalProbe(active.localTarget);
                if (!verifyLocalApp(worker, active, probe))
                {
                    return;
                }
                Call call = worker.transport.newLocalCall(probe, active.token, snapshot);
                worker.cancelActive = call::cancel;
                if (sender != worker || session != active)
                {
                    return;
                }
                try (Response response = call.execute())
                {
                    status = response.code();
                }
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
                if (snapshot.quests != null)
                {
                    active.uploadedQuests = snapshot.quests;
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
                LOG.warn(active.localTarget == null
                    ? "Rune Ledger could not be reached. Check the configured server URL."
                    : "Rune Ledger Desktop could not be reached. Check that local sync is enabled in the app.");
            }
        }
        finally
        {
            worker.cancelActive = null;
            worker.sending.set(false);
        }
    }

    private boolean verifyLocalApp(UploadWorker worker, Session active, SnapshotTransport.LocalProbe probe) throws IOException
    {
        Call call = worker.transport.probeLocalApp(probe);
        worker.cancelActive = call::cancel;
        if (sender != worker || session != active)
        {
            return false;
        }
        try (Response response = call.execute())
        {
            if (probe.verify(response))
            {
                return true;
            }
        }
        active.nextUpload = System.nanoTime() + RETRY_INTERVAL;
        LOG.warn("Rune Ledger Desktop identity could not be verified. Check that local sync is enabled in the app.");
        return false;
    }

    private static final class UploadWorker
    {
        final SnapshotTransport transport;
        final HttpClient hostedClient;
        final Map<CredentialScope, CredentialEntry> credentials = new ConcurrentHashMap<>();
        private final WindowsCredentialStore credentialStore = WindowsCredentialStore.forCurrentUser();
        final AtomicBoolean sending = new AtomicBoolean();
        volatile Runnable cancelActive;
        private volatile LocalAppTarget localTarget;
        private long nextDiscovery;
        final ExecutorService executor = Executors.newSingleThreadExecutor(task ->
        {
            Thread thread = new Thread(task, "rune-ledger-sync");
            thread.setDaemon(true);
            return thread;
        });

        UploadWorker(SnapshotTransport transport, HttpClient hostedClient)
        {
            this.transport = transport;
            this.hostedClient = hostedClient;
        }

        String credential(CredentialScope scope)
        {
            CredentialEntry entry = credentials.get(scope);
            if (entry != null)
            {
                return entry.code;
            }
            if (credentialStore.available())
            {
                CredentialEntry pending = new CredentialEntry(null);
                if (credentials.putIfAbsent(scope, pending) == null)
                {
                    try
                    {
                        executor.execute(() -> loadCredential(scope, pending));
                    }
                    catch (RejectedExecutionException ignored)
                    {
                        // The plugin is stopping.
                    }
                }
            }
            return null;
        }

        private void loadCredential(CredentialScope scope, CredentialEntry pending)
        {
            try
            {
                credentials.replace(scope, pending, new CredentialEntry(credentialStore.load(scope)));
            }
            catch (IOException | RuntimeException | LinkageError ignored)
            {
                LOG.warn("Rune Ledger could not load a Windows-protected sync code. Enter a new code in the sidebar.");
            }
        }

        String persistCredential(CredentialScope scope, String code)
        {
            try
            {
                if (code == null)
                {
                    credentialStore.clear(scope);
                    return "Code cleared.";
                }
                if (credentialStore.available())
                {
                    credentialStore.save(scope, code);
                    return "Code saved on this PC.";
                }
            }
            catch (IOException | RuntimeException | LinkageError ignored)
            {
                return code == null
                    ? "Code cleared for this session. Windows could not remove the saved code."
                    : "Windows could not save this code. It will be used for this RuneLite session.";
            }
            return "Code ready for this RuneLite session.";
        }

        LocalAppTarget discoverLocalApp()
        {
            long now = System.nanoTime();
            if ((nextDiscovery == 0 || now - nextDiscovery >= 0) && sending.compareAndSet(false, true))
            {
                nextDiscovery = now + LOCAL_DISCOVERY_INTERVAL;
                try
                {
                    executor.execute(() ->
                    {
                        try
                        {
                            localTarget = LocalAppTarget.find();
                        }
                        finally
                        {
                            sending.set(false);
                        }
                    });
                }
                catch (RejectedExecutionException ignored)
                {
                    sending.set(false);
                }
            }
            return localTarget;
        }

        void cancelActiveCall()
        {
            Runnable cancel = cancelActive;
            if (cancel != null)
            {
                cancel.run();
            }
        }
    }

    private static final class CredentialEntry
    {
        final String code;

        CredentialEntry(String code)
        {
            this.code = code;
        }
    }

    private static final class Session
    {
        final CredentialScope scope;
        final LocalAppTarget localTarget;
        final String token;
        final boolean bankEnabled;
        final boolean slayerEnabled;
        final boolean questsEnabled;
        boolean gameVariablesReady;
        boolean bankNeedsCapture = true;
        Map<Integer, Integer> observedBank;
        volatile Map<Integer, Integer> uploadedBank;
        volatile String uploadedSkills;
        volatile JsonElement uploadedSlayer;
        QuestProgressReader questReader;
        JsonElement observedQuests;
        volatile JsonElement uploadedQuests;
        long nextQuestScan;
        volatile long nextUpload;

        Session(CredentialScope scope, LocalAppTarget localTarget, String token, boolean bankEnabled, boolean slayerEnabled,
            boolean questsEnabled)
        {
            this.scope = scope;
            this.localTarget = localTarget;
            this.token = token;
            this.bankEnabled = bankEnabled;
            this.slayerEnabled = slayerEnabled;
            this.questsEnabled = questsEnabled;
        }

        boolean matches(CredentialScope selected, LocalAppTarget local, String credential, boolean includeBank,
            boolean includeSlayer, boolean includeQuests)
        {
            return scope.equals(selected) && token.equals(credential)
                && Objects.equals(localTarget, local) && bankEnabled == includeBank && slayerEnabled == includeSlayer
                && questsEnabled == includeQuests;
        }
    }
}

// Queue.java
package org.projectpersistence.queue;

import com.google.inject.Inject;
import com.velocitypowered.api.command.CommandManager;
import com.velocitypowered.api.command.CommandMeta;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.PostLoginEvent;
import com.velocitypowered.api.event.player.ServerConnectedEvent;
import com.velocitypowered.api.event.player.ServerPostConnectEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.slf4j.Logger;
import org.spongepowered.configurate.CommentedConfigurationNode;
import org.spongepowered.configurate.yaml.YamlConfigurationLoader;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

@Plugin(
        id = "queue",
        name = "Queue",
        version = "1.0.0",
        description = "A queue system for Velocity",
        authors = {"ProjectPersistence"}
)
public class Queue {

    private final ProxyServer server;
    private final Logger logger;
    private final Path dataDirectory;

    private final Map<UUID, QueueEntry> queueEntries = new ConcurrentHashMap<>();
    private final Set<UUID> priorityPlayers = new HashSet<>();
    private final Set<UUID> playersInQueue = Collections.synchronizedSet(new HashSet<>());

    private String mainServerName;
    private String queueServerName;
    private int mainServerMaxPlayers;

    private boolean queuePaused = false;
    private boolean mainServerOnline = true;

    // Point system configuration: permission -> seconds per point
    private final Map<String, Integer> pointTiers = new LinkedHashMap<>();

    @Inject
    public Queue(ProxyServer server, Logger logger, @DataDirectory Path dataDirectory) {
        this.server = server;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
    }

    @Subscribe
    public void onProxyInitialization(ProxyInitializeEvent event) {
        // Create data directory if it doesn't exist
        File dir = dataDirectory.toFile();
        if (!dir.exists()) {
            dir.mkdirs();
        }

        // Load configurations
        loadServerConfig();
        loadPriorityConfig();
        loadPointConfig();

        // Register commands
        CommandManager commandManager = server.getCommandManager();
        CommandMeta commandMeta = commandManager.metaBuilder("queue")
                .aliases("q")
                .build();
        commandManager.register(commandMeta, new QueueCommand());

        // Start queue processor
        server.getScheduler()
                .buildTask(this, this::processQueue)
                .repeat(2, TimeUnit.SECONDS)
                .schedule();

        // Start server status checker
        server.getScheduler()
                .buildTask(this, this::checkMainServerStatus)
                .repeat(5, TimeUnit.SECONDS)
                .schedule();

        // Start point accumulator
        server.getScheduler()
                .buildTask(this, this::accumulatePoints)
                .repeat(1, TimeUnit.SECONDS)
                .schedule();

        logger.info("Queue Plugin has been enabled!");
    }

    @Subscribe
    public void onPostLogin(PostLoginEvent event) {
        Player player = event.getPlayer();

        // Check if player is admin - they bypass everything
        if (player.hasPermission("queue.admin")) {
            connectToMainServer(player, true, true);
            return;
        }

        // Check if player has priority (permission or config list)
        boolean hasPriority = player.hasPermission("queue.priority") ||
                priorityPlayers.contains(player.getUniqueId());

        if (hasPriority && mainServerOnline) {
            connectToMainServer(player, true, false);
            return;
        }

        // Check if main server has space
        Optional<RegisteredServer> mainServer = server.getServer(mainServerName);
        if (mainServer.isPresent() && mainServerOnline) {
            int currentPlayers = mainServer.get().getPlayersConnected().size();
            if (currentPlayers < mainServerMaxPlayers) {
                connectToMainServer(player, false, false);
                return;
            }
        }

        // Add to queue
        addToQueue(player);
    }

    @Subscribe
    public void onServerConnected(ServerConnectedEvent event) {
        Player player = event.getPlayer();
        String serverName = event.getServer().getServerInfo().getName();

        // If player is connecting to queue server, add them to queue
        if (serverName.equals(queueServerName)) {
            server.getScheduler()
                    .buildTask(this, () -> {
                        // Admins go to front of queue if server is down
                        if (player.hasPermission("queue.admin")) {
                            if (!mainServerOnline) {
                                addToQueueFront(player);
                            } else {
                                connectToMainServer(player, true, true);
                            }
                            return;
                        }

                        // Check if player has priority (permission or config list)
                        boolean hasPriority = player.hasPermission("queue.priority") ||
                                priorityPlayers.contains(player.getUniqueId());

                        // Don't queue priority players who can join directly
                        if (hasPriority && mainServerOnline) {
                            Optional<RegisteredServer> mainServer = server.getServer(mainServerName);
                            if (mainServer.isPresent()) {
                                connectToMainServer(player, true, false);
                                return;
                            }
                        }

                        // Re-add to queue if not already there
                        if (!playersInQueue.contains(player.getUniqueId())) {
                            addToQueue(player);
                        } else {
                            // Update their position
                            int position = getQueuePosition(player.getUniqueId());
                            if (position != -1) {
                                QueueEntry entry = queueEntries.get(player.getUniqueId());
                                player.sendMessage(Component.text("You are in the queue. Position: " + position +
                                        " | Points: " + entry.points, NamedTextColor.YELLOW));
                            }
                        }
                    })
                    .delay(1, TimeUnit.SECONDS)
                    .schedule();
        }
    }

    @Subscribe
    public void onServerPostConnect(ServerPostConnectEvent event) {
        Player player = event.getPlayer();
        String currentServerName = player.getCurrentServer()
                .map(s -> s.getServerInfo().getName())
                .orElse("");

        // Only remove from tracking if player successfully connected to main server
        if (currentServerName.equals(mainServerName)) {
            playersInQueue.remove(player.getUniqueId());
            queueEntries.remove(player.getUniqueId());
        }
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        Player player = event.getPlayer();
        UUID playerId = player.getUniqueId();

        // Remove player from queue when they disconnect
        queueEntries.remove(playerId);
        playersInQueue.remove(playerId);
    }

    private void addToQueue(Player player) {
        if (!playersInQueue.contains(player.getUniqueId())) {
            // Determine seconds per point based on permissions
            int secondsPerPoint = getSecondsPerPoint(player);

            QueueEntry entry = new QueueEntry(player.getUniqueId(), secondsPerPoint);
            queueEntries.put(player.getUniqueId(), entry);
            playersInQueue.add(player.getUniqueId());

            Optional<RegisteredServer> queueServer = server.getServer(queueServerName);
            if (queueServer.isPresent()) {
                // Only connect if not already on queue server
                if (player.getCurrentServer().isEmpty() ||
                        !player.getCurrentServer().get().getServerInfo().getName().equals(queueServerName)) {
                    player.createConnectionRequest(queueServer.get()).connect().thenAccept(result -> {
                        if (result.isSuccessful()) {
                            int position = getQueuePosition(player.getUniqueId());
                            player.sendMessage(Component.text("You have been added to the queue. Position: " + position +
                                    " | Points per " + secondsPerPoint + "s", NamedTextColor.YELLOW));

                            if (queuePaused) {
                                player.sendMessage(Component.text("The queue is currently paused.", NamedTextColor.RED));
                            }
                            if (!mainServerOnline) {
                                player.sendMessage(Component.text("The main server is currently offline.", NamedTextColor.RED));
                            }
                        }
                    });
                } else {
                    int position = getQueuePosition(player.getUniqueId());
                    player.sendMessage(Component.text("You have been added to the queue. Position: " + position +
                            " | Points per " + secondsPerPoint + "s", NamedTextColor.YELLOW));
                }
            }
        }
    }

    private void addToQueueFront(Player player) {
        if (!playersInQueue.contains(player.getUniqueId())) {
            // Admins get maximum priority (1 second per point)
            QueueEntry entry = new QueueEntry(player.getUniqueId(), 1);
            entry.points = Integer.MAX_VALUE; // Give them max points to be first
            queueEntries.put(player.getUniqueId(), entry);
            playersInQueue.add(player.getUniqueId());

            player.sendMessage(Component.text("You have been added to the front of the queue (Admin Priority).", NamedTextColor.GREEN));
        }
    }

    private int getSecondsPerPoint(Player player) {
        // Check permissions in order (highest priority first)
        for (Map.Entry<String, Integer> tier : pointTiers.entrySet()) {
            if (player.hasPermission(tier.getKey())) {
                return tier.getValue();
            }
        }
        // Default if no permissions match
        return 60; // 1 point per 60 seconds default
    }

    private void connectToMainServer(Player player, boolean isPriority, boolean isAdmin) {
        connectToMainServer(player, isPriority, isAdmin, false);
    }

    private void connectToMainServer(Player player, boolean isPriority, boolean isAdmin, boolean forceBypass) {
        // If main server is offline and not admin/forced, send to queue
        if (!mainServerOnline && !isAdmin && !forceBypass) {
            if (isPriority) {
                player.sendMessage(Component.text("Main server is offline. Sending you to the queue server.", NamedTextColor.YELLOW));
                addToQueue(player);
            }
            return;
        }

        Optional<RegisteredServer> mainServer = server.getServer(mainServerName);
        if (mainServer.isPresent()) {
            // Admins and forced pulls can bypass max player limit
            if (!isAdmin && !forceBypass) {
                int currentPlayers = mainServer.get().getPlayersConnected().size();
                if (currentPlayers >= mainServerMaxPlayers && !isPriority) {
                    addToQueue(player);
                    return;
                }
            }

            player.createConnectionRequest(mainServer.get()).connect().thenAccept(result -> {
                if (result.isSuccessful()) {
                    playersInQueue.remove(player.getUniqueId());
                    queueEntries.remove(player.getUniqueId());

                    if (forceBypass) {
                        player.sendMessage(Component.text("You have been pulled from the queue by an admin!", NamedTextColor.GREEN));
                    } else if (isAdmin) {
                        player.sendMessage(Component.text("Connected to main server with admin access!", NamedTextColor.GOLD));
                    } else if (isPriority) {
                        player.sendMessage(Component.text("Connected to main server with priority access!", NamedTextColor.GREEN));
                    } else {
                        player.sendMessage(Component.text("Connected to main server!", NamedTextColor.GREEN));
                    }
                } else {
                    // Connection failed, add back to queue
                    if (!isAdmin && !forceBypass) {
                        player.sendMessage(Component.text("Failed to connect to main server. Adding you to the queue.", NamedTextColor.RED));
                        addToQueue(player);
                    }
                }
            });
        } else {
            // Server doesn't exist, add to queue
            if (!isAdmin && !forceBypass) {
                addToQueue(player);
            }
        }
    }

    private void processQueue() {
        // Don't process if queue is paused or main server is offline
        if (queuePaused || !mainServerOnline) {
            return;
        }

        Optional<RegisteredServer> mainServer = server.getServer(mainServerName);
        if (!mainServer.isPresent()) return;

        int currentPlayers = mainServer.get().getPlayersConnected().size();

        while (currentPlayers < mainServerMaxPlayers && !queueEntries.isEmpty()) {
            UUID nextPlayerId = getNextInQueue();
            if (nextPlayerId == null) break;

            Optional<Player> nextPlayer = server.getPlayer(nextPlayerId);
            if (nextPlayer.isPresent()) {
                // Check if player is admin
                boolean isAdmin = nextPlayer.get().hasPermission("queue.admin");
                connectToMainServer(nextPlayer.get(), false, isAdmin);
                if (!isAdmin) {
                    currentPlayers++;
                }

                // Update positions for remaining players
                updateQueuePositions();
            } else {
                // Player disconnected, remove from tracking
                queueEntries.remove(nextPlayerId);
                playersInQueue.remove(nextPlayerId);
            }
        }
    }

    private UUID getNextInQueue() {
        if (queueEntries.isEmpty()) return null;

        // Create a snapshot to avoid concurrent modification
        List<QueueEntry> snapshot = new ArrayList<>(queueEntries.values());

        // Sort by points (descending), then by secondsPerPoint (ascending)
        return snapshot.stream()
                .sorted((e1, e2) -> {
                    int pointCompare = Integer.compare(e2.points, e1.points);
                    if (pointCompare != 0) return pointCompare;
                    return Integer.compare(e1.secondsPerPoint, e2.secondsPerPoint);
                })
                .map(e -> e.playerId)
                .findFirst()
                .orElse(null);
    }

    private void accumulatePoints() {
        long currentTime = System.currentTimeMillis();

        for (QueueEntry entry : queueEntries.values()) {
            long timePassed = (currentTime - entry.joinTime) / 1000; // Convert to seconds
            entry.points = (int) (timePassed / entry.secondsPerPoint);
        }
    }

    private void checkMainServerStatus() {
        Optional<RegisteredServer> mainServer = server.getServer(mainServerName);
        if (!mainServer.isPresent()) {
            if (mainServerOnline) {
                mainServerOnline = false;
                logger.warn("Main server is not registered!");
                notifyQueuePlayers(Component.text("The main server is offline. Queue processing paused.", NamedTextColor.RED));
            }
            return;
        }

        mainServer.get().ping().thenAccept(ping -> {
            if (!mainServerOnline) {
                mainServerOnline = true;
                logger.info("Main server is back online!");
                notifyQueuePlayers(Component.text("The main server is back online. Queue processing resumed.", NamedTextColor.GREEN));
            }
        }).exceptionally(throwable -> {
            if (mainServerOnline) {
                mainServerOnline = false;
                logger.warn("Main server appears to be offline!");
                notifyQueuePlayers(Component.text("The main server is offline. Queue processing paused.", NamedTextColor.RED));
            }
            return null;
        });
    }

    private void notifyQueuePlayers(Component message) {
        Optional<RegisteredServer> queueServer = server.getServer(queueServerName);
        if (queueServer.isPresent()) {
            queueServer.get().getPlayersConnected().forEach(player -> {
                player.sendMessage(message);
            });
        }
    }

    private void updateQueuePositions() {
        // Create a snapshot to avoid concurrent modification
        List<QueueEntry> snapshot = new ArrayList<>(queueEntries.values());

        List<UUID> sortedQueue = snapshot.stream()
                .sorted((e1, e2) -> {
                    int pointCompare = Integer.compare(e2.points, e1.points);
                    if (pointCompare != 0) return pointCompare;
                    return Integer.compare(e1.secondsPerPoint, e2.secondsPerPoint);
                })
                .map(e -> e.playerId)
                .toList();

        int position = 1;
        for (UUID playerId : sortedQueue) {
            Optional<Player> player = server.getPlayer(playerId);
            if (player.isPresent()) {
                QueueEntry entry = queueEntries.get(playerId);
                if (entry != null) {
                    String statusText = "Queue #" + position + " | Points: " + entry.points;
                    if (queuePaused) {
                        statusText += " (PAUSED)";
                    }
                    if (!mainServerOnline) {
                        statusText += " (OFFLINE)";
                    }
                    player.get().sendActionBar(Component.text(statusText, NamedTextColor.GOLD));
                }
            }
            position++;
        }
    }

    private int getQueuePosition(UUID playerId) {
        // Create a snapshot to avoid concurrent modification
        List<QueueEntry> snapshot = new ArrayList<>(queueEntries.values());

        List<UUID> sortedQueue = snapshot.stream()
                .sorted((e1, e2) -> {
                    int pointCompare = Integer.compare(e2.points, e1.points);
                    if (pointCompare != 0) return pointCompare;
                    return Integer.compare(e1.secondsPerPoint, e2.secondsPerPoint);
                })
                .map(e -> e.playerId)
                .toList();

        int index = sortedQueue.indexOf(playerId);
        return index == -1 ? -1 : index + 1;
    }

    private void loadServerConfig() {
        File configFile = new File(dataDirectory.toFile(), "server-config.yml");

        try {
            YamlConfigurationLoader loader = YamlConfigurationLoader.builder()
                    .file(configFile)
                    .build();

            CommentedConfigurationNode root;

            if (!configFile.exists()) {
                // Create default configuration
                root = loader.createNode();
                root.node("main-server").set("main").comment("The name of the main server in your Velocity configuration");
                root.node("queue-server").set("queue").comment("The name of the queue/lobby server in your Velocity configuration");
                root.node("main-server-max-players").set(100).comment("Maximum players allowed on the main server (admins can bypass this)");
                loader.save(root);
                logger.info("Created default server-config.yml");
            } else {
                root = loader.load();
            }

            mainServerName = root.node("main-server").getString("main");
            queueServerName = root.node("queue-server").getString("queue");
            mainServerMaxPlayers = root.node("main-server-max-players").getInt(100);

            logger.info("Loaded server configuration - Main: " + mainServerName + ", Queue: " + queueServerName);
        } catch (IOException e) {
            logger.error("Failed to load server configuration", e);
            mainServerName = "main";
            queueServerName = "queue";
            mainServerMaxPlayers = 100;
        }
    }

    private void loadPriorityConfig() {
        File configFile = new File(dataDirectory.toFile(), "priority-players.yml");

        try {
            YamlConfigurationLoader loader = YamlConfigurationLoader.builder()
                    .file(configFile)
                    .build();

            CommentedConfigurationNode root;

            if (!configFile.exists()) {
                // Create default configuration
                root = loader.createNode();
                root.node("priority-players").setList(String.class, Arrays.asList(
                        "00000000-0000-0000-0000-000000000000"
                )).comment("List of player UUIDs with priority queue access. Players can also get priority via the queue.priority permission.");
                loader.save(root);
                logger.info("Created default priority-players.yml");
            } else {
                root = loader.load();
            }

            priorityPlayers.clear();
            List<String> uuidStrings = root.node("priority-players").getList(String.class, new ArrayList<>());
            for (String uuidString : uuidStrings) {
                try {
                    priorityPlayers.add(UUID.fromString(uuidString));
                } catch (IllegalArgumentException e) {
                    logger.warn("Invalid UUID in priority-players.yml: " + uuidString);
                }
            }

            logger.info("Loaded " + priorityPlayers.size() + " priority players");
        } catch (IOException e) {
            logger.error("Failed to load priority configuration", e);
        }
    }

    private void loadPointConfig() {
        File configFile = new File(dataDirectory.toFile(), "queue-points.yml");

        try {
            YamlConfigurationLoader loader = YamlConfigurationLoader.builder()
                    .file(configFile)
                    .build();

            CommentedConfigurationNode root;

            if (!configFile.exists()) {
                // Create default configuration with example tiers
                root = loader.createNode();

                root.node("tiers", "queue.vip.diamond").set(10).comment("VIP Diamond: 1 point every 10 seconds");
                root.node("tiers", "queue.vip.gold").set(20).comment("VIP Gold: 1 point every 20 seconds");
                root.node("tiers", "queue.vip.silver").set(30).comment("VIP Silver: 1 point every 30 seconds");
                root.node("tiers", "queue.vip").set(45).comment("VIP: 1 point every 45 seconds");
                root.node("default-seconds").set(60).comment("Default for players without any tier permission");

                loader.save(root);
                logger.info("Created default queue-points.yml");
            } else {
                root = loader.load();
            }

            pointTiers.clear();
            CommentedConfigurationNode tiersNode = root.node("tiers");

            if (!tiersNode.virtual()) {
                for (Map.Entry<Object, ? extends CommentedConfigurationNode> entry : tiersNode.childrenMap().entrySet()) {
                    String permission = entry.getKey().toString();
                    int seconds = entry.getValue().getInt(60);
                    pointTiers.put(permission, seconds);
                }
            }

            logger.info("Loaded " + pointTiers.size() + " queue point tiers");
        } catch (IOException e) {
            logger.error("Failed to load queue points configuration", e);
        }
    }

    // Queue entry class to track player queue data
    private static class QueueEntry {
        UUID playerId;
        long joinTime;
        int points;
        int secondsPerPoint;

        QueueEntry(UUID playerId, int secondsPerPoint) {
            this.playerId = playerId;
            this.joinTime = System.currentTimeMillis();
            this.points = 0;
            this.secondsPerPoint = secondsPerPoint;
        }
    }

    public class QueueCommand implements SimpleCommand {

        @Override
        public void execute(Invocation invocation) {
            CommandSource source = invocation.source();
            String[] args = invocation.arguments();

            if (args.length == 0) {
                sendHelp(source);
                return;
            }

            switch (args[0].toLowerCase()) {
                case "info":
                    handleInfo(source);
                    break;
                case "pull":
                    if (!source.hasPermission("queue.admin")) {
                        source.sendMessage(Component.text("You don't have permission to use this command!", NamedTextColor.RED));
                        return;
                    }
                    if (args.length < 2) {
                        source.sendMessage(Component.text("Usage: /queue pull <player>", NamedTextColor.RED));
                        return;
                    }
                    handlePull(source, args[1]);
                    break;
                case "pause":
                    if (!source.hasPermission("queue.admin")) {
                        source.sendMessage(Component.text("You don't have permission to use this command!", NamedTextColor.RED));
                        return;
                    }
                    handlePause(source);
                    break;
                case "resume":
                    if (!source.hasPermission("queue.admin")) {
                        source.sendMessage(Component.text("You don't have permission to use this command!", NamedTextColor.RED));
                        return;
                    }
                    handleResume(source);
                    break;
                case "status":
                    handleStatus(source);
                    break;
                case "credits":
                    handleCredits(source);
                    break;
                case "reload":
                    if (!source.hasPermission("queue.admin")) {
                        source.sendMessage(Component.text("You don't have permission to use this command!", NamedTextColor.RED));
                        return;
                    }
                    handleReload(source);
                    break;
                case "list":
                    if (!source.hasPermission("queue.admin")) {
                        source.sendMessage(Component.text("You don't have permission to use this command!", NamedTextColor.RED));
                        return;
                    }
                    handleList(source);
                    break;
                default:
                    sendHelp(source);
                    break;
            }
        }

        @Override
        public List<String> suggest(Invocation invocation) {
            String[] args = invocation.arguments();

            if (args.length == 0 || args.length == 1) {
                List<String> suggestions = new ArrayList<>();
                suggestions.add("info");
                suggestions.add("status");
                suggestions.add("credits");
                if (invocation.source().hasPermission("queue.admin")) {
                    suggestions.add("pull");
                    suggestions.add("pause");
                    suggestions.add("resume");
                    suggestions.add("reload");
                    suggestions.add("list");
                }
                return suggestions;
            }

            if (args.length == 2 && args[0].equalsIgnoreCase("pull")) {
                return server.getAllPlayers().stream()
                        .map(Player::getUsername)
                        .filter(name -> name.toLowerCase().startsWith(args[1].toLowerCase()))
                        .toList();
            }

            return Collections.emptyList();
        }

        private void sendHelp(CommandSource source) {
            source.sendMessage(Component.text("=== Queue System Help ===", NamedTextColor.GOLD));
            source.sendMessage(Component.text("/queue info - View your queue position", NamedTextColor.YELLOW));
            source.sendMessage(Component.text("/queue status - View queue system status", NamedTextColor.YELLOW));
            source.sendMessage(Component.text("/queue credits - View plugin credits", NamedTextColor.YELLOW));

            if (source.hasPermission("queue.admin")) {
                source.sendMessage(Component.text("/queue pull <player> - Pull a player from queue to main server", NamedTextColor.YELLOW));
                source.sendMessage(Component.text("/queue pause - Pause the queue from processing", NamedTextColor.YELLOW));
                source.sendMessage(Component.text("/queue resume - Resume the queue processing", NamedTextColor.YELLOW));
                source.sendMessage(Component.text("/queue list - List all players in queue", NamedTextColor.YELLOW));
                source.sendMessage(Component.text("/queue reload - Reload configuration files", NamedTextColor.YELLOW));
            }
        }

        private void handleInfo(CommandSource source) {
            if (!(source instanceof Player)) {
                source.sendMessage(Component.text("This command can only be used by players!", NamedTextColor.RED));
                return;
            }

            Player player = (Player) source;

            if (player.hasPermission("queue.admin")) {
                source.sendMessage(Component.text("You have admin access - you bypass the queue!", NamedTextColor.GOLD));
                return;
            }

            int position = getQueuePosition(player.getUniqueId());

            if (position == -1) {
                source.sendMessage(Component.text("You are not in the queue.", NamedTextColor.GREEN));
            } else {
                QueueEntry entry = queueEntries.get(player.getUniqueId());
                source.sendMessage(Component.text("Your queue position: " + position + "/" + queueEntries.size(), NamedTextColor.YELLOW));
                source.sendMessage(Component.text("Your points: " + entry.points + " (1 point per " + entry.secondsPerPoint + "s)", NamedTextColor.YELLOW));

                if (queuePaused) {
                    source.sendMessage(Component.text("Queue is currently PAUSED", NamedTextColor.RED));
                }
                if (!mainServerOnline) {
                    source.sendMessage(Component.text("Main server is currently OFFLINE", NamedTextColor.RED));
                }
            }
        }

        private void handlePull(CommandSource source, String playerName) {
            Optional<Player> targetPlayer = server.getPlayer(playerName);

            if (!targetPlayer.isPresent()) {
                source.sendMessage(Component.text("Player not found!", NamedTextColor.RED));
                return;
            }

            Player player = targetPlayer.get();
            UUID playerId = player.getUniqueId();

            if (!playersInQueue.contains(playerId)) {
                source.sendMessage(Component.text("This player is not in the queue!", NamedTextColor.RED));
                return;
            }

            queueEntries.remove(playerId);
            playersInQueue.remove(playerId);

            // Use forceBypass=true to bypass max player check
            connectToMainServer(player, false, false, true);
            source.sendMessage(Component.text("Pulled " + player.getUsername() + " from the queue!", NamedTextColor.GREEN));
        }

        private void handlePause(CommandSource source) {
            if (queuePaused) {
                source.sendMessage(Component.text("Queue is already paused!", NamedTextColor.YELLOW));
                return;
            }

            queuePaused = true;
            source.sendMessage(Component.text("Queue has been paused!", NamedTextColor.GREEN));
            notifyQueuePlayers(Component.text("The queue has been paused by an administrator.", NamedTextColor.YELLOW));
            logger.info("Queue paused by " + (source instanceof Player ? ((Player) source).getUsername() : "Console"));
        }

        private void handleResume(CommandSource source) {
            if (!queuePaused) {
                source.sendMessage(Component.text("Queue is not paused!", NamedTextColor.YELLOW));
                return;
            }

            queuePaused = false;
            source.sendMessage(Component.text("Queue has been resumed!", NamedTextColor.GREEN));
            notifyQueuePlayers(Component.text("The queue has been resumed!", NamedTextColor.GREEN));
            logger.info("Queue resumed by " + (source instanceof Player ? ((Player) source).getUsername() : "Console"));
        }

        private void handleStatus(CommandSource source) {
            source.sendMessage(Component.text("=== Queue System Status ===", NamedTextColor.GOLD));
            source.sendMessage(Component.text("Players in queue: " + queueEntries.size(), NamedTextColor.YELLOW));
            source.sendMessage(Component.text("Queue paused: " + (queuePaused ? "YES" : "NO"), queuePaused ? NamedTextColor.RED : NamedTextColor.GREEN));
            source.sendMessage(Component.text("Main server online: " + (mainServerOnline ? "YES" : "NO"), mainServerOnline ? NamedTextColor.GREEN : NamedTextColor.RED));

            Optional<RegisteredServer> mainServer = server.getServer(mainServerName);
            if (mainServer.isPresent()) {
                int current = mainServer.get().getPlayersConnected().size();
                source.sendMessage(Component.text("Main server players: " + current + "/" + mainServerMaxPlayers, NamedTextColor.YELLOW));
            }
        }

        private void handleCredits(CommandSource source) {
            source.sendMessage(Component.text("=== Queue Plugin Credits ===", NamedTextColor.GOLD));
            source.sendMessage(Component.text("Developed by ProjectPersistence", NamedTextColor.YELLOW));
            source.sendMessage(
                    Component.text("GitHub: ", NamedTextColor.YELLOW)
                            .append(Component.text("https://github.com/ProjectPersistence", NamedTextColor.AQUA)
                                    .clickEvent(net.kyori.adventure.text.event.ClickEvent.openUrl("https://github.com/ProjectPersistence"))
                                    .hoverEvent(net.kyori.adventure.text.event.HoverEvent.showText(Component.text("Click to open in browser"))))
            );
        }

        private void handleReload(CommandSource source) {
            loadServerConfig();
            loadPriorityConfig();
            loadPointConfig();
            source.sendMessage(Component.text("Configuration reloaded successfully!", NamedTextColor.GREEN));
        }

        private void handleList(CommandSource source) {
            if (queueEntries.isEmpty()) {
                source.sendMessage(Component.text("The queue is empty.", NamedTextColor.YELLOW));
                return;
            }

            source.sendMessage(Component.text("=== Players in Queue (" + queueEntries.size() + ") ===", NamedTextColor.GOLD));

            // Create a snapshot to avoid concurrent modification
            List<QueueEntry> snapshot = new ArrayList<>(queueEntries.values());

            List<UUID> sortedQueue = snapshot.stream()
                    .sorted((e1, e2) -> {
                        int pointCompare = Integer.compare(e2.points, e1.points);
                        if (pointCompare != 0) return pointCompare;
                        return Integer.compare(e1.secondsPerPoint, e2.secondsPerPoint);
                    })
                    .map(e -> e.playerId)
                    .toList();

            int position = 1;
            for (UUID playerId : sortedQueue) {
                Optional<Player> player = server.getPlayer(playerId);
                QueueEntry entry = queueEntries.get(playerId);
                if (entry != null) {
                    if (player.isPresent()) {
                        source.sendMessage(Component.text(position + ". " + player.get().getUsername() +
                                " - Points: " + entry.points + " (" + entry.secondsPerPoint + "s/pt)", NamedTextColor.YELLOW));
                    } else {
                        source.sendMessage(Component.text(position + ". (Disconnected) - Points: " + entry.points, NamedTextColor.GRAY));
                    }
                }
                position++;
            }
        }
    }
}
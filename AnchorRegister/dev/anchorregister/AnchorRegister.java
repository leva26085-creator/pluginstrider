package dev.anchorregister;

import io.papermc.paper.connection.PlayerConfigurationConnection;
import io.papermc.paper.event.connection.PlayerConnectionValidateLoginEvent;
import net.kyori.adventure.text.Component;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.Filter;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.config.Configuration;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent.Result;
import org.bukkit.plugin.java.JavaPlugin;

public final class AnchorRegister extends JavaPlugin implements Listener {
   private static final String UNAVAILABLE = "Сервис входа временно недоступен.";
   private static final String DUPLICATE = "Этот ник уже подключён к серверу.";
   private static final long AUTH_TIMEOUT = Duration.ofMinutes(2L).toNanos();
   private static final long ATTEMPT_WINDOW = Duration.ofMinutes(1L).toNanos();
   private static final int NAME_ATTEMPT_LIMIT = 5;
   private static final int IP_ATTEMPT_LIMIT = 20;
   private static final int MIN_PASSWORD_LENGTH = 8;
   private static final int MAX_PASSWORD_LENGTH = 64;
   private final Map<UUID, AnchorRegister.Connection> connections = new ConcurrentHashMap<>();
   private final Map<UUID, AnchorRegister.Connection> awaitingAuth = new HashMap<>();
   private final Map<String, AnchorRegister.Attempts> nameAttempts = new HashMap<>();
   private final Map<String, AnchorRegister.Attempts> ipAttempts = new HashMap<>();
   private volatile Accounts accounts;
   private PacketGate packetGate;
   private ThreadPoolExecutor passwordWorkers;
   private Configuration logConfiguration;
   private Filter logFilter;
   private RegistrationLobby lobby;
   private NetworkBridge network;

   public void onEnable() {
      this.saveDefaultConfig();
      this.getServer().getPluginManager().registerEvents(this, this);
      this.getServer().getPluginManager().registerEvents(new PlayerGuard(this), this);

      for (String name : List.of("register", "login")) {
         PluginCommand command = Objects.requireNonNull(this.getCommand(name));
         command.setExecutor(this);
         command.setTabCompleter((sender, cmd, alias, args) -> List.of());
      }
      PluginCommand hubCommand = this.getCommand("hub");
      if (hubCommand != null) {
         hubCommand.setExecutor(this);
         hubCommand.setTabCompleter((sender, cmd, alias, args) -> List.of());
      }
      PluginCommand smpCommand = this.getCommand("smp");
      if (smpCommand != null) {
         smpCommand.setExecutor(this);
         smpCommand.setTabCompleter((sender, cmd, alias, args) -> List.of());
      }

      PluginCommand editorCommand = this.getCommand("regedit");
      if (editorCommand != null) {
         editorCommand.setExecutor(this);
         editorCommand.setTabCompleter((sender, cmd, alias, args) -> sender instanceof Player player && player.isOp() && !this.locked(player) && args.length == 1
             ? List.of("on", "save", "off").stream().filter(value -> value.startsWith(args[0].toLowerCase(java.util.Locale.ROOT))).toList() : List.of());
      }

      try {
         this.logConfiguration = ((LoggerContext)LogManager.getContext(false)).getConfiguration();
         this.logFilter = Objects.requireNonNull(AuthenticationCommand.commandLogFilter());
         this.logFilter.start();
         this.logConfiguration.addFilter(this.logFilter);
         this.packetGate = new PacketGate();
         this.accounts = new Accounts(this.getDataFolder().toPath().resolve("accounts"));
         if (this.getConfig().getBoolean("network.enabled", false)) {
            this.network = new NetworkBridge(this);
            this.packetGate.allowNetwork();
         }
         if (this.getConfig().getBoolean("lobby.enabled", false)) {
            this.lobby = new RegistrationLobby(this);
            this.getServer().getPluginManager().registerEvents(this.lobby, this);
         }
      } catch (ReflectiveOperationException | RuntimeException | IOException var4) {
         this.accounts = null;
         this.getLogger().log(Level.SEVERE, "Не удалось запустить авторизацию. Подключения заблокированы.", (Throwable)var4);
         this.getServer().getOnlinePlayers().forEach(player -> player.kickPlayer("Сервис входа временно недоступен."));
         return;
      }

      this.passwordWorkers = new ThreadPoolExecutor(
         2, 2, 0L, TimeUnit.SECONDS, new ArrayBlockingQueue<>(16), task -> Thread.ofPlatform().name("AnchorRegister-password").daemon().unstarted(task)
      );
      this.getServer().getOnlinePlayers().forEach(this::begin);
      this.getServer().getScheduler().runTaskTimer(this, this::tick, 2L, 2L);
   }

   public void onDisable() {
      if (this.lobby != null) {
         this.lobby.close();
      }
      if (this.passwordWorkers != null) {
         this.connections.values().forEach(AnchorRegister.Connection::cancelJob);
         this.passwordWorkers.shutdownNow();
      }

      this.connections.clear();
      this.awaitingAuth.clear();
      if (this.packetGate != null) {
         this.packetGate.unlockAll();
      }

      if (this.logFilter != null) {
         this.logConfiguration.removeFilter(this.logFilter);
         this.logFilter.stop();
      }
   }

   @EventHandler(
      priority = EventPriority.HIGHEST
   )
   public void onPreLogin(AsyncPlayerPreLoginEvent event) {
      if (event.getLoginResult() == Result.ALLOWED) {
         String name = Accounts.nameKey(event.getName());
         if (this.accounts == null) {
            event.disallow(Result.KICK_OTHER, "Сервис входа временно недоступен.");
         } else if (this.connections.containsKey(event.getUniqueId())
            || this.connections.values().stream().anyMatch(connection -> connection.name.equals(name))) {
            event.disallow(Result.KICK_OTHER, "Этот ник уже подключён к серверу.");
         }
      }
   }

   @EventHandler(
      priority = EventPriority.HIGHEST
   )
   public void onLogin(PlayerConnectionValidateLoginEvent event) {
      if (!event.isAllowed() || !(event.getConnection() instanceof PlayerConfigurationConnection connection)) {
         return;
      }
      String playerName = connection.getProfile().getName();
      if (this.accounts == null) {
         event.kickMessage(Component.text(UNAVAILABLE));
      } else if (playerName == null || !Accounts.validName(Accounts.nameKey(playerName))) {
         event.kickMessage(Component.text("Этот ник недопустим для регистрации."));
      } else if (this.getServer().getOnlinePlayers().stream().anyMatch(p -> p.getName().equalsIgnoreCase(playerName))) {
         event.kickMessage(Component.text(DUPLICATE));
      } else {
         try {
            this.packetGate.lock(connection);
         } catch (ReflectiveOperationException | RuntimeException exception) {
            this.getLogger().log(Level.SEVERE, "Не удалось ограничить подключение игрока " + playerName, exception);
            event.kickMessage(Component.text(UNAVAILABLE));
         }
      }
   }

   @EventHandler(
      priority = EventPriority.LOWEST
   )
   public void onJoin(PlayerJoinEvent event) {
      if (this.nameInUse(event.getPlayer())) {
         event.getPlayer().kickPlayer("Этот ник уже подключён к серверу.");
         event.setJoinMessage(null);
      } else {
         this.begin(event.getPlayer());
      }
   }

   @EventHandler(
      priority = EventPriority.MONITOR
   )
   public void onQuit(PlayerQuitEvent event) {
      if (this.network != null) this.network.forget(event.getPlayer());
      if (this.lobby != null) {
         this.lobby.forget(event.getPlayer());
      }
      AnchorRegister.Connection connection = this.connection(event.getPlayer());
      if (connection != null) {
         this.connections.remove(event.getPlayer().getUniqueId(), connection);
         this.awaitingAuth.remove(event.getPlayer().getUniqueId(), connection);
         connection.cancelJob();
      }
   }

   private boolean nameInUse(Player candidate) {
      for (Player player : this.getServer().getOnlinePlayers()) {
         if (player != candidate && player.getName().equalsIgnoreCase(candidate.getName())) {
            return true;
         }
      }

      return false;
   }

   private void begin(Player player) {
      if (this.accounts == null) {
         player.kickPlayer("Сервис входа временно недоступен.");
      } else {
         InetSocketAddress address = player.getAddress();
         if (address != null && address.getAddress() != null) {
            String name = Accounts.nameKey(player.getName());
            if (!Accounts.validName(name)) {
               player.kickPlayer("Этот ник недопустим для регистрации.");
            } else {
               if (this.lobby != null) {
                  this.lobby.prepare(player);
               }
               PacketGate.MovementGate gate = this.lockPackets(player);
               if (gate == null) {
                  player.kickPlayer("Сервис входа временно недоступен.");
               } else {
                  String ip = Accounts.ipKey(address.getAddress());
                  Accounts.Account account = this.accounts.get(name);
                  long age = account == null ? Long.MAX_VALUE : System.currentTimeMillis() - account.authenticatedAt();
                  boolean remembered = !this.getConfig().getBoolean("network.require-session", false) && this.getConfig().getBoolean("session.enabled", true) && account != null && account.ip().equals(ip) && age >= 0 && age < 3_600_000L;
                  AnchorRegister.Connection connection = new AnchorRegister.Connection(player, gate, name, ip, remembered);
                  connection.verified = remembered || this.lobby == null;
                  this.connections.put(player.getUniqueId(), connection);
                  if (remembered) {
                     this.packetGate.unlock(player);
                  } else {
                     gate.correct(player, org.bukkit.Bukkit.getCurrentTick());
                     gate.allowVerification(this.lobby != null);
                     this.awaitingAuth.put(player.getUniqueId(), connection);
                  }

                  player.updateCommands();
                  if (remembered) {
                     tell(player, ChatColor.GREEN, "Вход по сохранённой сессии. Повторная проверка не нужна.");
                     if (this.network != null) this.network.verified(player);
                     if (this.lobby != null) this.getServer().getScheduler().runTask(this, () -> { if (player.isOnline() && this.connection(player) == connection) this.lobby.complete(player); });
                  } else {
                     if (this.lobby != null) {
                        if (this.network == null) {
                           this.lobby.begin(player);
                        } else {
                           this.getServer().getScheduler().runTaskLater(this, () -> {
                              if (this.connection(player) == connection && !connection.authenticated) {
                                 this.lobby.begin(player);
                                 this.prompt(player);
                              }
                           }, 6L);
                        }
                     }
                     if ((this.network == null || this.lobby == null)
                         && !this.getConfig().getBoolean("network.require-session", false)) this.prompt(player);
                  }
                  if (this.network != null) this.network.begin(player);
               }
            }
         } else {
            player.kickPlayer("Не удалось определить ваш IP-адрес. Подключитесь снова.");
         }
      }
   }

   private PacketGate.MovementGate lockPackets(Player player) {
      try {
         return this.packetGate.lock(player);
      } catch (RuntimeException exception) {
         this.getLogger().log(Level.SEVERE, "Не удалось ограничить подключение игрока " + player.getName(), exception);
         return null;
      }
   }

   @EventHandler(
      priority = EventPriority.LOWEST
   )
   public void onAuthCommand(PlayerCommandPreprocessEvent event) {
      String[] parts = event.getMessage().substring(1).split("\\s+", 4);
      AuthenticationCommand command = AuthenticationCommand.fromLabel(parts[0]);
      if (command != null) {
         boolean alreadyCancelled = event.isCancelled();
         event.setCancelled(true);
         event.setMessage("/" + parts[0]);
         if (!alreadyCancelled) {
            this.authenticate(event.getPlayer(), command, Arrays.copyOfRange(parts, 1, parts.length));
         }
      }
   }

   public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
      if (sender instanceof Player player) {
         if (command.getName().equals("regedit")) {
            if (this.locked(player)) this.prompt(player);
            else if (!player.isOp()) tell(player, ChatColor.RED, "Редактирование доступно только оператору.");
            else if (this.lobby == null) tell(player, ChatColor.YELLOW, "Редактор доступен в хабе. Сначала введи /hub.");
            else this.lobby.editorCommand(player, args);
            return true;
         }
         if (command.getName().equals("smp")) {
            if (this.locked(player)) this.prompt(player);
            else if (this.network != null) this.network.route(player, "smp");
            else tell(player, ChatColor.RED, "Сервер ѕᴍᴘ недоступен.");
            return true;
         }
         if (command.getName().equals("hub")) {
            if (this.lobby == null && this.network != null && !this.locked(player)) {
               this.network.route(player, "hub");
               return true;
            }
            if (this.lobby == null) tell(player, ChatColor.RED, "Хаб недоступен.");
            else this.lobby.sendToHub(player);
            return true;
         }
         this.authenticate(player, AuthenticationCommand.fromLabel(command.getName()), args);
      } else {
         tell(sender, ChatColor.RED, "Эта команда доступна только игроку в игре.");
      }

      return true;
   }

   private void authenticate(Player player, AuthenticationCommand command, String[] args) {
      if (this.getConfig().getBoolean("network.require-session", false)) {
         tell(player, ChatColor.YELLOW, "Подключись через входной сервер сети.");
         return;
      }
      AnchorRegister.Connection connection = this.connection(player);
      if (this.accounts == null || connection == null) {
         tell(player, ChatColor.RED, "Сервис входа временно недоступен.");
      } else if (connection.authenticated) {
         tell(player, ChatColor.GREEN, "Вы уже вошли.");
      } else if (!connection.verified) {
         this.prompt(player);
      } else if (connection.job != null) {
         tell(player, ChatColor.YELLOW, "Подождите, проверяем пароль.");
      } else {
         Accounts.Account account = this.accounts.get(connection.name);
         boolean registering = command == AuthenticationCommand.REGISTER;
         if (registering != (account == null)) {
            this.prompt(player);
         } else if (args.length != (registering ? 2 : 1)) {
            tell(player, ChatColor.YELLOW, "Используйте " + this.authenticationUsage(player));
         } else {
            String password = args[0];
            int length = password.codePointCount(0, password.length());
            if (length < 8 || length > 64) {
               tell(player, ChatColor.RED, "Пароль должен содержать от 8 до 64 символов.");
            } else if (registering && !password.equals(args[1])) {
               tell(player, ChatColor.RED, "Пароли не совпадают.");
            } else {
               long now = System.nanoTime();
               AnchorRegister.Attempts byName = currentAttempts(this.nameAttempts.get(connection.name), now);
               AnchorRegister.Attempts byIp = currentAttempts(this.ipAttempts.get(connection.ip), now);
               if (byName.count() < 5 && byIp.count() < 20) {
                  char[] secret = password.toCharArray();

                  try {
                     connection.job = this.passwordWorkers.submit(() -> {
                        if (registering) {
                           return Accounts.Password.create(secret);
                        } else {
                           return account.password().matches(secret) ? account.password() : null;
                        }
                     });
                  } catch (RejectedExecutionException var15) {
                     Arrays.fill(secret, '\u0000');
                     tell(player, ChatColor.YELLOW, "Сервер занят. Попробуйте снова через несколько секунд.");
                     return;
                  }

                  this.nameAttempts.put(connection.name, byName.increment());
                  this.ipAttempts.put(connection.ip, byIp.increment());
               } else {
                  tell(player, ChatColor.RED, "Слишком много попыток. Попробуйте снова через минуту.");
               }
            }
         }
      }
   }

   private void tick() {
      if (this.awaitingAuth.isEmpty() && this.nameAttempts.isEmpty() && this.ipAttempts.isEmpty()) return;
      long tick = org.bukkit.Bukkit.getCurrentTick();
      long now = System.nanoTime();
      if (tick % 20 < 2) {
         this.nameAttempts.values().removeIf(attempts -> attempts.expired(now));
         this.ipAttempts.values().removeIf(attempts -> attempts.expired(now));
      }

      for (AnchorRegister.Connection connection : List.copyOf(this.awaitingAuth.values())) {
         if (connection.player.isOnline()) {
            if (now - connection.joinedAt >= AUTH_TIMEOUT) {
               connection.player.kickPlayer("Время для авторизации истекло. Подключитесь снова.");
            } else if (connection.job != null && connection.job.isDone()) {
               this.finish(connection);
            } else {
               connection.gate.correct(connection.player, tick);
            }
         }
      }
   }

   private void finish(AnchorRegister.Connection connection) {
      Future<Accounts.Password> job = connection.job;
      connection.job = null;
      Player player = connection.player;

      try {
         Accounts.Password password = job.get();
         if (password == null) {
            tell(player, ChatColor.RED, "Неверный пароль.");
            return;
         }

         boolean registered = this.accounts.get(connection.name) == null;
         this.accounts.save(connection.name, password, connection.ip, System.currentTimeMillis());
         this.nameAttempts.remove(connection.name);
         connection.authenticated = true;
         this.awaitingAuth.remove(player.getUniqueId(), connection);
         this.packetGate.unlock(player);
         if (this.network != null) this.network.verified(player);
         player.updateInventory();
         player.updateCommands();
         String message = registered ? "Регистрация завершена." : "Вы успешно вошли.";
         tell(player, ChatColor.GREEN, message + " Сессия входа действует 1 час.");
         if (this.lobby != null) {
            this.lobby.complete(player);
         }
      } catch (IOException | ExecutionException var7) {
         this.getLogger().log(Level.SEVERE, "Не удалось завершить авторизацию игрока " + connection.name, (Throwable)var7);
         tell(player, ChatColor.RED, "Ошибка авторизации. Попробуйте снова или обратитесь к администратору.");
      } catch (InterruptedException var8) {
         Thread.currentThread().interrupt();
      }
   }

   boolean locked(Player player) {
      AnchorRegister.Connection connection = this.connection(player);
      return connection == null || !connection.authenticated;
   }

   void resumeFromNetwork(Player player) {
      AnchorRegister.Connection connection = this.connection(player);
      if (connection == null || connection.authenticated) return;
      connection.verified = true;
      connection.authenticated = true;
      this.awaitingAuth.remove(player.getUniqueId(), connection);
      this.packetGate.unlock(player);
      if (this.lobby != null) {
         this.lobby.forget(player);
         player.getInventory().clear();
         player.setGameMode(org.bukkit.GameMode.ADVENTURE);
         this.lobby.complete(player);
      }
      player.updateInventory();
      player.updateCommands();
   }

   void prompt(Player player) {
      if (this.getConfig().getBoolean("network.require-session", false)) {
         player.sendActionBar("§bПодтверждаем вход в режим…");
         return;
      }
      if (this.accounts == null) {
         tell(player, ChatColor.RED, "Сервис входа временно недоступен.");
      } else if (!this.verified(player) && this.lobby != null) {
         this.lobby.prompt(player);
      } else {
         tell(player, ChatColor.YELLOW, "Для входа в игру введите " + this.authenticationUsage(player));
      }
   }

   String authenticationUsage(Player player) {
      return this.accounts != null && this.accounts.get(Accounts.nameKey(player.getName())) != null
         ? "/login (пароль)" : "/reg (пароль) (повтор)";
   }

   boolean authenticationCommandAllowed(Player player, String label) {
      AuthenticationCommand command = AuthenticationCommand.fromLabel(label);
      if (command == null) return label.equals("captcha") && !this.verified(player);
      boolean registered = this.accounts != null && this.accounts.get(Accounts.nameKey(player.getName())) != null;
      return registered ? command == AuthenticationCommand.LOGIN : command == AuthenticationCommand.REGISTER;
   }

   boolean verified(Player player) {
      AnchorRegister.Connection connection = this.connection(player);
      return connection != null && connection.verified;
   }

   void verificationPassed(Player player) {
      AnchorRegister.Connection connection = this.connection(player);
      if (connection == null || connection.verified) return;
      connection.verified = true;
      connection.gate.allowVerification(false);
      player.updateCommands();
      tell(player, ChatColor.GREEN, "Проверка пройдена.");
      this.prompt(player);
   }

   private AnchorRegister.Connection connection(Player player) {
      AnchorRegister.Connection connection = this.connections.get(player.getUniqueId());
      return connection != null && connection.player == player ? connection : null;
   }

   private static void tell(CommandSender sender, ChatColor color, String message) {
      sender.sendMessage(ChatColor.DARK_AQUA + "[ᴀɴᴄʜᴏʀ] " + color + message);
   }

   private static AnchorRegister.Attempts currentAttempts(AnchorRegister.Attempts attempts, long now) {
      return attempts != null && !attempts.expired(now) ? attempts : new AnchorRegister.Attempts(now, 0);
   }

   private record Attempts(long startedAt, int count) {
      boolean expired(long now) {
         return now - this.startedAt >= AnchorRegister.ATTEMPT_WINDOW;
      }

      AnchorRegister.Attempts increment() {
         return new AnchorRegister.Attempts(this.startedAt, this.count + 1);
      }
   }

   private static final class Connection {
      private final Player player;
      private final PacketGate.MovementGate gate;
      private final String name;
      private final String ip;
      private final long joinedAt = System.nanoTime();
      private volatile boolean authenticated;
      private boolean verified;
      private Future<Accounts.Password> job;

      private Connection(Player player, PacketGate.MovementGate gate, String name, String ip, boolean authenticated) {
         this.player = player;
         this.gate = gate;
         this.name = name;
         this.ip = ip;
         this.authenticated = authenticated;
      }

      private void cancelJob() {
         if (this.job != null) {
            this.job.cancel(false);
         }
      }
   }
}

package dev.anchoreconomy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;

/** One WAL-backed ledger shared by this PC's backends. No floating point balances. */
public final class AnchorEconomy extends JavaPlugin implements Listener {
    public record Stats(long sapphires,long kills) {}
    public record ModeStats(long playMillis,long kills,long wins) {public ModeStats(long playMillis,long kills){this(playMillis,kills,0);}}
    public record Leader(UUID id,String name,long kills) {}
    public record LeaderPage(List<Leader> entries,long total,int offset,boolean more){}
    private static final long MAX=1_000_000_000_000L;
    private final Map<UUID,Stats> cache=new ConcurrentHashMap<>();
    private final ExecutorService worker=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"AnchorLedger");t.setDaemon(true);return t;});
    private Connection db;
    private ModeStatistics modeStatistics;

    @Override public void onEnable() {
        saveDefaultConfig();
        try {
            Path path=Path.of(getConfig().getString("storage-path","../data/players.sqlite")).toAbsolutePath().normalize();
            Files.createDirectories(path.getParent()); Class.forName("org.sqlite.JDBC");
            db=DriverManager.getConnection("jdbc:sqlite:"+path);
            try(Statement sql=db.createStatement()) {
                sql.execute("PRAGMA busy_timeout=5000"); sql.execute("PRAGMA journal_mode=WAL"); sql.execute("PRAGMA synchronous=FULL");
                sql.execute("CREATE TABLE IF NOT EXISTS players(uuid TEXT PRIMARY KEY, name TEXT NOT NULL, sapphires INTEGER NOT NULL DEFAULT 0 CHECK(typeof(sapphires)='integer' AND sapphires>=0 AND sapphires<=1000000000000), kills INTEGER NOT NULL DEFAULT 0 CHECK(kills>=0))");
                sql.execute("CREATE INDEX IF NOT EXISTS player_name ON players(name COLLATE NOCASE)");
                sql.execute("CREATE TABLE IF NOT EXISTS ledger(id INTEGER PRIMARY KEY, uuid TEXT NOT NULL, delta INTEGER NOT NULL, balance INTEGER NOT NULL, actor TEXT NOT NULL, created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP)");
            }
            String role=getConfig().getString("statistics-mode","auto");
            if(role.equals("auto"))role=Bukkit.getPluginManager().getPlugin("AnchorMitap")!=null?"mitap":Bukkit.getWorld("SMP")!=null?"smp":"hub";
            modeStatistics=new ModeStatistics(db,role,Bukkit.getWorlds().isEmpty()?null:Bukkit.getWorlds().getFirst().getWorldFolder().toPath().toAbsolutePath());
            submit(modeStatistics::importHistory);
        } catch(Exception error) { throw new IllegalStateException("Не удалось открыть хранилище сапфиров",error); }
        Bukkit.getPluginManager().registerEvents(this,this);
        Bukkit.getScheduler().runTaskTimer(this,()->{
            Map<UUID,String> online=new HashMap<>();Bukkit.getOnlinePlayers().forEach(p->online.put(p.getUniqueId(),p.getName()));
            var snapshots=snapshots();
            submit(()->{for(var player:online.entrySet()){ensure(player.getKey(),player.getValue());refresh(player.getKey());}for(var value:snapshots)modeStatistics.save(value);});
        },1,20);
    }
    @EventHandler public void join(PlayerJoinEvent event) {
        UUID id=event.getPlayer().getUniqueId();String name=event.getPlayer().getName();
        submit(()->{ensure(id,name);refresh(id);});
    }
    public Stats stats(UUID id) {return cache.getOrDefault(id,new Stats(0,0));}
    private List<ModeStatistics.Snapshot> snapshots(){return modeStatistics.enabled()?Bukkit.getOnlinePlayers().stream().map(modeStatistics::snapshot).toList():List.of();}
    public CompletableFuture<ModeStats> statistics(UUID id,String mode){
        var snapshots=snapshots();CompletableFuture<ModeStats> future=new CompletableFuture<>();
        if(worker.isShutdown()){future.completeExceptionally(new IllegalStateException("Statistics offline"));return future;}
        worker.execute(()->{try{for(var value:snapshots)modeStatistics.save(value);future.complete(modeStatistics.read(id,mode));}catch(Exception error){future.completeExceptionally(error);}});return future;
    }
    public CompletableFuture<List<Leader>> leaders(String mode,int limit){
        var snapshots=snapshots();CompletableFuture<List<Leader>> future=new CompletableFuture<>();
        if(worker.isShutdown()){future.completeExceptionally(new IllegalStateException("Statistics offline"));return future;}
        worker.execute(()->{try{for(var value:snapshots)modeStatistics.save(value);future.complete(modeStatistics.leaders(mode,limit));}catch(Exception error){future.completeExceptionally(error);}});return future;
    }
    public CompletableFuture<LeaderPage> leaderboard(String mode,int offset,int limit){var snapshots=snapshots();CompletableFuture<LeaderPage> future=new CompletableFuture<>();worker.execute(()->{try{for(var value:snapshots)modeStatistics.save(value);future.complete(modeStatistics.page(mode,offset,limit));}catch(Exception e){future.completeExceptionally(e);}});return future;}
    public void recordMitapWin(String match,Collection<UUID> winners){var ids=List.copyOf(winners);submit(()->modeStatistics.win(match,ids));}
    @EventHandler public void statisticsQuit(org.bukkit.event.player.PlayerQuitEvent event){if(modeStatistics.enabled()){var value=modeStatistics.snapshot(event.getPlayer());submit(()->modeStatistics.save(value));}}
    public void recordKill(Player player) {
        UUID id=player.getUniqueId();String name=player.getName();
        submit(()->{ensure(id,name);try(PreparedStatement sql=db.prepareStatement("UPDATE players SET kills=kills+1 WHERE uuid=?")){sql.setString(1,id.toString());sql.executeUpdate();}refresh(id);});
    }
    public void award(Player player,long amount,String reason) {
        if(amount<=0)return;UUID id=player.getUniqueId();String name=player.getName();
        submit(()->{ensure(id,name);change(id,"give",amount,reason);});
    }
    private void ensure(UUID id,String name) throws SQLException {
        try(PreparedStatement sql=db.prepareStatement("INSERT INTO players(uuid,name) VALUES(?,?) ON CONFLICT(uuid) DO UPDATE SET name=excluded.name")) {
            sql.setString(1,id.toString());sql.setString(2,name);sql.executeUpdate();
        }
    }
    private Stats refresh(UUID id) throws SQLException {
        try(PreparedStatement sql=db.prepareStatement("SELECT sapphires,kills FROM players WHERE uuid=?")) {
            sql.setString(1,id.toString());try(ResultSet row=sql.executeQuery()) {
                Stats value=row.next()?new Stats(row.getLong(1),row.getLong(2)):new Stats(0,0);cache.put(id,value);return value;
            }
        }
    }
    private UUID lookup(String name) throws SQLException {
        try(PreparedStatement sql=db.prepareStatement("SELECT uuid FROM players WHERE name=? COLLATE NOCASE")) {
            sql.setString(1,name);try(ResultSet row=sql.executeQuery()){return row.next()?UUID.fromString(row.getString(1)):null;}
        }
    }
    private long change(UUID id,String action,long amount,String actor) throws SQLException {
        // A single BEGIN IMMEDIATE serializes transactions across all three JVMs.
        try(Statement tx=db.createStatement()){tx.execute("BEGIN IMMEDIATE");}
        try {
            long before=refresh(id).sapphires();
            long after=switch(action){case "give"->Math.addExact(before,amount);case "take"->before-amount;default->amount;};
            if(after<0)throw new IllegalArgumentException("Недостаточно сапфиров.");
            if(after>MAX)throw new IllegalArgumentException("Слишком большой баланс.");
            try(PreparedStatement sql=db.prepareStatement("UPDATE players SET sapphires=? WHERE uuid=?")){sql.setLong(1,after);sql.setString(2,id.toString());sql.executeUpdate();}
            try(PreparedStatement sql=db.prepareStatement("INSERT INTO ledger(uuid,delta,balance,actor) VALUES(?,?,?,?)")) {
                sql.setString(1,id.toString());sql.setLong(2,after-before);sql.setLong(3,after);sql.setString(4,actor);sql.executeUpdate();
            }
            try(Statement tx=db.createStatement()){tx.execute("COMMIT");}
            refresh(id);return after;
        } catch(Exception error) {
            try(Statement tx=db.createStatement()){tx.execute("ROLLBACK");}
            if(error instanceof SQLException sql)throw sql;
            if(error instanceof RuntimeException runtime)throw runtime;
            throw new SQLException(error);
        }
    }
    @Override public boolean onCommand(CommandSender sender,Command command,String label,String[] args) {
        if(args.length==0 || (args[0].equalsIgnoreCase("balance")&&args.length==1)) {
            if(sender instanceof Player player) submit(()->{Stats stats=refresh(player.getUniqueId());reply(sender,"Сапфиры: "+stats.sapphires());});
            else reply(sender,"Использование: /sapphires balance <ник>");return true;
        }
        if(!sender.hasPermission("anchor.economy.admin")){reply(sender,"Недостаточно прав.");return true;}
        if(args.length==2&&args[0].equalsIgnoreCase("balance")) {
            String name=args[1];submit(()->{UUID id=lookup(name);reply(sender,id==null?"Игрок ещё не посещал сервер.":"Сапфиры "+name+": "+refresh(id).sapphires());});return true;
        }
        if(args.length!=3 || !Set.of("give","take","set").contains(args[0].toLowerCase(Locale.ROOT))) {
            reply(sender,"Использование: /sapphires give|take|set <ник> <количество>");return true;
        }
        long amount;try{amount=Long.parseLong(args[2]);if(amount<0||amount>MAX)throw new NumberFormatException();}
        catch(NumberFormatException error){reply(sender,"Количество должно быть целым числом от 0 до "+MAX+".");return true;}
        String action=args[0].toLowerCase(Locale.ROOT),name=args[1],actor=sender.getName();
        submit(()->{UUID id=lookup(name);if(id==null){reply(sender,"Игрок ещё не посещал сервер.");return;}
            try{long balance=change(id,action,amount,actor);reply(sender,"Баланс "+name+": "+balance+" сапфиров.");}
            catch(IllegalArgumentException|ArithmeticException error){reply(sender,error.getMessage());}
        });return true;
    }
    @Override public List<String> onTabComplete(CommandSender sender,Command command,String alias,String[] args) {
        List<String> options=args.length==1?(sender.hasPermission("anchor.economy.admin")?List.of("balance","give","take","set"):List.of("balance")):
            args.length==2&&sender.hasPermission("anchor.economy.admin")?Bukkit.getOnlinePlayers().stream().map(Player::getName).toList():List.of();
        String prefix=args.length==0?"":args[args.length-1].toLowerCase(Locale.ROOT);return options.stream().filter(v->v.toLowerCase(Locale.ROOT).startsWith(prefix)).toList();
    }
    private void reply(CommandSender sender,String message) {
        if(!isEnabled())return;Bukkit.getScheduler().runTask(this,()->sender.sendMessage(Component.text("[ᴀɴᴄʜᴏʀ] ",NamedTextColor.DARK_AQUA).append(Component.text(message,NamedTextColor.AQUA))));
    }
    @FunctionalInterface private interface Job {void run()throws Exception;}
    private void submit(Job job) {
        if(worker.isShutdown())return;worker.execute(()->{try{job.run();}catch(Exception error){getLogger().log(java.util.logging.Level.SEVERE,"Ошибка хранилища сапфиров",error);}});
    }
    @Override public void onDisable() {
        if(modeStatistics!=null){var snapshots=snapshots();submit(()->{for(var value:snapshots)modeStatistics.save(value);});}
        worker.shutdown();try{if(worker.awaitTermination(10,TimeUnit.SECONDS)&&db!=null)db.close();}
        catch(Exception error){getLogger().warning("Не удалось закрыть хранилище: "+error.getMessage());}
    }
}

package dev.anchoreconomy;

import com.google.gson.JsonParser;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import org.bukkit.Statistic;
import org.bukkit.entity.Player;

/** Accessed exclusively by the existing ledger worker. Absolute native counters prevent double counting. */
final class ModeStatistics {
 record Snapshot(UUID id,String name,long millis,long kills){}
 private final Connection db;private final String role;private final Path world;
 ModeStatistics(Connection db,String role,Path world)throws SQLException{
  this.db=db;this.role=role;this.world=world;
  try(var sql=db.createStatement()){
   sql.execute("CREATE TABLE IF NOT EXISTS mode_statistics(uuid TEXT NOT NULL, mode TEXT NOT NULL CHECK(mode IN ('smp','mitap')), name TEXT NOT NULL, play_millis INTEGER NOT NULL DEFAULT 0 CHECK(play_millis>=0), kills INTEGER NOT NULL DEFAULT 0 CHECK(kills>=0), PRIMARY KEY(uuid,mode))");
   sql.execute("CREATE INDEX IF NOT EXISTS mode_kills ON mode_statistics(mode,kills DESC,name COLLATE NOCASE)");
   sql.execute("BEGIN IMMEDIATE");try{boolean wins=false;try(var rows=sql.executeQuery("PRAGMA table_info(mode_statistics)")){while(rows.next())if(rows.getString("name").equals("wins"))wins=true;}if(!wins)sql.execute("ALTER TABLE mode_statistics ADD COLUMN wins INTEGER NOT NULL DEFAULT 0");sql.execute("COMMIT");}catch(SQLException error){sql.execute("ROLLBACK");throw error;}
   sql.execute("CREATE TABLE IF NOT EXISTS mitap_wins(match_id TEXT NOT NULL,uuid TEXT NOT NULL,PRIMARY KEY(match_id,uuid))");
   // Until this update recordKill was called only by Mitap, including combat-log zombies.
   sql.execute("INSERT INTO mode_statistics(uuid,mode,name,play_millis,kills) SELECT uuid,'mitap',name,0,kills FROM players WHERE kills>0 ON CONFLICT(uuid,mode) DO UPDATE SET kills=MAX(mode_statistics.kills,excluded.kills),name=excluded.name");
  }
 }
 boolean enabled(){return role.equals("smp")||role.equals("mitap");}
 Snapshot snapshot(Player p){return new Snapshot(p.getUniqueId(),p.getName(),Math.max(0L,p.getStatistic(Statistic.PLAY_ONE_MINUTE))*50L,Math.max(0L,p.getStatistic(Statistic.PLAYER_KILLS)));}
 void save(Snapshot value)throws SQLException{if(enabled())save(value,role);}
 private void save(Snapshot v,String mode)throws SQLException{
  long kills=v.kills;if(mode.equals("mitap")){try(var sql=db.prepareStatement("SELECT kills FROM players WHERE uuid=?")){sql.setString(1,v.id.toString());try(var rows=sql.executeQuery()){kills=rows.next()?rows.getLong(1):0;}}}
  try(var sql=db.prepareStatement("INSERT INTO mode_statistics(uuid,mode,name,play_millis,kills) VALUES(?,?,?,?,?) ON CONFLICT(uuid,mode) DO UPDATE SET name=excluded.name, play_millis=MAX(mode_statistics.play_millis,excluded.play_millis), kills=MAX(mode_statistics.kills,excluded.kills)")){
   sql.setString(1,v.id.toString());sql.setString(2,mode);sql.setString(3,v.name);sql.setLong(4,v.millis);sql.setLong(5,kills);sql.executeUpdate();
  }
 }
 void importHistory()throws Exception{
  if(!enabled()||world==null)return;Path directory=world.resolve("stats");if(!Files.isDirectory(directory))return;
  Map<UUID,String> names=new HashMap<>();try(var sql=db.createStatement();var rows=sql.executeQuery("SELECT uuid,name FROM players")){while(rows.next())names.put(UUID.fromString(rows.getString(1)),rows.getString(2));}
  Path users=world.getParent().resolve("usercache.json");if(Files.isRegularFile(users))try(var reader=Files.newBufferedReader(users)){for(var entry:JsonParser.parseReader(reader).getAsJsonArray()){var o=entry.getAsJsonObject();names.putIfAbsent(UUID.fromString(o.get("uuid").getAsString()),o.get("name").getAsString());}}
  try(var files=Files.list(directory)){
   for(Path file:files.filter(p->p.getFileName().toString().endsWith(".json")).toList()){
    UUID id;try{id=UUID.fromString(file.getFileName().toString().replace(".json",""));}catch(IllegalArgumentException invalid){continue;}
    try(var reader=Files.newBufferedReader(file)){
     var stats=JsonParser.parseReader(reader).getAsJsonObject().getAsJsonObject("stats");if(stats==null)continue;var custom=stats.getAsJsonObject("minecraft:custom");if(custom==null)continue;
     long ticks=custom.has("minecraft:play_time")?Math.max(0,custom.get("minecraft:play_time").getAsLong()):0;
     long kills=custom.has("minecraft:player_kills")?Math.max(0,custom.get("minecraft:player_kills").getAsLong()):0;
     save(new Snapshot(id,names.getOrDefault(id,id.toString().substring(0,8)),Math.multiplyExact(ticks,50L),kills),role);
    }catch(com.google.gson.JsonParseException|ArithmeticException invalid){/* A malformed historical file cannot reset saved statistics. */}
   }
  }
 }
 AnchorEconomy.ModeStats read(UUID id,String mode)throws SQLException{
  check(mode);try(var sql=db.prepareStatement("SELECT play_millis,kills,wins FROM mode_statistics WHERE uuid=? AND mode=?")){sql.setString(1,id.toString());sql.setString(2,mode);try(var rows=sql.executeQuery()){return rows.next()?new AnchorEconomy.ModeStats(rows.getLong(1),rows.getLong(2),rows.getLong(3)):new AnchorEconomy.ModeStats(0,0);}}
 }
 List<AnchorEconomy.Leader> leaders(String mode,int limit)throws SQLException{
  check(mode);List<AnchorEconomy.Leader> result=new ArrayList<>();try(var sql=db.prepareStatement("SELECT uuid,name,kills FROM mode_statistics WHERE mode=? AND kills>0 ORDER BY kills DESC,name COLLATE NOCASE,uuid LIMIT ?")){sql.setString(1,mode);sql.setInt(2,Math.clamp(limit,1,10));try(var rows=sql.executeQuery()){while(rows.next())result.add(new AnchorEconomy.Leader(UUID.fromString(rows.getString(1)),rows.getString(2),rows.getLong(3)));}}return List.copyOf(result);
 }
 private void check(String mode){if(!mode.equals("smp")&&!mode.equals("mitap"))throw new IllegalArgumentException("Unknown statistics mode");}
 AnchorEconomy.LeaderPage page(String mode,int offset,int limit)throws SQLException{check(mode);offset=Math.max(0,offset);limit=Math.clamp(limit,1,10);long total;try(var q=db.prepareStatement("SELECT COUNT(*) FROM mode_statistics WHERE mode=? AND kills>0")){q.setString(1,mode);try(var r=q.executeQuery()){total=r.getLong(1);}}List<AnchorEconomy.Leader> result=new ArrayList<>();try(var q=db.prepareStatement("SELECT uuid,name,kills FROM mode_statistics WHERE mode=? AND kills>0 ORDER BY kills DESC,name COLLATE NOCASE,uuid LIMIT ? OFFSET ?")){q.setString(1,mode);q.setInt(2,limit);q.setInt(3,offset);try(var r=q.executeQuery()){while(r.next())result.add(new AnchorEconomy.Leader(UUID.fromString(r.getString(1)),r.getString(2),r.getLong(3)));}}return new AnchorEconomy.LeaderPage(List.copyOf(result),total,offset,offset+result.size()<total);}
 void win(String match,Collection<UUID> winners)throws SQLException{try(var tx=db.createStatement()){tx.execute("BEGIN IMMEDIATE");try{for(UUID id:winners){int added;try(var q=db.prepareStatement("INSERT OR IGNORE INTO mitap_wins VALUES(?,?)")){q.setString(1,match);q.setString(2,id.toString());added=q.executeUpdate();}if(added==1)try(var q=db.prepareStatement("INSERT INTO mode_statistics(uuid,mode,name,play_millis,kills,wins) VALUES(?,'mitap',COALESCE((SELECT name FROM players WHERE uuid=?),?),0,0,1) ON CONFLICT(uuid,mode) DO UPDATE SET wins=mode_statistics.wins+1")){q.setString(1,id.toString());q.setString(2,id.toString());q.setString(3,id.toString().substring(0,8));q.executeUpdate();}}tx.execute("COMMIT");}catch(SQLException e){tx.execute("ROLLBACK");throw e;}}}
}

package dev.anchorregister;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.logging.Level;
import org.bukkit.*;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/** Editor authority is an authenticated operator session, never the stored recovery file. */
final class RegistrationEditor {
 private record State(Location location,GameMode mode,boolean flight,boolean flying,int slot,int level,float exp,ItemStack[] items){}
 private final AnchorRegister plugin;
 private final RegistrationLobby lobby;
 private final Map<UUID,State> states=new HashMap<>();
 private final Set<UUID> editing=new HashSet<>();
 private final Path file;
 RegistrationEditor(AnchorRegister plugin,RegistrationLobby lobby)throws IOException {
  this.plugin=plugin;this.lobby=lobby;file=plugin.getDataFolder().toPath().resolve("editor-states.yml");
  if(Files.exists(file))try{
   YamlConfiguration data=new YamlConfiguration();data.load(file.toFile());
   for(String key:data.getKeys(false)){UUID id=UUID.fromString(key);String base=key+".";Location where=data.getLocation(base+"location");if(where==null||where.getWorld()!=lobby.hubLocation().getWorld())throw new IllegalArgumentException("Invalid editor return world");
    List<?> list=data.getList(base+"items",List.of());if(list.size()<41||list.size()>64)throw new IllegalArgumentException("Invalid editor inventory");ItemStack[] items=new ItemStack[list.size()];for(int i=0;i<items.length;i++)items[i]=list.get(i)==null?null:((ItemStack)list.get(i)).clone();
    states.put(id,new State(where,GameMode.valueOf(data.getString(base+"mode","CREATIVE")),data.getBoolean(base+"flight"),data.getBoolean(base+"flying"),Math.clamp(data.getInt(base+"slot"),0,8),Math.max(0,data.getInt(base+"level")),(float)Math.clamp(data.getDouble(base+"exp"),0,1),items));
   }
  }catch(InvalidConfigurationException|RuntimeException error){throw new IOException("Не удалось прочитать восстановление редактора регистрации",error);}
 }
 private static ItemStack[] copy(ItemStack[] items){return Arrays.stream(items).map(i->i==null?null:i.clone()).toArray(ItemStack[]::new);}
 private void persist()throws IOException {
  YamlConfiguration data=new YamlConfiguration();for(var entry:states.entrySet()){String b=entry.getKey()+".";State s=entry.getValue();data.set(b+"location",s.location);data.set(b+"mode",s.mode.name());data.set(b+"flight",s.flight);data.set(b+"flying",s.flying);data.set(b+"slot",s.slot);data.set(b+"level",s.level);data.set(b+"exp",s.exp);data.set(b+"items",Arrays.asList(s.items));}
  Files.createDirectories(file.getParent());Path temp=file.resolveSibling(file.getFileName()+".tmp");data.save(temp.toFile());try{Files.move(temp,file,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}catch(AtomicMoveNotSupportedException unsupported){Files.move(temp,file,StandardCopyOption.REPLACE_EXISTING);}
 }
 boolean active(Player p){return editing.contains(p.getUniqueId());}
 boolean canBuild(Player p){return active(p)&&p.isOp()&&!plugin.locked(p)&&p.getGameMode()==GameMode.CREATIVE&&p.getWorld()==lobby.registrationWorld();}
 void command(Player p,String[] args){
  if(plugin.locked(p)){plugin.prompt(p);return;}if(!p.isOp()){tell(p,ChatColor.RED,"Редактирование доступно только оператору.");return;}
  String action=args.length==0?"on":args[0].toLowerCase(Locale.ROOT);if(args.length>1){help(p);return;}
  switch(action){case "on"->start(p);case "save"->{if(!active(p)){tell(p,ChatColor.YELLOW,"Сначала введи /regedit on.");return;}String problem=lobby.roomProblem();if(problem!=null){tell(p,ChatColor.RED,problem);return;}if(save(p,false))tell(p,ChatColor.GREEN,"Комната регистрации сохранена. Можно продолжать строительство.");}case "off"->{if(!active(p)){tell(p,ChatColor.YELLOW,"Ты не в режиме редактирования.");return;}if(save(p,true)&&restore(p,true))tell(p,ChatColor.GREEN,"Комната сохранена. Ты вернулся в хаб, вещи и режим игры восстановлены.");}default->help(p);}
 }
 private void help(Player p){tell(p,ChatColor.AQUA,"/regedit on — строительство • /regedit save — сохранить • /regedit off — сохранить и выйти");}
 private void start(Player p){
  if(active(p)){tell(p,ChatColor.YELLOW,"Редактор уже включён. Сохранить: /regedit save • выйти: /regedit off");return;}
  if(p.getWorld()!=lobby.hubLocation().getWorld()){tell(p,ChatColor.YELLOW,"Сначала перейди в хаб: /hub.");return;}
  if(states.containsKey(p.getUniqueId())&&!restore(p,true))return;
  p.closeInventory();State state=new State(p.getLocation().clone(),p.getGameMode(),p.getAllowFlight(),p.isFlying(),p.getInventory().getHeldItemSlot(),p.getLevel(),p.getExp(),copy(p.getInventory().getContents()));states.put(p.getUniqueId(),state);
  try{persist();}catch(IOException error){states.remove(p.getUniqueId());failure(p,error);return;}
  editing.add(p.getUniqueId());if(!p.teleport(lobby.entranceLocation())){editing.remove(p.getUniqueId());states.remove(p.getUniqueId());try{persist();}catch(IOException error){states.put(p.getUniqueId(),state);failure(p,error);}tell(p,ChatColor.RED,"Не удалось перейти в комнату регистрации.");return;}
  p.getInventory().clear();p.setGameMode(GameMode.CREATIVE);p.setAllowFlight(true);p.setFlying(false);p.setFallDistance(0);lobby.refreshVisibility(p);
  tell(p,ChatColor.AQUA,"Редактор регистрации включён. Строй в креативе; топорик доступен через //wand.");tell(p,ChatColor.YELLOW,"Не перекрывай место появления и три руды. Сохранить: /regedit save • выйти: /regedit off");
 }
 private boolean save(Player p,boolean repair){try{if(repair&&lobby.roomProblem()!=null){lobby.repairServiceArea();tell(p,ChatColor.YELLOW,"Для работы проверки восстановлены руды и свободное место появления.");}lobby.registrationWorld().save();plugin.getConfig().set("lobby.room-created",true);plugin.saveConfig();return true;}catch(RuntimeException error){failure(p,error);return false;}}
 private boolean restore(Player p,boolean consume){State state=states.get(p.getUniqueId());if(state==null)return true;Location current=p.getLocation().clone();if(!p.teleport(state.location)){tell(p,ChatColor.RED,"Не удалось вернуться в хаб. Повтори /regedit off.");return false;}
  if(consume){states.remove(p.getUniqueId());try{persist();}catch(IOException error){states.put(p.getUniqueId(),state);p.teleport(current);failure(p,error);return false;}}
  editing.remove(p.getUniqueId());p.closeInventory();p.getInventory().setContents(copy(state.items));p.getInventory().setHeldItemSlot(state.slot);p.setGameMode(state.mode);p.setAllowFlight(state.flight||state.mode==GameMode.CREATIVE||state.mode==GameMode.SPECTATOR);p.setFlying(state.flying&&p.getAllowFlight());p.setLevel(state.level);p.setExp(state.exp);p.setFallDistance(0);p.updateInventory();lobby.refreshVisibility(p);return true;
 }
 boolean recover(Player p){if(!states.containsKey(p.getUniqueId())||active(p))return false;if(restore(p,true)){tell(p,ChatColor.GREEN,"Вещи и режим игры восстановлены после редактирования регистрации.");return true;}return false;}
 boolean exitToHub(Player p){if(!active(p))return false;if(save(p,true)&&restore(p,true))tell(p,ChatColor.GREEN,"Комната сохранена, режим редактирования выключен.");return true;}
 void leave(Player p){if(active(p))try{save(p,true);restore(p,false);}finally{editing.remove(p.getUniqueId());}}
 void close(){for(UUID id:List.copyOf(editing)){Player p=Bukkit.getPlayer(id);if(p!=null)leave(p);}editing.clear();}
 private void failure(Player p,Exception error){plugin.getLogger().log(Level.SEVERE,"Ошибка сохранения редактора регистрации",error);tell(p,ChatColor.RED,"Не удалось сохранить редактор. Восстановление вещей оставлено на сервере.");}
 private void tell(Player p,ChatColor color,String text){p.sendMessage(ChatColor.DARK_AQUA+"[ᴀɴᴄʜᴏʀ] "+color+text);}
}

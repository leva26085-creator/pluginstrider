package dev.anchordonate;

import java.util.*;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.*;
import org.bukkit.inventory.ItemStack;

final class PremiumDescription {
 private static final TextColor GOLD=TextColor.color(0xFFD166),WHITE=TextColor.color(0xE8F0FA),CYAN=TextColor.color(0x67DCE8);
 private static Component text(String s,TextColor c){return Component.text(s,c).font(Key.key("minecraft:default")).decoration(TextDecoration.ITALIC,false);}
 private static Component bullet(String label,String value){return text("✦ ",GOLD).append(text(label,WHITE)).append(text(value,CYAN));}
 static void apply(ItemStack stack,boolean plus){
  var meta=stack.getItemMeta();List<Component> previous=meta.lore()==null?List.of():meta.lore();List<Component> lines=new ArrayList<>();
  lines.add(Component.text(Character.toString(0xE400+(plus?5:4)),NamedTextColor.WHITE).font(Key.key("anchorshop:badges")).append(text(plus?" ᴘʀᴇᴍɪᴜᴍ+":" ᴘʀᴇᴍɪᴜᴍ",GOLD).decorate(TextDecoration.BOLD)));
  lines.add(text(plus?"Все бонусы ᴘʀᴇᴍɪᴜᴍ и ещё больше возможностей":"Бюджетный набор полезных возможностей",WHITE));lines.add(Component.empty());
  if(!plus){
   lines.add(bullet("Полёт только в /hub — ","/fly"));
   lines.add(bullet("Размер 0.5–1.5 в /hub — ","/scale"));
   lines.add(bullet("5 ᴠɪᴘ-кейсов каждый месяц — ","/premium"));
   lines.add(bullet("Роль в дискорде — ","ᴘʀᴇᴍɪᴜᴍ"));lines.add(text("  Доступ к новым чатам",WHITE));
   lines.add(bullet("Значок рядом с ником — ","ᴘʀᴇᴍɪᴜᴍ"));
   lines.add(bullet("Участие в ивентах — ","приоритет"));
  }else{
   lines.add(bullet("Все команды ᴘʀᴇᴍɪᴜᴍ — ","/fly, /scale, /premium"));
   lines.add(text("  /fly и /scale — только в /hub; размер 0.5–1.5",WHITE));
   lines.add(bullet("Каждый месяц — ","15 ᴠɪᴘ-кейсов"));lines.add(text("  10 за ᴘʀᴇᴍɪᴜᴍ+ и 5 за ᴘʀᴇᴍɪᴜᴍ",WHITE));
   lines.add(bullet("Ивенты — ","гарантированный приоритет"));
   lines.add(bullet("Кэшбэк — ","40 сапфиров"));lines.add(bullet("Собственный титул — ","1 кастомный титул"));
   lines.add(bullet("Роль в дискорде — ","ᴘʀᴇᴍɪᴜᴍ+"));lines.add(text("  Чат с модераторами 1 уровня",WHITE));
  }
  lines.add(text("Роль в дискорде оформляет администрация",TextColor.color(0x95A7BF)));lines.add(Component.empty());lines.addAll(previous);lines.add(text("Покупка за сапфиры · срок суммируется",TextColor.color(0x95A7BF)));meta.lore(lines);stack.setItemMeta(meta);
 }
}

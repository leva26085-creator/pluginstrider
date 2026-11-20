package dev.anchordonate;

import com.google.gson.JsonParser;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.*;

/** Exact native-font advances keep dynamic statistics inside the menu without touching player slots. */
final class MenuGraphics {
 record Line(int x,int y,String text,TextColor color){}
 private final Map<Integer,Integer> widths=new HashMap<>();private int backgroundAdvance;
 MenuGraphics(InputStream input)throws IOException{
  if(input==null)throw new IOException("Missing GUI font metrics");
  try(var reader=new InputStreamReader(input,StandardCharsets.UTF_8)){var data=JsonParser.parseReader(reader).getAsJsonObject();backgroundAdvance=data.get("backgroundAdvance").getAsInt();data.getAsJsonObject("widths").entrySet().forEach(e->widths.put(Integer.parseInt(e.getKey()),e.getValue().getAsInt()));}
 }
 private Component space(int pixels){if(pixels< -512||pixels>512)throw new IllegalArgumentException("GUI text exceeds layout bounds");return Component.text(Character.toString(0xF000+512+pixels)).font(Key.key("anchorshop:layout"));}
 Component title(int page,List<Line> lines){
  Component result=Component.text("\uF801"+Character.toString(0xE500+page),NamedTextColor.WHITE).font(Key.key("anchorshop:ui")).shadowColor(ShadowColor.none());
  if(lines.isEmpty())return result;result=result.append(space(-backgroundAdvance));
  for(Line line:lines){int advance=line.text.codePoints().map(c->widths.getOrDefault(c,6)).sum();if(line.x+advance>168)throw new IllegalArgumentException("GUI label too wide: "+line.text);result=result.append(space(line.x)).append(Component.text(line.text,line.color).font(Key.key("anchorshop:row_"+line.y))).append(space(-line.x-advance));}
  return result;
 }
}

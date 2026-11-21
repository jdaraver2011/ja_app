package com.example.ja;
import java.util.Locale;
import java.util.regex.*;
/** Uses the pinned WeatherStar large-icon mapping; dual forecasts use the later condition. */
public final class JaWeatherIcons {
 private JaWeatherIcons(){}
 public static String file(String url,String description,Boolean daytime){
  String code="";int probability=100;boolean night=daytime!=null&&!daytime;
  Matcher m=Pattern.compile("/icons/[^/]+/(day|night)/([^?]+)",Pattern.CASE_INSENSITIVE).matcher(url==null?"":url);
  if(m.find()){
   if(daytime==null)night=m.group(1).equalsIgnoreCase("night");
   String[] periods=m.group(2).split("/");String[] parts=periods[periods.length-1].split(",");code=parts[0].toLowerCase(Locale.ROOT);
   if(parts.length>1)try{probability=Integer.parseInt(parts[1]);}catch(NumberFormatException ignored){}
  }
  if(code.isEmpty()){
   String text=description==null?"":description.toLowerCase(Locale.ROOT);
   if(text.contains("thunder"))code="tsra_hi";else if(text.contains("freezing"))code="fzra";
   else if(text.contains("snow"))code="snow";else if(text.contains("sleet"))code="sleet";
   else if(text.contains("shower"))code="rain_showers";else if(text.contains("rain"))code="rain";
   else if(text.contains("fog")||text.contains("mist"))code="fog";
   else if(text.contains("mostly cloudy")||text.contains("partly cloudy"))code="bkn";
   else if(text.contains("cloud")||text.contains("overcast"))code="ovc";
   else if(text.contains("clear")||text.contains("sunny")||text.contains("fair"))code="skc";
  }
  String icon;
  switch(code){
   case "skc":case "hot":case "cold":case "haze":icon=night?"Clear":"Sunny";break;
   case "few":case "sct":case "bkn":icon=night?"Mostly-Clear":"Partly-Cloudy";break;
   case "ovc":icon="Cloudy";break;
   case "fog":icon="Fog";break;
   case "smoke":case "dust":icon="Smoke";break;
   case "rain_showers":case "rain_showers_hi":case "rain_showers_high":icon="Shower";break;
   case "rain":icon="Rain";break;
   case "snow":icon=probability>50?"Heavy-Snow":"Light-Snow";break;
   case "rain_sleet":icon="Rain-Sleet";break;
   case "sleet":icon="Sleet";break;
   case "rain_snow":icon="Rain-Snow";break;
   case "snow_fzra":case "winter_mix":icon="Freezing-Rain-Snow";break;
   case "fzra":case "rain_fzra":icon="Freezing-Rain";break;
   case "snow_sleet":icon="Snow-Sleet";break;
   case "tsra":case "tsra_sct":icon=night?"Scattered-Thunderstorms-Night":"Scattered-Thunderstorms-Day";break;
   case "tsra_hi":case "tornado":case "hurricane":case "tropical_storm":icon="Thunderstorm";break;
   case "blizzard":icon="Blowing-Snow";break;
   default:icon=code.startsWith("wind_")?"Windy":"No-Data";
  }
  return icon+".png";
 }
 public static String asset(String url,String description,Boolean daytime){return "file:///android_asset/weatherstar/images/icons/current-conditions/"+file(url,description,daytime);}
}

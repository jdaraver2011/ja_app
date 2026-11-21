package com.example.ja;
public final class JaWeatherIconsTest {
 static void check(String url,String text,Boolean day,String want){String got=JaWeatherIcons.file(url,text,day);if(!got.equals(want))throw new AssertionError(got+" != "+want);}
 public static void main(String[]args){
  check("https://api.weather.gov/icons/land/day/skc?size=medium","",null,"Sunny.png");
  check("https://api.weather.gov/icons/land/night/skc","",null,"Clear.png");
  check("https://api.weather.gov/icons/land/day/bkn","",false,"Mostly-Clear.png");
  check("https://api.weather.gov/icons/land/day/rain,30/snow,60","",true,"Heavy-Snow.png");
  check("https://api.weather.gov/icons/land/day/tsra_sct,40","",true,"Scattered-Thunderstorms-Day.png");
  check(null,"Cloudy",null,"Cloudy.png");check(null,"Light Rain",null,"Rain.png");
  check("garbage","",null,"No-Data.png");check("https://api.weather.gov/icons/land/day/wind_ovc","",null,"Windy.png");
  System.out.println("WeatherStar day/night, dual-condition, precipitation and fallback icon checks passed");
 }
}

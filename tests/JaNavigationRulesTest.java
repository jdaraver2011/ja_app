package com.example.ja;
public final class JaNavigationRulesTest {
 public static void main(String[] args){
  if(JaNavigationRules.backTarget(2)!=1||JaNavigationRules.backTarget(1)!=0||JaNavigationRules.backTarget(0)!=0)throw new AssertionError("Back must advance a single level");
  System.out.println("Content -> sub row -> main row navigation checks passed");
 }
}

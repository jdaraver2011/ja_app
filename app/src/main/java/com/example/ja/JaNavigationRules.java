package com.example.ja;
/** Back advances one navigation level; only Back from the main row reaches the exit gate. */
public final class JaNavigationRules {
    private JaNavigationRules(){}
    public static int backTarget(int level){return level>=2?1:0;}
}

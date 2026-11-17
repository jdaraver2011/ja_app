package com.example.ja;

public final class JaMusicRulesTest {
    private static void check(boolean value) { if (!value) throw new AssertionError(); }
    public static void main(String[] args) {
        check(JaMusicRules.allowed("https://www.deezer.com/en/"));
        check(JaMusicRules.allowed("https://account.deezer.com/en-us/login/?redirect=abc"));
        check(JaMusicRules.allowed("https://accounts.google.com/o/oauth2/auth"));
        // Music redirects may cross HTTPS hosts; this is not a source-provider allowlist.
        check(JaMusicRules.allowed("https://login.example.org/callback?code=example"));
        check(JaMusicRules.allowed("https://music.example.org:443/player"));
        check(!JaMusicRules.allowed("https://deezer.com@attacker.example/"));
        check(!JaMusicRules.allowed("https://attacker@deezer.com/"));
        check(!JaMusicRules.allowed("http://deezer.com/"));
        check(!JaMusicRules.allowed("javascript:alert(1)"));
        check(!JaMusicRules.allowed("intent://deezer.com/"));
        check(!JaMusicRules.allowed("file:///data/local/page.html"));
        check(!JaMusicRules.allowed("data:text/html,example"));
        check(!JaMusicRules.allowed("//www.deezer.com/en/"));
        check(!JaMusicRules.allowed("https:///missing-host"));
        check(!JaMusicRules.allowed(null));
        check(!JaMusicRules.allowed("https://www.deezer.com:8080/"));
        check(JaMusicRules.mayPlay(false, true));
        check(!JaMusicRules.mayPlay(true, true));
        check(!JaMusicRules.mayPlay(false, false));
        check(!JaMusicRules.mayPlay(true, false));
        System.out.println("Music navigation and audio ownership tests passed");
    }
}

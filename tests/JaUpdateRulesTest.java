package com.example.ja;
import java.util.Set;
public final class JaUpdateRulesTest {
    static void check(boolean value) { if(!value)throw new AssertionError(); }
    public static void main(String[] args) {
        check(JaUpdateRules.versionMatches("com.example.ja","com.example.ja",20008,20009,20009));
        check(!JaUpdateRules.versionMatches("com.example.ja","other.app",20008,20009,20009));
        check(!JaUpdateRules.versionMatches("com.example.ja","com.example.ja",20008,20008,20008));
        check(!JaUpdateRules.versionMatches("com.example.ja","com.example.ja",20008,20007,20007));
        check(!JaUpdateRules.versionMatches("com.example.ja","com.example.ja",20008,20009,20010));
        check(JaUpdateRules.signersMatch(Set.of("a"),Set.of("a")));
        check(!JaUpdateRules.signersMatch(Set.of(),Set.of()));
        check(!JaUpdateRules.signersMatch(Set.of("a"),Set.of("b")));
        check(!JaUpdateRules.signersMatch(Set.of("a","b"),Set.of("a")));
        String hash="a".repeat(64);
        check(JaUpdateRules.downloadMatches(hash,hash,10,10));
        check(!JaUpdateRules.downloadMatches(hash,"b".repeat(64),10,10));
        check(!JaUpdateRules.downloadMatches(hash,hash,10,9));
        check(!JaUpdateRules.downloadMatches("invalid","invalid",10,10));
        check(!JaUpdateRules.downloadMatches(hash,hash,0,0));
        System.out.println("PASS: package/version, newer-only, metadata mismatch, signer mismatch and corrupt/truncated download rejection");
    }
}

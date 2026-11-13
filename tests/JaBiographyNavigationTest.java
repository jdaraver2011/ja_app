package com.example.ja;
import static com.example.ja.JaBiographyNavigation.*;
public final class JaBiographyNavigationTest {
    static void expect(Action expected, boolean reading, Key key, boolean canScroll) {
        if (action(reading,key,canScroll) != expected) throw new AssertionError(key + " reading=" + reading);
    }
    public static void main(String[] args) {
        expect(Action.NEXT,false,Key.DOWN,true); // a long bio can be bypassed immediately
        expect(Action.NEXT,false,Key.DOWN,false); // short/empty bio also passes to content
        expect(Action.PREVIOUS,false,Key.UP,true);
        expect(Action.BEGIN_READING,false,Key.SELECT,false);
        expect(Action.SCROLL_DOWN,true,Key.DOWN,true);
        expect(Action.SCROLL_UP,true,Key.UP,true);
        expect(Action.STAY,true,Key.UP,false);
        expect(Action.NEXT,true,Key.DOWN,false); // bottom never traps the viewer
        expect(Action.END_READING,true,Key.BACK,true); // Back exits reading, not the person screen
        expect(Action.END_READING,true,Key.SELECT,false);
        expect(Action.PROPAGATE,false,Key.BACK,false);
        System.out.println("PASS: opt-in reading, long/short bio bypass, scroll limits, bottom escape and Back behavior");
    }
}

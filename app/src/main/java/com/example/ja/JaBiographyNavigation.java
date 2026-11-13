package com.example.ja;

/** Reading is opt-in; navigation never requires scrolling through a biography. */
public final class JaBiographyNavigation {
    public enum Key { UP, DOWN, SELECT, BACK }
    public enum Action { BEGIN_READING, END_READING, SCROLL_UP, SCROLL_DOWN, PREVIOUS, NEXT, STAY, PROPAGATE }
    public static Action action(boolean reading, Key key, boolean canScroll) {
        switch (key) {
            case SELECT: return reading ? Action.END_READING : Action.BEGIN_READING;
            case BACK: return reading ? Action.END_READING : Action.PROPAGATE;
            case UP: return reading ? (canScroll ? Action.SCROLL_UP : Action.STAY) : Action.PREVIOUS;
            case DOWN: return reading ? (canScroll ? Action.SCROLL_DOWN : Action.STAY) : Action.NEXT;
            default: return Action.PROPAGATE;
        }
    }
}

package com.example.ja;

/** Page URLs supplied by the user; construction does not verify a playable stream. */
public final class JaAdditionalTmdbUrls {
    private JaAdditionalTmdbUrls() {}
    private static boolean valid(String type, int id, int season, int episode) {
        return id > 0 && ("movie".equals(type)
                || ("tv".equals(type) && season >= 0 && episode > 0));
    }
    public static String movy(String type, int id, int season, int episode) {
        if (!valid(type, id, season, episode)) return null;
        return "movie".equals(type)
                ? "https://www.movy.sx/movie/" + id
                : "https://www.movy.sx/tv/" + id + "/" + season + "/" + episode;
    }
    public static String sevenMovies(String type, int id, int season, int episode) {
        if (!valid(type, id, season, episode)) return null;
        return "movie".equals(type)
                ? "https://7movies.ac/movie/" + id + "/watch"
                : "https://7movies.ac/tv/" + id + "/watch?season=" + season + "&episode=" + episode;
    }
}

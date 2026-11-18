package com.example.ja;

import java.net.URI;

public final class JaAdditionalTmdbUrlsTest {
    private static void check(boolean value) { if (!value) throw new AssertionError(); }
    public static void main(String[] args) {
        check("https://www.movy.sx/movie/687163".equals(JaAdditionalTmdbUrls.movy("movie", 687163, 0, 0)));
        check("https://www.movy.sx/tv/254630/1/2".equals(JaAdditionalTmdbUrls.movy("tv", 254630, 1, 2)));
        check("https://www.movy.sx/tv/254630/1/1".equals(JaAdditionalTmdbUrls.movy("tv", 254630, 1, 1)));
        check("https://7movies.ac/movie/687163/watch".equals(JaAdditionalTmdbUrls.sevenMovies("movie", 687163, 0, 0)));
        String episode = JaAdditionalTmdbUrls.sevenMovies("tv", 291507, 1, 1);
        check("https://7movies.ac/tv/291507/watch?season=1&episode=1".equals(episode));
        check("https://7movies.ac/tv/291507/watch?season=17&episode=12".equals(
                JaAdditionalTmdbUrls.sevenMovies("tv", 291507, 17, 12)));
        check(URI.create(episode).getHost().equals("7movies.ac"));
        check(URI.create(episode).getRawQuery().equals("season=1&episode=1"));
        check(JaAdditionalTmdbUrls.movy("tv", 1399, 0, 1).endsWith("/0/1"));
        for (String type : new String[]{null, "person", "TV", "movie/../tv"}) {
            check(JaAdditionalTmdbUrls.movy(type, 1399, 1, 1) == null);
            check(JaAdditionalTmdbUrls.sevenMovies(type, 1399, 1, 1) == null);
        }
        check(JaAdditionalTmdbUrls.movy("movie", 0, 0, 0) == null);
        check(JaAdditionalTmdbUrls.sevenMovies("movie", -1, 0, 0) == null);
        check(JaAdditionalTmdbUrls.movy("tv", 1399, -1, 1) == null);
        check(JaAdditionalTmdbUrls.sevenMovies("tv", 1399, 1, 0) == null);
        System.out.println("Additional TMDB source URL tests passed");
    }
}

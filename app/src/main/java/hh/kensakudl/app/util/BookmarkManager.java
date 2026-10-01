package hh.kensakudl.app.util;

import android.content.Context;
import android.content.SharedPreferences;
import com.google.gson.Gson;
import hh.kensakudl.app.model.AnimeItem;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
/**
 * Persists bookmarked anime in application-private SharedPreferences using Gson.
 *
 * <p>The manager is a process-local singleton and synchronizes its public operations so
 * callers cannot observe partially updated bookmark state.</p>
 */

public class BookmarkManager {
    private static final String PREF_NAME = "kensaku_bookmarks";
    private static final String KEY_ITEMS = "bookmarked_anime";
    private static BookmarkManager instance;
    private final SharedPreferences prefs;
    private final Gson gson;

    private BookmarkManager(Context context) {
        prefs = context.getApplicationContext().getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        gson = new Gson();
    }
/**
 * Returns the process-wide bookmark manager instance.
 *
 * @param context context used to obtain application-scoped preferences
 * @return shared bookmark manager
 */

    public static synchronized BookmarkManager getInstance(Context context) {
        if (instance == null) {
            instance = new BookmarkManager(context);
        }
        return instance;
    }
/**
 * Loads the current bookmark collection from persistent storage.
 *
 * @return bookmarked anime, or an empty list when none are stored
 */

    public synchronized List<AnimeItem> getBookmarks() {
        String json = prefs.getString(KEY_ITEMS, null);
        if (json == null) return new ArrayList<>();
        
        AnimeItem[] items = gson.fromJson(json, AnimeItem[].class);
        if (items == null) return new ArrayList<>();
        return new ArrayList<>(Arrays.asList(items));
    }
/**
 * Adds an anime to the bookmarks or removes it when already present.
 *
 * @param anime anime to toggle
 */

    public synchronized void toggleBookmark(AnimeItem anime) {
        List<AnimeItem> items = getBookmarks();
        int existingIndex = -1;
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).getAnilist().equals(anime.getAnilist())) {
                existingIndex = i;
                break;
            }
        }
        if (existingIndex >= 0) {
            items.remove(existingIndex);
        } else {
            items.add(0, anime);
        }
        prefs.edit().putString(KEY_ITEMS, gson.toJson(items)).apply();
    }
/**
 * Checks whether an AniList identifier is currently bookmarked.
 *
 * @param anilistId AniList identifier to look up
 * @return {@code true} when a matching bookmark exists
 */

    public synchronized boolean isBookmarked(String anilistId) {
        List<AnimeItem> items = getBookmarks();
        for (AnimeItem it : items) {
            if (it.getAnilist().equals(anilistId)) return true;
        }
        return false;
    }
}
package hh.kensakudl.app.util;

import android.content.Context;
import android.content.SharedPreferences;
import com.google.gson.Gson;
import hh.kensakudl.app.model.AnimeItem;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Persists bookmarked anime as JSON in private SharedPreferences and exposes synchronized operations for toggling and querying saved titles.
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
     * Returns the singleton bookmark store, using application-scoped preferences.
     * 
     * @param context context used to access private preferences
     * @return shared bookmark manager
     */
    public static synchronized BookmarkManager getInstance(Context context) {
        if (instance == null) {
            instance = new BookmarkManager(context);
        }
        return instance;
    }

    /**
     * Deserializes the stored bookmark array into a mutable list. Missing or empty storage produces an empty list.
     * 
     * @return saved anime in stored order
     */
    public synchronized List<AnimeItem> getBookmarks() {
        String json = prefs.getString(KEY_ITEMS, null);
        if (json == null) return new ArrayList<>();
        // R8-Safe: Uses concrete class array instead of anonymous TypeToken
        AnimeItem[] items = gson.fromJson(json, AnimeItem[].class);
        if (items == null) return new ArrayList<>();
        return new ArrayList<>(Arrays.asList(items));
    }

    /**
     * Adds the anime to the front of the saved list, or removes its existing entry when already bookmarked.
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
     * Checks the saved list using the AniList identifier as the bookmark key.
     * 
     * @param anilistId AniList identifier to look up
     * @return {@code true} when the identifier is saved
     */
    public synchronized boolean isBookmarked(String anilistId) {
        List<AnimeItem> items = getBookmarks();
        for (AnimeItem it : items) {
            if (it.getAnilist().equals(anilistId)) return true;
        }
        return false;
    }
}
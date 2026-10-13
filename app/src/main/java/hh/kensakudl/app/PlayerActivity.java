package hh.kensakudl.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.media.AudioManager;
import android.media.audiofx.LoudnessEnhancer;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.annotation.OptIn;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.ui.AspectRatioFrameLayout;
import androidx.media3.ui.PlayerView;
import java.io.File;
import java.util.Collections;

/**
 * Plays either a local episode file or a resolved HLS stream. Playback state, gesture-driven controls, audio enhancements, and the last playback position are managed here.
 */
@OptIn(markerClass = UnstableApi.class)
public class PlayerActivity extends AppCompatActivity {
    public static final String EXTRA_VIDEO_PATH = "extra_video_path";
    public static final String EXTRA_ANIME_TITLE = "extra_anime_title";
    public static final String EXTRA_EPISODE_NAME = "extra_episode_name";
    public static final String EXTRA_STREAM_URL = "extra_stream_url";
    public static final String EXTRA_STREAM_REFERER = "extra_stream_referer";
    public static final String EXTRA_STREAM_ORIGIN = "extra_stream_origin";
    public static final String EXTRA_STREAM_SUBTITLE = "extra_stream_subtitle";
    private static final String PREFS_NAME = "kensaku_playback_positions";

    private ExoPlayer player;
    private PlayerView playerView;
    private String videoPath;
    private String animeTitle;
    private String episodeName;
    private String streamUrl;
    private String streamReferer;
    private String streamOrigin;
    private String streamSubtitle;

    private int currentResizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT;

    private AudioManager audioManager;
    private LoudnessEnhancer loudnessEnhancer;
    private GestureDetector gestureDetector;
    private final Handler hudHandler = new Handler(Looper.getMainLooper());

    private View viewExtraDim;
    private LinearLayout hudBrightness;
    private ImageView ivBrightnessIcon;
    private ProgressBar pbBrightness;
    private TextView tvBrightnessValue;

    private LinearLayout hudVolume;
    private ImageView ivVolumeIcon;
    private ProgressBar pbVolume;
    private TextView tvVolumeValue;

    private int screenWidth = 0;
    private int screenHeight = 0;
    private int maxStreamVolume = 15;

    private boolean isDragging = false;
    private boolean isLeftDrag = false;
    private float initialBrightness = 0.5f;
    private int initialVolume = 0;
    private int currentVolumePercent = 100;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_player);

        hideSystemBars();

        videoPath = getIntent().getStringExtra(EXTRA_VIDEO_PATH);
        streamUrl = getIntent().getStringExtra(EXTRA_STREAM_URL);
        streamReferer = getIntent().getStringExtra(EXTRA_STREAM_REFERER);
        streamOrigin = getIntent().getStringExtra(EXTRA_STREAM_ORIGIN);
        streamSubtitle = getIntent().getStringExtra(EXTRA_STREAM_SUBTITLE);
        animeTitle = getIntent().getStringExtra(EXTRA_ANIME_TITLE);
        episodeName = getIntent().getStringExtra(EXTRA_EPISODE_NAME);

        boolean streaming = streamUrl != null && !streamUrl.trim().isEmpty();
        if (!streaming && (videoPath == null || !new File(videoPath).exists())) {
            Toast.makeText(this, "Video file not found", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        playerView = findViewById(R.id.playerView);
        initViews();
        setupGestureDetector();
        setupHeaderOverlay();
        initializePlayer();
    }

    private void initViews() {
        viewExtraDim = findViewById(R.id.viewExtraDim);
        hudBrightness = findViewById(R.id.hudBrightness);
        ivBrightnessIcon = findViewById(R.id.ivBrightnessIcon);
        pbBrightness = findViewById(R.id.pbBrightness);
        tvBrightnessValue = findViewById(R.id.tvBrightnessValue);

        hudVolume = findViewById(R.id.hudVolume);
        ivVolumeIcon = findViewById(R.id.ivVolumeIcon);
        pbVolume = findViewById(R.id.pbVolume);
        tvVolumeValue = findViewById(R.id.tvVolumeValue);

        DisplayMetrics dm = getResources().getDisplayMetrics();
        screenWidth = dm.widthPixels;
        screenHeight = dm.heightPixels;

        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        if (audioManager != null) {
            maxStreamVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            int currentStreamVol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
            currentVolumePercent = (int) ((currentStreamVol / (float) maxStreamVolume) * 100);
        }
    }

    private void setupGestureDetector() {
        gestureDetector = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            /**
             * Records the start of a touch gesture so subsequent movement can be interpreted by the player controls.
             * 
             * @param e initial touch event
             * @return whether the gesture detector consumed the event
             */
            @Override
            public boolean onDown(@NonNull MotionEvent e) {
                isDragging = false;
                isLeftDrag = (e.getX() < (screenWidth / 2f));

                if (isLeftDrag) {
                    WindowManager.LayoutParams lp = getWindow().getAttributes();
                    initialBrightness = (lp.screenBrightness < 0) ? 0.5f : lp.screenBrightness;
                } else {
                    initialVolume = currentVolumePercent;
                }
                return true;
            }

            /**
             * Maps touch movement to the player's gesture controls, such as seeking or adjusting playback settings.
             * 
             * @param e1 initial touch event
             * @param e2 current touch event
             * @param distanceX horizontal movement reported by the gesture detector
             * @param distanceY vertical movement reported by the gesture detector
             * @return whether the gesture was handled
             */
            @Override
            public boolean onScroll(MotionEvent e1, @NonNull MotionEvent e2, float distanceX, float distanceY) {
                if (e1 == null) return false;

                float deltaY = (e1.getY() - e2.getY()) / (float) screenHeight;
                if (!isDragging && Math.abs(e2.getY() - e1.getY()) > 20 && Math.abs(distanceY) > Math.abs(distanceX)) {
                    isDragging = true;
                }

                if (isDragging) {
                    if (isLeftDrag) {
                        adjustBrightness(deltaY);
                    } else {
                        adjustVolume(deltaY);
                    }
                    return true;
                }
                return false;
            }
        });
    }

    private void adjustBrightness(float deltaY) {
        hudHandler.removeCallbacksAndMessages(null);
        hudBrightness.setVisibility(View.VISIBLE);
        hudVolume.setVisibility(View.GONE);

        float newBrightness = initialBrightness + deltaY * 1.5f;

        if (newBrightness < 0f) {
            float extraDimAlpha = Math.min(0.65f, Math.abs(newBrightness) * 1.3f);
            viewExtraDim.setAlpha(extraDimAlpha);

            WindowManager.LayoutParams lp = getWindow().getAttributes();
            lp.screenBrightness = 0.01f;
            getWindow().setAttributes(lp);

            ivBrightnessIcon.setImageResource(R.drawable.ic_brightness_low);
            pbBrightness.setProgress(0);
            tvBrightnessValue.setText("Extra Dim");
        } else {
            viewExtraDim.setAlpha(0f);
            newBrightness = Math.min(1.0f, newBrightness);

            WindowManager.LayoutParams lp = getWindow().getAttributes();
            lp.screenBrightness = newBrightness;
            getWindow().setAttributes(lp);

            int pct = (int) (newBrightness * 100);
            ivBrightnessIcon.setImageResource(R.drawable.ic_brightness_high);
            pbBrightness.setProgress(pct);
            tvBrightnessValue.setText(pct + "%");
        }
    }

    private void adjustVolume(float deltaY) {
        hudHandler.removeCallbacksAndMessages(null);
        hudVolume.setVisibility(View.VISIBLE);
        hudBrightness.setVisibility(View.GONE);

        int newVolume = (int) (initialVolume + deltaY * 200f);
        newVolume = Math.max(0, Math.min(200, newVolume));
        currentVolumePercent = newVolume;

        if (newVolume <= 100) {
            if (loudnessEnhancer != null) {
                try { loudnessEnhancer.setEnabled(false); } catch (Exception ignored) {}
            }
            int streamVol = (int) ((newVolume / 100f) * maxStreamVolume);
            if (audioManager != null) {
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, streamVol, 0);
            }

            ivVolumeIcon.setImageResource(newVolume == 0 ? R.drawable.ic_volume_mute : R.drawable.ic_volume_up);
            pbVolume.setProgressTintList(ColorStateList.valueOf(Color.parseColor("#A4384F")));
            pbVolume.setProgress(newVolume);
            tvVolumeValue.setText(newVolume + "%");
        } else {
            if (audioManager != null) {
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, maxStreamVolume, 0);
            }
            if (loudnessEnhancer != null) {
                try {
                    int gainMilliBels = (int) (((newVolume - 100) / 100f) * 1500);
                    loudnessEnhancer.setTargetGain(gainMilliBels);
                    loudnessEnhancer.setEnabled(true);
                } catch (Exception ignored) {}
            }

            ivVolumeIcon.setImageResource(R.drawable.ic_volume_up);
            pbVolume.setProgressTintList(ColorStateList.valueOf(Color.parseColor("#B36B73")));
            pbVolume.setProgress(newVolume);
            tvVolumeValue.setText(newVolume + "% (Boost)");
        }
    }

    /**
     * Routes touch events through the gesture detector before allowing the activity hierarchy to process them.
     * 
     * @param ev event dispatched by Android
     * @return whether the event was consumed
     */
    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        if (gestureDetector != null) {
            gestureDetector.onTouchEvent(ev);
        }

        if (ev.getAction() == MotionEvent.ACTION_UP || ev.getAction() == MotionEvent.ACTION_CANCEL) {
            if (isDragging) {
                isDragging = false;
                scheduleHideHud();
                return true;
            }
        }
        return isDragging || super.dispatchTouchEvent(ev);
    }

    private void scheduleHideHud() {
        hudHandler.removeCallbacksAndMessages(null);
        hudHandler.postDelayed(() -> {
            hudBrightness.setVisibility(View.GONE);
            hudVolume.setVisibility(View.GONE);
        }, 1200);
    }

    private void initLoudnessEnhancer(int audioSessionId) {
        if (audioSessionId != C.AUDIO_SESSION_ID_UNSET && audioSessionId != 0) {
            try {
                if (loudnessEnhancer != null) {
                    loudnessEnhancer.release();
                }
                loudnessEnhancer = new LoudnessEnhancer(audioSessionId);
            } catch (Exception e) {
                Log.w("PlayerActivity", "LoudnessEnhancer initialization failed: " + e.getMessage());
            }
        }
    }

    private void setupHeaderOverlay() {
        TextView tvTitle = playerView.findViewById(R.id.tvPlayerAnimeTitle);
        TextView tvEpisode = playerView.findViewById(R.id.tvPlayerEpisodeName);
        ImageButton btnBack = playerView.findViewById(R.id.btnPlayerBack);
        ImageButton btnAspect = playerView.findViewById(R.id.btnAspectRatio);

        if (tvTitle != null) tvTitle.setText(animeTitle != null ? animeTitle : "KensakuDL");
        if (tvEpisode != null) tvEpisode.setText(episodeName != null ? "Episode " + episodeName : "");
        if (btnBack != null) btnBack.setOnClickListener(v -> finish());

        if (btnAspect != null) {
            btnAspect.setOnClickListener(v -> toggleAspectRatio());
        }
    }

    private void toggleAspectRatio() {
        if (currentResizeMode == AspectRatioFrameLayout.RESIZE_MODE_FIT) {
            currentResizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM;
            Toast.makeText(this, "Zoom (Crop to Fill)", Toast.LENGTH_SHORT).show();
        } else if (currentResizeMode == AspectRatioFrameLayout.RESIZE_MODE_ZOOM) {
            currentResizeMode = AspectRatioFrameLayout.RESIZE_MODE_FILL;
            Toast.makeText(this, "Stretch to Fill", Toast.LENGTH_SHORT).show();
        } else {
            currentResizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT;
            Toast.makeText(this, "Original (Fit)", Toast.LENGTH_SHORT).show();
        }
        playerView.setResizeMode(currentResizeMode);
    }

    private void initializePlayer() {
        player = new ExoPlayer.Builder(this)
                .setSeekBackIncrementMs(10000)
                .setSeekForwardIncrementMs(10000)
                .build();

        // Enable default English subtitles
        player.setTrackSelectionParameters(
                player.getTrackSelectionParameters()
                        .buildUpon()
                        .setPreferredTextLanguage("en")
                        .setSelectUndeterminedTextLanguage(true)
                        .build()
        );

        playerView.setPlayer(player);

        player.addListener(new Player.Listener() {
            /**
             * Attaches audio processing to the active playback session when the player reports a new audio session.
             * 
             * @param audioSessionId platform audio session identifier
             */
            @Override
            public void onAudioSessionIdChanged(int audioSessionId) {
                initLoudnessEnhancer(audioSessionId);
            }

            /**
             * Handles playback failures and updates the player UI or recovery behavior.
             * 
             * @param error playback failure reported by Media3
             */
            @Override
            public void onPlayerError(androidx.media3.common.PlaybackException error) {
                Log.e("KensakuStreaming", "Playback failed for URL: " + streamUrl, error);
                String detail = error.getErrorCodeName();
                if (error.getCause() != null && error.getCause().getMessage() != null) {
                    detail += ": " + error.getCause().getMessage();
                }
                Toast.makeText(PlayerActivity.this, "Playback failed (" + detail + ")", Toast.LENGTH_LONG).show();
            }
        });

        boolean streaming = streamUrl != null && !streamUrl.trim().isEmpty();
        MediaItem.Builder mediaItemBuilder = new MediaItem.Builder()
                .setUri(streaming ? Uri.parse(streamUrl) : Uri.fromFile(new File(videoPath)));
        // Many providers expose HLS playlists at tokenized URLs without a .m3u8 suffix.
        // Force HLS explicitly; otherwise Media3 may incorrectly treat them as progressive files.
        if (streaming) {
            mediaItemBuilder.setMimeType(MimeTypes.APPLICATION_M3U8);
        }

        if (streaming && streamSubtitle != null && !streamSubtitle.trim().isEmpty()) {
            MediaItem.SubtitleConfiguration subConfig = new MediaItem.SubtitleConfiguration.Builder(Uri.parse(streamSubtitle))
                    .setMimeType(MimeTypes.TEXT_VTT).setLanguage("en")
                    .setSelectionFlags(C.SELECTION_FLAG_DEFAULT).build();
            mediaItemBuilder.setSubtitleConfigurations(Collections.singletonList(subConfig));
        } else if (!streaming) {
            File vttFile = new File(videoPath.replace(".mp4", ".vtt"));
            if (vttFile.exists()) {
                MediaItem.SubtitleConfiguration subConfig = new MediaItem.SubtitleConfiguration.Builder(Uri.fromFile(vttFile))
                        .setMimeType(MimeTypes.TEXT_VTT).setLanguage("en")
                        .setSelectionFlags(C.SELECTION_FLAG_DEFAULT).build();
                mediaItemBuilder.setSubtitleConfigurations(Collections.singletonList(subConfig));
            }
        }

        if (streaming) {
            java.util.Map<String, String> headers = new java.util.HashMap<>();
            headers.put("User-Agent", hh.kensakudl.app.network.ApiClient.UA);
            if (streamReferer != null && !streamReferer.isEmpty()) headers.put("Referer", streamReferer);
            if (streamOrigin != null && !streamOrigin.isEmpty()) headers.put("Origin", streamOrigin);
            DefaultHttpDataSource.Factory httpFactory = new DefaultHttpDataSource.Factory()
                    .setUserAgent(hh.kensakudl.app.network.ApiClient.UA)
                    .setAllowCrossProtocolRedirects(true)
                    .setDefaultRequestProperties(headers);
            DefaultDataSource.Factory dataSourceFactory = new DefaultDataSource.Factory(this, httpFactory);
            player.setMediaSource(new DefaultMediaSourceFactory(dataSourceFactory).createMediaSource(mediaItemBuilder.build()));
        } else {
            player.setMediaItem(mediaItemBuilder.build());
        }

        long savedPosition = getSavedPosition();
        if (savedPosition > 2000) {
            player.seekTo(savedPosition);
        }

        player.prepare();
        player.setPlayWhenReady(true);
    }

    private void hideSystemBars() {
        WindowInsetsControllerCompat controller = WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        controller.setSystemBarsBehavior(WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        controller.hide(WindowInsetsCompat.Type.systemBars());
    }

    private String getPrefKey() {
        return (animeTitle != null ? animeTitle : "") + "::" + (episodeName != null ? episodeName : "");
    }

    private long getSavedPosition() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        return prefs.getLong(getPrefKey(), 0);
    }

    private void savePosition() {
        if (player != null && player.getCurrentPosition() > 0) {
            long pos = player.getCurrentPosition();
            long dur = player.getDuration();
            if (dur > 0 && pos >= dur - 15000) {
                pos = 0;
            }
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                    .edit()
                    .putLong(getPrefKey(), pos)
                    .apply();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        savePosition();
        if (player != null) {
            player.pause();
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        savePosition();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        savePosition();
        if (loudnessEnhancer != null) {
            try {
                loudnessEnhancer.setEnabled(false);
                loudnessEnhancer.release();
            } catch (Exception ignored) {}
            loudnessEnhancer = null;
        }
        if (player != null) {
            player.release();
            player = null;
        }
    }
}
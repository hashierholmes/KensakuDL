package hh.kensakudl.app;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.fragment.app.Fragment;
import hh.kensakudl.app.util.UpdateChecker;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentTransaction;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import hh.kensakudl.app.databinding.ActivityMainBinding;
import hh.kensakudl.app.model.AnimeItem;
import hh.kensakudl.app.ui.MoreFragment;
import hh.kensakudl.app.ui.QueueFragment;
import hh.kensakudl.app.ui.SearchFragment;
import hh.kensakudl.app.ui.SeriesFragment;

/**
 * Hosts the application shell, bottom navigation, and permission-gated actions. It retains the primary fragments and coordinates transitions into series details and the download queue.
 */
public class MainActivity extends AppCompatActivity {
    private static final int REQUEST_NOTIFICATION_PERMISSION = 101;
    private static final int REQUEST_VIDEO_ACCESS = 102;
    private static final String PREFS_NAME = "kensaku_permissions";
    private static final String PREF_VIDEO_INTRO_SHOWN = "video_access_intro_shown";

    private ActivityMainBinding binding;
    private Runnable pendingNotificationAction;
    private Runnable pendingVideoAction;

    private SearchFragment homeFragment;
    private QueueFragment downloadFragment;
    private MoreFragment moreFragment;
    private Fragment activeFragment;

    @Override
    @SuppressWarnings("deprecation")
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Transition window background from splash to solid dark surface
        getWindow().setBackgroundDrawableResource(R.color.bg_surface);

        // Pure Black Status Bar
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            int surfaceColor = ContextCompat.getColor(this, R.color.bg_surface);
            int HeaderColor = ContextCompat.getColor(this, R.color.header_bg);

            getWindow().setStatusBarColor(surfaceColor);
            getWindow().setNavigationBarColor(HeaderColor);

            WindowCompat.setDecorFitsSystemWindows(getWindow(), true);
            WindowInsetsControllerCompat insetsController =
                    WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
            insetsController.setAppearanceLightStatusBars(false);
            insetsController.setAppearanceLightNavigationBars(false);
        }

        if (getSupportActionBar() != null) {
            getSupportActionBar().hide();
        }

        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        // Preserved tab state: fragments are created once and shown/hidden
        if (savedInstanceState == null) {
            homeFragment = new SearchFragment();
            downloadFragment = new QueueFragment();
            moreFragment = new MoreFragment();

            getSupportFragmentManager().beginTransaction()
                    .add(R.id.fragmentContainer, moreFragment, "MORE").hide(moreFragment)
                    .add(R.id.fragmentContainer, downloadFragment, "DOWNLOAD").hide(downloadFragment)
                    .add(R.id.fragmentContainer, homeFragment, "HOME")
                    .commit();
            activeFragment = homeFragment;
        } else {
            homeFragment = (SearchFragment) getSupportFragmentManager().findFragmentByTag("HOME");
            downloadFragment = (QueueFragment) getSupportFragmentManager().findFragmentByTag("DOWNLOAD");
            moreFragment = (MoreFragment) getSupportFragmentManager().findFragmentByTag("MORE");
            activeFragment = homeFragment;
        }

        binding.bottomNavigation.setOnItemSelectedListener(item -> {
            int id = item.getItemId();
            if (id == R.id.nav_home) {
                switchTab(homeFragment);
                return true;
            } else if (id == R.id.nav_download) {
                switchTab(downloadFragment);
                return true;
            } else if (id == R.id.nav_more) {
                switchTab(moreFragment);
                return true;
            }
            return false;
        });

        binding.getRoot().post(() -> {
            showVideoAccessIntroIfNeeded();
            UpdateChecker.check(this);
        });
    }

    private void switchTab(Fragment target) {
        if (target == null) return;

        FragmentTransaction ft = getSupportFragmentManager().beginTransaction();

        if (homeFragment != null) ft.hide(homeFragment);
        if (downloadFragment != null) ft.hide(downloadFragment);
        if (moreFragment != null) ft.hide(moreFragment);

        // A Series screen is a detail overlay on Home, not a separate tab.
        // Always reveal the requested tab; never re-show a stale Series fragment.
        Fragment series = getSupportFragmentManager().findFragmentByTag("SERIES");
        if (series != null && series.isAdded()) {
            ft.hide(series);
        }

        ft.show(target).commit();
        activeFragment = target;
    }

    /**
     * Opens the detail screen for the selected anime and makes its fragment the active destination.
     */
    public void openSeries(AnimeItem anime) {
        SeriesFragment seriesFragment = SeriesFragment.newInstance(anime);
        getSupportFragmentManager().beginTransaction()
                .setCustomAnimations(
                        android.R.anim.fade_in,
                        android.R.anim.fade_out,
                        android.R.anim.fade_in,
                        android.R.anim.fade_out
                )
                .add(R.id.fragmentContainer, seriesFragment, "SERIES")
                .hide(homeFragment)
                .addToBackStack("SERIES_STACK")
                .commit();
        activeFragment = seriesFragment;
    }

    /**
     * Returns to the home/search destination through the app's fragment navigation flow.
     */
    public void navigateToSearch() {
        // Pop synchronously before switching tabs. An asynchronous pop followed by
        // hide/show transactions can briefly leave the container with no visible view.
        FragmentManager fm = getSupportFragmentManager();
        if (fm.getBackStackEntryCount() > 0) {
            fm.popBackStackImmediate("SERIES_STACK", FragmentManager.POP_BACK_STACK_INCLUSIVE);
        }
        switchTab(homeFragment);
        if (binding != null) binding.bottomNavigation.setSelectedItemId(R.id.nav_home);
    }

    /**
     * Navigates to the download queue without creating a second queue fragment.
     */
    public void navigateToQueue() {
        if (getSupportFragmentManager().getBackStackEntryCount() > 0) {
            getSupportFragmentManager().popBackStackImmediate(
                    "SERIES_STACK",
                    FragmentManager.POP_BACK_STACK_INCLUSIVE
            );
        }
        switchTab(downloadFragment);
        binding.bottomNavigation.setSelectedItemId(R.id.nav_download);
    }

    /**
     * Runs the supplied action when notification permission is available, requesting permission first on Android versions that require it.
     * 
     * @param onGranted action to run after permission is granted
     */
    public void requestNotificationPermission(Runnable onGranted) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
                || ContextCompat.checkSelfPermission(
                        this,
                        Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED) {
            if (onGranted != null) {
                onGranted.run();
            }
            return;
        }

        pendingNotificationAction = onGranted;
        ActivityCompat.requestPermissions(
                this,
                new String[]{Manifest.permission.POST_NOTIFICATIONS},
                REQUEST_NOTIFICATION_PERMISSION
        );
    }

    private void showVideoAccessIntroIfNeeded() {
        if (hasFullVideoAccess()) return;

        boolean shown = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .getBoolean(PREF_VIDEO_INTRO_SHOWN, false);

        if (shown) return;

        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .edit()
                .putBoolean(PREF_VIDEO_INTRO_SHOWN, true)
                .apply();

      /*  new MaterialAlertDialogBuilder(this, R.style.KensakuDialogTheme)
                .setTitle("Video access required")
                .setMessage(
                        "KensakuDL needs access to downloaded videos so the built-in video player can play your episodes.\n\n" +
                        "Allow video access to continue."
                )
                .setNegativeButton("Not now", null)
                .setPositiveButton("Continue", (dialog, which) -> requestVideoAccess(null))
                .show();*/
    }

    /**
     * Explains and requests the app's video-file access permission before running the supplied action.
     * 
     * @param onGranted action to run after access is granted
     */
    public void requestVideoAccess(Runnable onGranted) {
        if (hasFullVideoAccess()) {
            if (onGranted != null) {
                onGranted.run();
            }
            return;
        }

        pendingVideoAction = onGranted;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ActivityCompat.requestPermissions(
                    this,
                    new String[]{
                            Manifest.permission.READ_MEDIA_VIDEO,
                            Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
                    },
                    REQUEST_VIDEO_ACCESS
            );
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ActivityCompat.requestPermissions(
                    this,
                    new String[]{
                            Manifest.permission.READ_MEDIA_VIDEO
                    },
                    REQUEST_VIDEO_ACCESS
            );
        } else {
            ActivityCompat.requestPermissions(
                    this,
                    new String[]{
                            Manifest.permission.READ_EXTERNAL_STORAGE
                    },
                    REQUEST_VIDEO_ACCESS
            );
        }
    }

    /**
     * Reports whether the app currently has the video access required by its file workflow.
     * 
     * @return {@code true} when the required access is available
     */
    public boolean hasFullVideoAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.READ_MEDIA_VIDEO
            ) == PackageManager.PERMISSION_GRANTED;
        }

        return ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.READ_EXTERNAL_STORAGE
        ) == PackageManager.PERMISSION_GRANTED;
    }

    /**
     * Resumes the pending permission-gated action when the corresponding permission result is received.
     */
    @Override
    public void onRequestPermissionsResult(
            int requestCode,
            String[] permissions,
            int[] grantResults
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == REQUEST_NOTIFICATION_PERMISSION) {
            Runnable action = pendingNotificationAction;
            pendingNotificationAction = null;

            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED) {
                if (action != null) {
                    action.run();
                }
            } else if (action != null) {
                Toast.makeText(
                        this,
                        "Notification permission is required for download notifications",
                        Toast.LENGTH_SHORT
                ).show();
            }
            return;
        }

        if (requestCode != REQUEST_VIDEO_ACCESS) return;

        Runnable action = pendingVideoAction;
        pendingVideoAction = null;

        if (hasFullVideoAccess()) {
            if (action != null) {
                action.run();
            }
        } else if (action != null) {
            Toast.makeText(
                    this,
                    "Full video access is required to play downloaded episodes",
                    Toast.LENGTH_SHORT
            ).show();
        }
    }
}
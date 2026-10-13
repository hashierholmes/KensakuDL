package hh.kensakudl.app.util;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.text.Html;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.method.LinkMovementMethod;
import android.view.ViewGroup;
import android.view.View;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.LinearLayout;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;

import androidx.annotation.Nullable;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import hh.kensakudl.app.BuildConfig;
import hh.kensakudl.app.R;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Checks the latest GitHub release, compares semantic version components, and presents a scrollable release dialog with formatted release notes and an APK download action.
 */
public final class UpdateChecker {

    private static final String RELEASES_URL =
            "https://api.github.com/repos/hashierholmes/KensakuDL/releases/latest";

    private static final Pattern LINK_PATTERN =
            Pattern.compile("\\[([^\\]]+)]\\((https?://[^\\s)]+)\\)");

    private static final Pattern CODE_PATTERN =
            Pattern.compile("`([^`]+)`");

    private static final Pattern BOLD_PATTERN =
            Pattern.compile("\\*\\*([^*]+)\\*\\*");

    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build();

    private static final Handler MAIN_HANDLER =
            new Handler(Looper.getMainLooper());

    private UpdateChecker() {
    }

    /**
     * Fetches the latest GitHub release off the main thread and displays the update dialog only when a newer version is available.
     * 
     * @param context context used to access resources and present the dialog
     */
    public static void check(Context context) {
        new Thread(() -> {
            Release release = fetchLatestRelease();

            if (release == null) {
                return;
            }

            String currentVersion = BuildConfig.VERSION_NAME;

            if (compareVersions(release.tagName, currentVersion) <= 0) {
                return;
            }

            MAIN_HANDLER.post(() ->
                    showUpdateDialog(context, release)
            );

        }, "KensakuDL-UpdateChecker").start();
    }

    @Nullable
    private static Release fetchLatestRelease() {
        Request request = new Request.Builder()
                .url(RELEASES_URL)
                .header(
                        "Accept",
                        "application/vnd.github+json"
                )
                .header(
                        "X-GitHub-Api-Version",
                        "2022-11-28"
                )
                .get()
                .build();

        try (Response response = CLIENT.newCall(request).execute()) {

            if (!response.isSuccessful()
                    || response.body() == null) {
                return null;
            }

            JsonObject json = JsonParser
                    .parseString(response.body().string())
                    .getAsJsonObject();

            String tagName = getString(json, "tag_name");
            String releaseName = getString(json, "name");
            String body = getString(json, "body");
            String releaseUrl = getString(json, "html_url");

            if (TextUtils.isEmpty(tagName)) {
                return null;
            }

            String apkUrl = findApkUrl(json);

            return new Release(
                    tagName,
                    TextUtils.isEmpty(releaseName)
                            ? tagName
                            : releaseName,
                    body,
                    releaseUrl,
                    apkUrl
            );

        } catch (IOException | RuntimeException ignored) {
            return null;
        }
    }

    @Nullable
    private static String findApkUrl(JsonObject json) {

        if (!json.has("assets")
                || !json.get("assets").isJsonArray()) {
            return null;
        }

        JsonArray assets = json.getAsJsonArray("assets");

        // Prefer the official KensakuDL APK.
        for (JsonElement element : assets) {

            if (!element.isJsonObject()) {
                continue;
            }

            JsonObject asset = element.getAsJsonObject();
            String name = getString(asset, "name");

            if ("KensakuDL.apk".equalsIgnoreCase(name)) {
                return getString(
                        asset,
                        "browser_download_url"
                );
            }
        }

        // Fallback to any APK asset.
        for (JsonElement element : assets) {

            if (!element.isJsonObject()) {
                continue;
            }

            JsonObject asset = element.getAsJsonObject();
            String name = getString(asset, "name");

            if (!TextUtils.isEmpty(name)
                    && name.toLowerCase(Locale.ROOT)
                    .endsWith(".apk")) {

                return getString(
                        asset,
                        "browser_download_url"
                );
            }
        }

        return null;
    }

    private static String getString(
            JsonObject object,
            String key
    ) {

        if (!object.has(key)
                || object.get(key).isJsonNull()) {
            return "";
        }

        try {
            return object.get(key).getAsString();
        } catch (RuntimeException ignored) {
            return "";
        }
    }

    private static void showUpdateDialog(
            Context context,
            Release release
    ) {
        if (!(context instanceof android.app.Activity)) return;

        android.app.Activity activity = (android.app.Activity) context;
        if (activity.isFinishing() || activity.isDestroyed()) return;

        int outerPad = dp(context, 20);
        int ink = 0xFFF1EDF4;
        int muted = 0xFFB9B2C0;
        int maroon = 0xFF8B2B3F;

        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(outerPad, dp(context, 18), outerPad, dp(context, 14));
        GradientDrawable surface = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xFF29232E, 0xFF201D27, 0xFF17171F});
        surface.setCornerRadius(dp(context, 26));
        surface.setStroke(dp(context, 1), 0xFF433441);
        root.setBackground(surface);
        root.setElevation(dp(context, 8));

        // Compact header: integrated into the same surface instead of using
        // Material's separate, flat title area.
        TextView appTitle = new TextView(context);
        appTitle.setText("KensakuDL");
        appTitle.setTextSize(18);
        appTitle.setLetterSpacing(0.015f);
        appTitle.setTextColor(ink);
        appTitle.setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD);
        root.addView(appTitle, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView subtitle = new TextView(context);
        subtitle.setText("UPDATE AVAILABLE  ·  " + release.tagName);
        subtitle.setTextSize(11);
        subtitle.setLetterSpacing(0.09f);
        subtitle.setTextColor(0xFFE08AA0);
        subtitle.setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams subtitleParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        subtitleParams.topMargin = dp(context, 4);
        subtitleParams.bottomMargin = dp(context, 16);
        root.addView(subtitle, subtitleParams);

        View separator = new View(context);
        separator.setBackgroundColor(0x334F3C4D);
        root.addView(separator, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(context, 1)));

        String notes = TextUtils.isEmpty(release.body)
                ? "A new version of KensakuDL is available. Check the release page for details."
                : release.body;

        TextView notesView = new TextView(context);
        notesView.setText(markdownToSpanned(notes));
        notesView.setTextSize(14);
        notesView.setLineSpacing(dp(context, 2), 1.0f);
        notesView.setTextColor(ink);
        notesView.setLinkTextColor(0xFFE18AA0);
        notesView.setMovementMethod(LinkMovementMethod.getInstance());
        notesView.setPadding(dp(context, 1), dp(context, 14), dp(context, 5), dp(context, 14));

        ScrollView scrollView = new ScrollView(context);
        scrollView.setFillViewport(false);
        scrollView.setVerticalScrollBarEnabled(true);
        scrollView.setScrollbarFadingEnabled(true);
        scrollView.setOverScrollMode(ViewGroup.OVER_SCROLL_IF_CONTENT_SCROLLS);
        scrollView.addView(notesView, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        scrollParams.topMargin = dp(context, 2);
        scrollParams.bottomMargin = dp(context, 8);
        root.addView(scrollView, scrollParams);

        View footerSeparator = new View(context);
        footerSeparator.setBackgroundColor(0x334F3C4D);
        root.addView(footerSeparator, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(context, 1)));

        LinearLayout footer = new LinearLayout(context);
        footer.setOrientation(LinearLayout.HORIZONTAL);
        footer.setGravity(android.view.Gravity.END | android.view.Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams footerParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        footerParams.topMargin = dp(context, 12);
        root.addView(footer, footerParams);

        android.widget.Button laterButton = new android.widget.Button(context);
        laterButton.setText("LATER");
        laterButton.setTextSize(12);
        laterButton.setLetterSpacing(0.06f);
        laterButton.setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD);
        laterButton.setTextColor(muted);
        laterButton.setAllCaps(false);
        laterButton.setMinHeight(dp(context, 44));
        laterButton.setBackground(makeGlossButton(context,
                0xFF302B37, 0xFF24212C, 0xFF49404F));
        laterButton.setPadding(dp(context, 18), 0, dp(context, 18), 0);
        footer.addView(laterButton, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(context, 44)));

        android.widget.Button updateButton = new android.widget.Button(context);
        updateButton.setText("UPDATE");
        updateButton.setTextSize(12);
        updateButton.setLetterSpacing(0.06f);
        updateButton.setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD);
        updateButton.setTextColor(0xFFFFF0F3);
        updateButton.setAllCaps(false);
        updateButton.setMinHeight(dp(context, 44));
        updateButton.setBackground(makeGlossButton(context,
                0xFFAA3A55, 0xFF7F263F, 0xFFC45A75));
        updateButton.setPadding(dp(context, 22), 0, dp(context, 22), 0);
        LinearLayout.LayoutParams updateParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(context, 44));
        updateParams.leftMargin = dp(context, 10);
        footer.addView(updateButton, updateParams);

        androidx.appcompat.app.AlertDialog dialog =
                new MaterialAlertDialogBuilder(context, R.style.KensakuDialogTheme)
                        .setView(root)
                        .create();
        laterButton.setOnClickListener(v -> dialog.dismiss());
        updateButton.setOnClickListener(v -> {
            String targetUrl = !TextUtils.isEmpty(release.apkUrl)
                    ? release.apkUrl : release.releaseUrl;
            if (!TextUtils.isEmpty(targetUrl)) {
                try {
                    context.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(targetUrl)));
                } catch (RuntimeException ignoredException) {
                    // Keep the dialog safe if no app can open the release URL.
                }
            }
            dialog.dismiss();
        });

        dialog.setOnShowListener(ignored -> {
            android.view.Window window = dialog.getWindow();
            if (window != null) {
                window.setBackgroundDrawableResource(android.R.color.transparent);
                window.addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND);
                android.view.WindowManager.LayoutParams params = window.getAttributes();
                params.dimAmount = 0.72f;
                window.setAttributes(params);
                int screenWidth = context.getResources().getDisplayMetrics().widthPixels;
                int screenHeight = context.getResources().getDisplayMetrics().heightPixels;
                window.setLayout(
                        Math.min(screenWidth - dp(context, 32), dp(context, 520)),
                        Math.min((int) (screenHeight * 0.84f), dp(context, 760)));
            }
        });
        dialog.show();
        // Apply size after show, because AlertDialog can otherwise override it.
        android.view.Window window = dialog.getWindow();
        if (window != null) {
            int screenWidth = context.getResources().getDisplayMetrics().widthPixels;
            int screenHeight = context.getResources().getDisplayMetrics().heightPixels;
            window.setBackgroundDrawableResource(android.R.color.transparent);
            window.setLayout(Math.min(screenWidth - dp(context, 32), dp(context, 520)),
                    Math.min((int) (screenHeight * 0.84f), dp(context, 760)));
        }
    }

    private static GradientDrawable makeGlossButton(
            Context context, int topColor, int bottomColor, int strokeColor) {
        GradientDrawable drawable = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{topColor, bottomColor});
        drawable.setCornerRadius(dp(context, 14));
        drawable.setStroke(dp(context, 1), strokeColor);
        return drawable;
    }

    /**
     * Converts the small Markdown subset commonly used
     * in GitHub release notes into Android styled text.
     */
    private static Spanned markdownToSpanned(
            String markdown
    ) {

        String normalized = markdown
                .replace("\r\n", "\n")
                .replace('\r', '\n');

        String[] lines =
                normalized.split("\n", -1);

        StringBuilder html =
                new StringBuilder();

        for (String line : lines) {

            String trimmed = line.trim();

            // Empty line.
            if (trimmed.isEmpty()) {
                html.append("<br>");
                continue;
            }

            // # Heading
            // ## Heading
            // ### Heading
            if (trimmed.matches(
                    "^#{1,3}\\s+.*"
            )) {

                int level = 0;

                while (level < 3
                        && level < trimmed.length()
                        && trimmed.charAt(level) == '#') {
                    level++;
                }

                String heading =
                        trimmed.substring(level).trim();

                html.append("<h")
                        .append(level)
                        .append(">");

                html.append(
                        formatInlineMarkdown(heading)
                );

                html.append("</h")
                        .append(level)
                        .append(">");

                continue;
            }

            // Markdown bullet.
            if (trimmed.startsWith("- ")
                    || trimmed.startsWith("* ")
                    || trimmed.startsWith("+ ")) {

                String item =
                        trimmed.substring(2).trim();

                html.append("&#8226;&nbsp;");

                html.append(
                        formatInlineMarkdown(item)
                );

                html.append("<br>");

                continue;
            }

            // Markdown horizontal rule.
            if (trimmed.matches(
                    "^[-*_]{3,}$"
            )) {

                html.append("<hr>");
                continue;
            }

            // Normal paragraph.
            html.append(
                    formatInlineMarkdown(trimmed)
            );

            html.append("<br>");
        }

        String htmlText = html.toString();

        if (android.os.Build.VERSION.SDK_INT
                >= android.os.Build.VERSION_CODES.N) {

            return Html.fromHtml(
                    htmlText,
                    Html.FROM_HTML_MODE_LEGACY
            );
        }

        //noinspection deprecation
        return Html.fromHtml(htmlText);
    }

    /**
     * Handles inline Markdown such as bold text,
     * inline code and links.
     */
    private static String formatInlineMarkdown(
            String text
    ) {

        // Escape HTML first.
        String escaped =
                TextUtils.htmlEncode(text);

        // [Text](https://example.com)
        Matcher linkMatcher =
                LINK_PATTERN.matcher(escaped);

        StringBuffer linkResult =
                new StringBuffer();

        while (linkMatcher.find()) {

            String label =
                    linkMatcher.group(1);

            String url =
                    linkMatcher.group(2);

            String replacement =
                    "<a href=\""
                            + url
                            + "\">"
                            + label
                            + "</a>";

            linkMatcher.appendReplacement(
                    linkResult,
                    Matcher.quoteReplacement(
                            replacement
                    )
            );
        }

        linkMatcher.appendTail(linkResult);

        escaped = linkResult.toString();

        // `code`
        Matcher codeMatcher =
                CODE_PATTERN.matcher(escaped);

        StringBuffer codeResult =
                new StringBuffer();

        while (codeMatcher.find()) {

            String replacement =
                    "<tt>"
                            + codeMatcher.group(1)
                            + "</tt>";

            codeMatcher.appendReplacement(
                    codeResult,
                    Matcher.quoteReplacement(
                            replacement
                    )
            );
        }

        codeMatcher.appendTail(codeResult);

        escaped = codeResult.toString();

        // **bold**
        Matcher boldMatcher =
                BOLD_PATTERN.matcher(escaped);

        StringBuffer boldResult =
                new StringBuffer();

        while (boldMatcher.find()) {

            String replacement =
                    "<b>"
                            + boldMatcher.group(1)
                            + "</b>";

            boldMatcher.appendReplacement(
                    boldResult,
                    Matcher.quoteReplacement(
                            replacement
                    )
            );
        }

        boldMatcher.appendTail(boldResult);

        return boldResult.toString();
    }

    private static int compareVersions(
            String remote,
            String local
    ) {

        String[] remoteParts =
                normalizeVersion(remote)
                        .split("\\.");

        String[] localParts =
                normalizeVersion(local)
                        .split("\\.");

        int count =
                Math.max(
                        remoteParts.length,
                        localParts.length
                );

        for (int i = 0; i < count; i++) {

            int remotePart =
                    i < remoteParts.length
                            ? parseVersionPart(
                                    remoteParts[i]
                            )
                            : 0;

            int localPart =
                    i < localParts.length
                            ? parseVersionPart(
                                    localParts[i]
                            )
                            : 0;

            if (remotePart != localPart) {
                return Integer.compare(
                        remotePart,
                        localPart
                );
            }
        }

        return 0;
    }

    private static String normalizeVersion(
            String version
    ) {

        if (version == null) {
            return "0";
        }

        String normalized =
                version.trim();

        while (normalized.startsWith("v")
                || normalized.startsWith("V")) {

            normalized =
                    normalized.substring(1);
        }

        int dash =
                normalized.indexOf('-');

        if (dash >= 0) {
            normalized =
                    normalized.substring(0, dash);
        }

        return TextUtils.isEmpty(normalized)
                ? "0"
                : normalized;
    }

    private static int parseVersionPart(
            String part
    ) {

        try {
            return Integer.parseInt(part);
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private static int dp(
            Context context,
            int value
    ) {

        return Math.round(
                value
                        * context
                        .getResources()
                        .getDisplayMetrics()
                        .density
        );
    }

    private static final class Release {

        final String tagName;
        final String name;
        final String body;
        final String releaseUrl;
        final String apkUrl;

        Release(
                String tagName,
                String name,
                String body,
                String releaseUrl,
                String apkUrl
        ) {

            this.tagName = tagName;
            this.name = name;
            this.body = body;
            this.releaseUrl = releaseUrl;
            this.apkUrl = apkUrl;
        }
    }

}
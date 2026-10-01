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
import android.widget.ScrollView;
import android.widget.TextView;

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
 * Checks GitHub Releases for a newer KensakuDL version and presents the release details.
 *
 * <p>Network work is performed off the main thread. Only the final dialog presentation is
 * posted back to the main looper.</p>
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
 * Checks the latest GitHub release and shows an update dialog when it is newer.
 *
 * @param context context used to display the release information
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

        if (!(context instanceof android.app.Activity)) {
            return;
        }

        android.app.Activity activity =
                (android.app.Activity) context;

        if (activity.isFinishing()
                || activity.isDestroyed()) {
            return;
        }

        TextView notesView = new TextView(context);

        String notes = TextUtils.isEmpty(release.body)
                ? "A new version of KensakuDL is available."
                : release.body;

        notesView.setText(
                markdownToSpanned(notes)
        );

        notesView.setTextSize(14);

        notesView.setTextColor(
                androidx.core.content.ContextCompat.getColor(
                        context,
                        R.color.text_primary
                )
        );

        notesView.setMovementMethod(
                LinkMovementMethod.getInstance()
        );

        int padding = dp(context, 20);

        notesView.setPadding(
                padding,
                0,
                padding,
                0
        );

        ScrollView scrollView = new ScrollView(context);

        scrollView.setFillViewport(true);

        scrollView.addView(
                notesView,
                new ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                )
        );

        String title = TextUtils.isEmpty(release.name)
                ? "KensakuDL Update Available"
                : release.name;

        MaterialAlertDialogBuilder builder =
                new MaterialAlertDialogBuilder(
                        context,
                        R.style.KensakuDialogTheme
                )
                .setTitle(title)
                .setMessage(
                        "Version "
                                + release.tagName
                                + " is now available."
                )
                .setView(scrollView)
                .setNegativeButton(
                        "Later",
                        null
                )
                .setPositiveButton(
                        "Update",
                        null
                );

        androidx.appcompat.app.AlertDialog dialog =
                builder.create();

        dialog.setOnShowListener(ignored -> {

            dialog.getButton(
                    androidx.appcompat.app.AlertDialog
                            .BUTTON_POSITIVE
            ).setOnClickListener(v -> {

                String targetUrl =
                        !TextUtils.isEmpty(release.apkUrl)
                                ? release.apkUrl
                                : release.releaseUrl;

                if (!TextUtils.isEmpty(targetUrl)) {

                    try {

                        context.startActivity(
                                new Intent(
                                        Intent.ACTION_VIEW,
                                        Uri.parse(targetUrl)
                                )
                        );

                    } catch (RuntimeException ignoredException) {
                        
                    }
                }

                dialog.dismiss();
            });
        });

        dialog.show();
    }

    
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

            
            if (trimmed.isEmpty()) {
                html.append("<br>");
                continue;
            }

            
            
            
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

            
            if (trimmed.matches(
                    "^[-*_]{3,}$"
            )) {

                html.append("<hr>");
                continue;
            }

            
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

        
        return Html.fromHtml(htmlText);
    }

    
    private static String formatInlineMarkdown(
            String text
    ) {

        
        String escaped =
                TextUtils.htmlEncode(text);

        
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
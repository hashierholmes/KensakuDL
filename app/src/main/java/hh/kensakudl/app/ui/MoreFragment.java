package hh.kensakudl.app.ui;

import android.content.Intent;
import android.content.pm.PackageInfo;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.method.LinkMovementMethod;
import android.text.style.ClickableSpan;
import android.text.style.ForegroundColorSpan;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import hh.kensakudl.app.databinding.FragmentMoreBinding;
/**
 * Displays application information, version details, and external project links.
 */

public class MoreFragment extends Fragment {
    private FragmentMoreBinding binding;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        binding = FragmentMoreBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        
        setupAppVersion();

        
        binding.btnViewGithub.setOnClickListener(v -> {
            openUrl("https://github.com/hashierholmes/KensakuDL");
        });

        
        setupFooter();
    }

    private void setupAppVersion() {
        try {
            PackageInfo pInfo = requireContext().getPackageManager().getPackageInfo(requireContext().getPackageName(), 0);
            binding.tvAppVersion.setText("Version " + pInfo.versionName);
        } catch (Exception e) {
            binding.tvAppVersion.setText("Version 1.0");
        }
    }

    private void setupFooter() {
        String fullText = "Made with ♥ by @hashierholmes";
        SpannableString spannable = new SpannableString(fullText);

        int maroonColor = Color.parseColor("#A4384F");

        
        int heartIndex = fullText.indexOf("♥");
        if (heartIndex >= 0) {
            spannable.setSpan(
                new ForegroundColorSpan(maroonColor),
                heartIndex,
                heartIndex + 1,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            );
        }

        
        int userIndex = fullText.indexOf("@hashierholmes");
        if (userIndex >= 0) {
            int end = userIndex + "@hashierholmes".length();

            ClickableSpan clickableSpan = new ClickableSpan() {
                @Override
                public void onClick(@NonNull View widget) {
                    openUrl("https://hash.is-a.dev");
                }

                @Override
                public void updateDrawState(@NonNull TextPaint ds) {
                    super.updateDrawState(ds);
                    ds.setColor(maroonColor);
                    ds.setUnderlineText(false);
                }
            };

            spannable.setSpan(clickableSpan, userIndex, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            binding.tvFooter.setText(spannable);
            binding.tvFooter.setMovementMethod(LinkMovementMethod.getInstance());
            binding.tvFooter.setHighlightColor(Color.TRANSPARENT);
        } else {
            binding.tvFooter.setText(fullText);
        }
    }

    private void openUrl(String url) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            startActivity(intent);
        } catch (Exception ignored) {}
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}
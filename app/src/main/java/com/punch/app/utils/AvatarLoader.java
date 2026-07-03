package com.punch.app.utils;

import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.DataSource;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.load.engine.GlideException;
import com.bumptech.glide.load.model.GlideUrl;
import com.bumptech.glide.load.model.LazyHeaders;
import com.bumptech.glide.request.RequestListener;
import com.bumptech.glide.request.target.Target;

import java.io.File;
import java.util.Locale;

public final class AvatarLoader {
    private AvatarLoader() {
    }

    public static void load(ImageView imageView,
                            TextView fallbackView,
                            String displayName,
                            @Nullable String source) {
        fallbackView.setText(buildAvatarText(displayName));
        fallbackView.setVisibility(TextView.VISIBLE);
        Glide.with(imageView).clear(imageView);
        imageView.setImageDrawable(null);
        imageView.setVisibility(ImageView.INVISIBLE);

        Object model = buildModel(source);
        if (model == null) {
            return;
        }

        Glide.with(imageView)
                .load(model)
                .diskCacheStrategy(DiskCacheStrategy.ALL)
                .circleCrop()
                .listener(new RequestListener<Drawable>() {
                    @Override
                    public boolean onLoadFailed(@Nullable GlideException e,
                                                Object model,
                                                Target<Drawable> target,
                                                boolean isFirstResource) {
                        fallbackView.setVisibility(TextView.VISIBLE);
                        imageView.setImageDrawable(null);
                        imageView.setVisibility(ImageView.INVISIBLE);
                        return false;
                    }

                    @Override
                    public boolean onResourceReady(Drawable resource,
                                                   Object model,
                                                   Target<Drawable> target,
                                                   DataSource dataSource,
                                                   boolean isFirstResource) {
                        fallbackView.setVisibility(TextView.GONE);
                        imageView.setVisibility(ImageView.VISIBLE);
                        return false;
                    }
                })
                .into(imageView);
    }

    public static void clear(ImageView imageView) {
        Glide.with(imageView).clear(imageView);
        imageView.setImageDrawable(null);
        imageView.setVisibility(ImageView.INVISIBLE);
    }

    private static Object buildModel(@Nullable String rawSource) {
        if (rawSource == null) {
            return null;
        }
        String source = rawSource.trim();
        if (source.isEmpty()) {
            return null;
        }
        if (source.startsWith("content://")) {
            return Uri.parse(source);
        }
        if (source.startsWith("http://") || source.startsWith("https://")) {
            return buildGlideUrl(source);
        }

        File file = new File(source);
        if (file.exists()) {
            return file;
        }

        String baseUrl = SessionManager.get().getBaseUrl();
        if (source.startsWith("/")) {
            return buildGlideUrl(baseUrl + source);
        }
        return buildGlideUrl(baseUrl + "/" + source);
    }

    private static GlideUrl buildGlideUrl(String url) {
        LazyHeaders.Builder headers = new LazyHeaders.Builder()
                .addHeader("X-Device-Id", SessionManager.get().getDeviceId());
        String token = SessionManager.get().getToken();
        if (token != null && !token.trim().isEmpty()) {
            headers.addHeader("Authorization", "Bearer " + token.trim());
        }
        return new GlideUrl(url, headers.build());
    }

    private static String buildAvatarText(String name) {
        if (name == null) {
            return "?";
        }
        String trimmed = name.trim();
        if (trimmed.isEmpty()) {
            return "?";
        }
        return trimmed.substring(0, 1).toUpperCase(Locale.getDefault());
    }
}

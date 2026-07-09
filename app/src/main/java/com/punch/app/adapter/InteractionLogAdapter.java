package com.punch.app.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.punch.app.R;
import com.punch.app.network.InteractionLogEntry;
import com.punch.app.network.InteractionLogger;
import com.punch.app.utils.LogDisplayFormatter;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class InteractionLogAdapter extends RecyclerView.Adapter<InteractionLogAdapter.VH> {
    private final List<InteractionLogEntry> items = new ArrayList<>();
    private final Set<String> expandedIds = new HashSet<>();
    private final SimpleDateFormat timeFormat =
            new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());

    public void submit(List<InteractionLogEntry> entries) {
        items.clear();
        if (entries != null) {
            items.addAll(entries);
        }
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_interaction_log, parent, false);
        return new VH(view);
    }

    @Override
    public void onBindViewHolder(@NonNull VH holder, int position) {
        InteractionLogEntry entry = items.get(position);
        boolean expanded = expandedIds.contains(entry.id);
        holder.tvTime.setText(timeFormat.format(new Date(entry.timeMillis)));
        holder.tvTitle.setText(resolveTitle(entry));
        holder.tvMeta.setText(buildMeta(entry));
        holder.tvStatus.setText(entry.success ? "OK" : "FAIL");
        holder.tvStatus.setSelected(entry.success);
        holder.tvStatus.setTextColor(ContextCompat.getColor(
                holder.itemView.getContext(),
                entry.success ? R.color.log_status_success : R.color.log_status_error
        ));
        holder.tvDetail.setText(LogDisplayFormatter.buildDetail(entry));
        holder.tvDetail.setVisibility(expanded ? View.VISIBLE : View.GONE);
        holder.itemView.setOnClickListener(v -> {
            if (expanded) {
                expandedIds.remove(entry.id);
            } else {
                expandedIds.add(entry.id);
            }
            int adapterPosition = holder.getBindingAdapterPosition();
            if (adapterPosition != RecyclerView.NO_POSITION) {
                notifyItemChanged(adapterPosition);
            }
        });
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    private String buildMeta(InteractionLogEntry entry) {
        StringBuilder builder = new StringBuilder();
        String groupLabel = InteractionLogger.groupLabel(entry.group);
        if (!groupLabel.trim().isEmpty()) {
            builder.append(groupLabel);
        }
        if (InteractionLogger.CATEGORY_BUSINESS.equals(entry.category)) {
            String detailPreview = firstLine(entry.detail);
            if (!detailPreview.isEmpty()) {
                if (builder.length() > 0) {
                    builder.append("  ");
                }
                builder.append(detailPreview);
            }
            return builder.toString();
        }
        if (entry.httpStatus > 0) {
            if (builder.length() > 0) {
                builder.append("  ");
            }
            builder.append("HTTP ").append(entry.httpStatus);
        }
        if (entry.backendCode != 0) {
            if (builder.length() > 0) {
                builder.append("  ");
            }
            builder.append("code ").append(entry.backendCode);
        }
        if (entry.durationMs > 0) {
            if (builder.length() > 0) {
                builder.append("  ");
            }
            builder.append(entry.durationMs).append(" ms");
        }
        if (entry.backendMessage != null && !entry.backendMessage.trim().isEmpty()) {
            if (builder.length() > 0) {
                builder.append("  ");
            }
            builder.append(entry.backendMessage.trim());
        } else if (entry.errorMessage != null && !entry.errorMessage.trim().isEmpty()) {
            if (builder.length() > 0) {
                builder.append("  ");
            }
            builder.append(entry.errorMessage.trim());
        }
        return builder.toString();
    }

    private String resolveTitle(InteractionLogEntry entry) {
        String title = entry.title == null ? "" : entry.title.trim();
        if (!title.isEmpty()) {
            return title;
        }
        return (entry.method + " " + entry.path).trim();
    }

    private String firstLine(String value) {
        if (value == null) {
            return "";
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return "";
        }
        int lineBreak = trimmed.indexOf('\n');
        return lineBreak >= 0 ? trimmed.substring(0, lineBreak).trim() : trimmed;
    }

    static final class VH extends RecyclerView.ViewHolder {
        final TextView tvTime;
        final TextView tvTitle;
        final TextView tvMeta;
        final TextView tvStatus;
        final TextView tvDetail;

        VH(@NonNull View itemView) {
            super(itemView);
            tvTime = itemView.findViewById(R.id.tv_log_time);
            tvTitle = itemView.findViewById(R.id.tv_log_title);
            tvMeta = itemView.findViewById(R.id.tv_log_meta);
            tvStatus = itemView.findViewById(R.id.tv_log_status);
            tvDetail = itemView.findViewById(R.id.tv_log_detail);
        }
    }
}

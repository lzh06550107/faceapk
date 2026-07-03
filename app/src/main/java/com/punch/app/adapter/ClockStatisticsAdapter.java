package com.punch.app.adapter;

import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.punch.app.R;
import com.punch.app.network.dto.PunchDto;
import com.punch.app.utils.AvatarLoader;

import java.util.ArrayList;
import java.util.List;

public class ClockStatisticsAdapter extends RecyclerView.Adapter<ClockStatisticsAdapter.VH> {
    private final List<PunchDto.Row> items = new ArrayList<>();

    public void replace(List<PunchDto.Row> data) {
        items.clear();
        if (data != null) {
            items.addAll(data);
        }
        notifyDataSetChanged();
    }

    public void append(List<PunchDto.Row> data) {
        if (data == null || data.isEmpty()) {
            return;
        }
        int start = items.size();
        items.addAll(data);
        notifyItemRangeInserted(start, data.size());
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_punch_record, parent, false);
        return new VH(view);
    }

    @Override
    public void onBindViewHolder(@NonNull VH holder, int position) {
        PunchDto.Row row = items.get(position);
        AvatarLoader.load(holder.ivAvatar, holder.tvAvatar, row.name, row.facePath);
        holder.tvName.setText(TextUtils.isEmpty(row.name) ? "--" : row.name);
        holder.tvDept.setText(buildDeptText(row));
        holder.tvTime.setText(TextUtils.isEmpty(row.clockTime) ? "--" : row.clockTime);
        holder.tvType.setText(TextUtils.isEmpty(row.clockStatusText) ? "未知状态" : row.clockStatusText);
        holder.tvType.setBackgroundColor(resolveClockStatusColor(row.clockStatus));
        holder.tvSync.setText(resolveSignText(row.sign));
        holder.tvSync.setTextColor(resolveSignColor(row.sign));

        String extra = buildExtraText(row);
        if (extra.isEmpty()) {
            holder.tvExtra.setVisibility(View.GONE);
            holder.tvExtra.setText("");
        } else {
            holder.tvExtra.setVisibility(View.VISIBLE);
            holder.tvExtra.setText(extra);
        }
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    @Override
    public void onViewRecycled(@NonNull VH holder) {
        super.onViewRecycled(holder);
        AvatarLoader.clear(holder.ivAvatar);
        holder.tvAvatar.setVisibility(View.VISIBLE);
    }

    private String buildDeptText(PunchDto.Row row) {
        String numbers = row.numbers == null ? "" : row.numbers.trim();
        String lineName = row.lineName == null ? "" : row.lineName.trim();
        if (!numbers.isEmpty() && !lineName.isEmpty()) {
            return numbers + " / " + lineName;
        }
        return !numbers.isEmpty() ? numbers : lineName;
    }

    private String buildExtraText(PunchDto.Row row) {
        List<String> parts = new ArrayList<>(3);
        if (row.specialText != null && !row.specialText.trim().isEmpty()) {
            parts.add(row.specialText.trim());
        }
        if (row.lateMinutes > 0) {
            parts.add("迟到 " + row.lateMinutes + " 分钟");
        }
        if (row.earlyMinutes > 0) {
            parts.add("早退 " + row.earlyMinutes + " 分钟");
        }
        return TextUtils.join(" / ", parts);
    }

    private int resolveClockStatusColor(int clockStatus) {
        if (clockStatus == 1) {
            return 0xFF2E7D32;
        }
        if (clockStatus == 2) {
            return 0xFFC62828;
        }
        if (clockStatus == 3) {
            return 0xFFEF6C00;
        }
        return 0xFF546E7A;
    }

    private String resolveSignText(String sign) {
        if ("normal".equalsIgnoreCase(sign)) {
            return "正常";
        }
        if ("absence".equalsIgnoreCase(sign)) {
            return "缺卡";
        }
        if ("late".equalsIgnoreCase(sign)) {
            return "迟到";
        }
        if ("leave".equalsIgnoreCase(sign)) {
            return "请假";
        }
        if ("out".equalsIgnoreCase(sign)) {
            return "外出";
        }
        if ("travel".equalsIgnoreCase(sign)) {
            return "出差";
        }
        if ("early".equalsIgnoreCase(sign)) {
            return "早退";
        }
        return "未知";
    }

    private int resolveSignColor(String sign) {
        if ("normal".equalsIgnoreCase(sign)) {
            return 0xFF2E7D32;
        }
        if ("absence".equalsIgnoreCase(sign)) {
            return 0xFFC62828;
        }
        if ("late".equalsIgnoreCase(sign) || "early".equalsIgnoreCase(sign)) {
            return 0xFFEF6C00;
        }
        if ("leave".equalsIgnoreCase(sign)) {
            return 0xFF1565C0;
        }
        if ("out".equalsIgnoreCase(sign)) {
            return 0xFF00838F;
        }
        if ("travel".equalsIgnoreCase(sign)) {
            return 0xFF6A1B9A;
        }
        return 0xFF546E7A;
    }

    static class VH extends RecyclerView.ViewHolder {
        final ImageView ivAvatar;
        final TextView tvAvatar;
        final TextView tvName;
        final TextView tvDept;
        final TextView tvExtra;
        final TextView tvTime;
        final TextView tvType;
        final TextView tvSync;

        VH(@NonNull View view) {
            super(view);
            ivAvatar = view.findViewById(R.id.iv_avatar);
            tvAvatar = view.findViewById(R.id.tv_avatar);
            tvName = view.findViewById(R.id.tv_name);
            tvDept = view.findViewById(R.id.tv_dept);
            tvExtra = view.findViewById(R.id.tv_extra);
            tvTime = view.findViewById(R.id.tv_time);
            tvType = view.findViewById(R.id.tv_type);
            tvSync = view.findViewById(R.id.tv_sync);
        }
    }
}

package com.punch.app.adapter;

import android.content.Context;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.punch.app.R;
import com.punch.app.face.FaceFileManager;
import com.punch.app.model.Employee;
import com.punch.app.utils.AvatarLoader;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class LocalEmployeeDebugAdapter extends RecyclerView.Adapter<LocalEmployeeDebugAdapter.ViewHolder> {
    private final Context appContext;
    private final List<Employee> items = new ArrayList<>();
    private final SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());

    public LocalEmployeeDebugAdapter(Context context) {
        this.appContext = context.getApplicationContext();
    }

    public void submit(List<Employee> employees) {
        items.clear();
        if (employees != null) {
            items.addAll(employees);
        }
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_local_employee_debug, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        Employee employee = items.get(position);
        holder.tvName.setText(safe(employee.name, "-"));
        holder.tvId.setText("工号: " + safe(employee.id, "-"));
        holder.tvLine.setText("线体: " + buildLineText(employee));
        holder.tvStatus.setText(buildStatusText(employee));
        holder.tvMeta.setText(buildMetaText(employee));

        String avatarSource = resolveAvatarSource(employee);
        AvatarLoader.load(holder.ivAvatar, holder.tvAvatar, employee.name, avatarSource);
    }

    @Override
    public void onViewRecycled(@NonNull ViewHolder holder) {
        super.onViewRecycled(holder);
        AvatarLoader.clear(holder.ivAvatar);
        holder.tvAvatar.setVisibility(View.VISIBLE);
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    private String resolveAvatarSource(Employee employee) {
        if (!TextUtils.isEmpty(employee.id)
                && employee.faceRegistered == 1
                && !TextUtils.isEmpty(employee.localFaceId)) {
            String localPath = FaceFileManager.getFaceImagePath(appContext, employee.id);
            java.io.File localFile = new java.io.File(localPath);
            if (localFile.exists()) {
                return localPath;
            }
        }
        return employee.faceImageUrl;
    }

    private String buildLineText(Employee employee) {
        String lineName = safe(employee.assignedLineName, "");
        String lineCode = safe(employee.assignedLineCode, "");
        if (lineName.isEmpty() && lineCode.isEmpty()) {
            return "-";
        }
        if (lineName.isEmpty() || lineName.equals(lineCode)) {
            return lineCode;
        }
        if (lineCode.isEmpty()) {
            return lineName;
        }
        return lineName + " (" + lineCode + ")";
    }

    private String buildStatusText(Employee employee) {
        StringBuilder builder = new StringBuilder();
        builder.append(employee.isDeleted == 1 ? "已删除" : "有效");
        builder.append(" / ");
        builder.append(employee.faceRegistered == 1 ? "人脸已注册" : "人脸未注册");
        builder.append(" / ");
        builder.append("face_status=");
        builder.append(safe(employee.faceStatus, "-"));
        builder.append(" / ");
        builder.append("status=");
        builder.append(safe(employee.status, "-"));
        return builder.toString();
    }

    private String buildMetaText(Employee employee) {
        String dept = safe(employee.dept, "-");
        String updatedAt = employee.updatedAt > 0
                ? dateFormat.format(new Date(employee.updatedAt * 1000L))
                : "-";
        return "部门: " + dept
                + "\n更新时间: " + updatedAt
                + "\n本地人脸ID: " + safe(employee.localFaceId, "-");
    }

    private String safe(String value, String fallback) {
        return value == null || value.trim().isEmpty() ? fallback : value.trim();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        private final ImageView ivAvatar;
        private final TextView tvAvatar;
        private final TextView tvName;
        private final TextView tvId;
        private final TextView tvLine;
        private final TextView tvStatus;
        private final TextView tvMeta;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            ivAvatar = itemView.findViewById(R.id.iv_local_employee_avatar);
            tvAvatar = itemView.findViewById(R.id.tv_local_employee_avatar_fallback);
            tvName = itemView.findViewById(R.id.tv_local_employee_name);
            tvId = itemView.findViewById(R.id.tv_local_employee_id);
            tvLine = itemView.findViewById(R.id.tv_local_employee_line);
            tvStatus = itemView.findViewById(R.id.tv_local_employee_status);
            tvMeta = itemView.findViewById(R.id.tv_local_employee_meta);
        }
    }
}
